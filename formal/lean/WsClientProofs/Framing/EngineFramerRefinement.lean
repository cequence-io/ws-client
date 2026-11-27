import WsClientProofs.Framing.ExtractedDrivers
import WsClientProofs.Framing.EngineFramerProof

/-!
# The extracted `StreamTransformers.DelimiterFramer` is the model the proofs are about

As for `ByteFramingRefinement`: the extracted code (`Int` indices, loops over a step counter) is
proved equal to the hand-written model `EngineFramerProof` reasons about, so the theorems about
the model are theorems about the shipped code. This framer has no invariant to carry: every
state, every chunk.
-/

namespace WsClientProofs.Framing.EngineFramerRefinement

open WsClientProofs.Java WsClientProofs.Framing
open WsClientProofs.Framing.ExtractedDrivers (embedEnd embedResult)

abbrev EE := WsClientProofs.Framing.EngineFramerExtracted.State
abbrev EM := WsClientProofs.Framing.EngineFramerModel.State UInt8

/-- The extracted state of a model state (`Nat` fields as `Int`). -/
def embed (m : EM) : EE := { buffer := m.buffer, scanFrom := m.scanFrom }

/-- The failures of the model as the exceptions the code throws. -/
def toExt : EngineFramerModel.Failure → EngineFramerExtracted.Failure
  | .frameTooLong => .CequenceWSFrameTooLongException
  | .restTooLong => .CequenceWSFrameTooLongException

def lift {α β : Type} (f : α → β) :
    Except EngineFramerModel.Failure α → Except EngineFramerExtracted.Failure β
  | .ok a => .ok (f a)
  | .error e => .error (toExt e)

theorem arrayGet_natCast {α : Type} (a : List α) (n : Nat) :
    Scala.arrayGet a (n : Int) = a[n]? := by
  unfold Scala.arrayGet
  rw [if_neg (by omega)]
  simp

/-! ## The search loops -/

/-- The inner loop of `indexOfDelimiter` is `matchLength`. -/
theorem loop2_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (s : EE) (i : Nat) :
    ∀ (steps j : Nat), d.length - j < steps →
      EngineFramerExtracted.indexOfDelimiter_loop2 d mfl fuel steps s (i : Int) (j : Int) =
        .ok ((matchLength d s.buffer i j : Nat) : Int) := by
  intro steps
  induction steps with
  | zero => intro j h; omega
  | succ n ih =>
    intro j hj
    rw [EngineFramerExtracted.indexOfDelimiter_loop2, matchLength]
    have hidx : ((i : Int) + (j : Int)) = ((i + j : Nat) : Int) := by push_cast; rfl
    simp only [hidx, arrayGet_natCast]
    have hiff : ((decide ((j : Int) < (d.length : Int)) && s.buffer[i + j]? == d[j]?) = true) ↔
        (j < d.length ∧ s.buffer[i + j]? = d[j]?) := by
      simp [Int.ofNat_lt]
    by_cases hc : j < d.length ∧ s.buffer[i + j]? = d[j]?
    · rw [if_pos (hiff.mpr hc), if_pos hc]
      have := ih (j + 1) (by omega)
      rw [show ((j : Int) + 1) = ((j + 1 : Nat) : Int) by push_cast; rfl]
      exact this
    · rw [if_neg (fun h => hc (hiff.mp h)), if_neg hc]
      rfl

/-- The outer loop of `indexOfDelimiter` is `scan`: the index of the first delimiter, or the
first index past the last start that leaves room for one. -/
theorem loop1_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (hf : d.length < fuel) (s : EE)
    (e : Nat) :
    ∀ (steps i : Nat), e + 1 - i < steps →
      EngineFramerExtracted.indexOfDelimiter_loop1 d mfl fuel steps s (i : Int)
          ((e : Int) - (d.length : Int)) =
        match scan d s.buffer e i with
        | some j => .ok (.inl (s, (j : Int)))
        | none => .ok (.inr (max (i : Int) ((e : Int) - (d.length : Int) + 1))) := by
  intro steps
  induction steps with
  | zero => intro i h; omega
  | succ n ih =>
    intro i hi
    rw [EngineFramerExtracted.indexOfDelimiter_loop1, scan]
    by_cases hle : i + d.length ≤ e
    · have hle' : (i : Int) ≤ (e : Int) - (d.length : Int) := by omega
      rw [if_pos hle]
      simp only [hle', if_true]
      have hm : EngineFramerExtracted.indexOfDelimiter_loop2 d mfl fuel fuel s (i : Int) (0 : Int) =
          .ok ((matchLength d s.buffer i 0 : Nat) : Int) :=
        loop2_eq d mfl fuel s i fuel 0 (by omega)
      simp only [hm, bind, Except.bind, pure, Except.pure]
      by_cases hz : matchLength d s.buffer i 0 = d.length
      · have hz' : ((matchLength d s.buffer i 0 : Nat) : Int) = (d.length : Int) := by
          exact_mod_cast hz
        rw [if_pos hz]
        simp [hz']
      · have hz' : ¬ ((matchLength d s.buffer i 0 : Nat) : Int) = (d.length : Int) := by
          intro h; exact hz (by exact_mod_cast h)
        rw [if_neg hz]
        have hb : ¬ (((matchLength d s.buffer i 0 : Nat) : Int) == (d.length : Int)) = true :=
          fun h => hz' (beq_iff_eq.mp h)
        rw [if_neg hb]
        have := ih (i + 1) (by omega)
        rw [show (i : Int) + 1 = ((i + 1 : Nat) : Int) by push_cast; rfl]
        rw [this]
        cases hs : scan d s.buffer e (i + 1) with
        | some j => rfl
        | none =>
          rw [show max ((i + 1 : Nat) : Int) ((e : Int) - (d.length : Int) + 1) =
            max (i : Int) ((e : Int) - (d.length : Int) + 1) by omega]
    · have hle' : ¬ ((i : Int) ≤ (e : Int) - (d.length : Int)) := by omega
      rw [if_neg hle]
      simp only [hle', if_false, pure, Except.pure]
      rw [show max (i : Int) ((e : Int) - (d.length : Int) + 1) = (i : Int) by omega]

/-- What `indexOfDelimiter` returns, from the model's `Option Nat × State`. -/
def idx (r : Option Nat × EM) : EE × Int :=
  (embed r.2, match r.1 with
    | some i => (i : Int)
    | none => -1)

theorem indexOfDelimiter_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (m : EM) (from_ : Nat)
    (hf : m.buffer.length + d.length + 2 ≤ fuel) :
    EngineFramerExtracted.indexOfDelimiter d mfl fuel (embed m) (from_ : Int) =
      .ok (idx (EngineFramerModel.indexOfDelimiter d m from_)) := by
  have hi : max (from_ : Int) (m.scanFrom : Int) = ((max from_ m.scanFrom : Nat) : Int) := by
    omega
  have h1 := loop1_eq d mfl fuel (by omega) (embed m) m.buffer.length fuel (max from_ m.scanFrom)
    (by omega)
  simp only [embed] at h1 ⊢
  unfold EngineFramerExtracted.indexOfDelimiter EngineFramerModel.indexOfDelimiter
  simp only [hi, h1, bind, Except.bind, pure, Except.pure]
  cases hs : scan d m.buffer m.buffer.length (max from_ m.scanFrom) with
  | some j => simp [idx, embed]
  | none =>
    simp only [idx, embed]
    rw [show max (0 : Int) ((m.buffer.length : Int) - (d.length : Int) + 1) =
      ((m.buffer.length + 1 - d.length : Nat) : Int) by omega]

/-! ## The frame loop of `onInput` -/

def loopResult (r : EM × Nat × List (List UInt8)) : EE × List (List UInt8) × Int × Int :=
  (embed r.1, r.2.2, (r.2.1 : Int), -1)

theorem onInput_loop_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (fuel : Nat) :
    ∀ (steps : Nat) (m : EM) (start : Nat) (frames : List (List UInt8)),
      m.buffer.length - start < steps → m.buffer.length + d.length + 2 ≤ fuel →
      EngineFramerExtracted.onInput_loop1 d (max : Int) fuel steps
          (idx (EngineFramerModel.indexOfDelimiter d m start)).1 frames (start : Int)
          (idx (EngineFramerModel.indexOfDelimiter d m start)).2 =
        lift loopResult (EngineFramerModel.frameLoop d hd max m start frames) := by
  intro steps
  induction steps with
  | zero => intro m start frames h; omega
  | succ n ih =>
    intro m start frames hs hf
    rw [EngineFramerModel.frameLoop]
    have hlen : 0 < d.length := List.length_pos_iff.mpr hd
    split
    · rename_i s' heq
      rw [heq]
      rw [EngineFramerExtracted.onInput_loop1]
      simp [idx, lift, loopResult]
      rfl
    · rename_i i s' heq
      obtain ⟨rfl, h1, h2⟩ := EngineFramerModel.indexOfDelimiter_some heq
      rw [heq]
      rw [EngineFramerExtracted.onInput_loop1]
      simp only [idx, embed]
      rw [if_pos (by omega)]
      by_cases hB : i - start > max
      · have hB' : (i : Int) - (start : Int) > (max : Int) := by omega
        rw [if_pos hB', if_pos hB]
        rfl
      · have hB' : ¬ ((i : Int) - (start : Int) > (max : Int)) := by omega
        rw [if_neg hB', if_neg hB]
        have hidx := indexOfDelimiter_eq d (max : Int) fuel s' (i + d.length) (by omega)
        simp only [pure, Except.pure, bind, Except.bind]
        have hst : (i : Int) + (d.length : Int) = ((i + d.length : Nat) : Int) := by omega
        rw [hst, embed] at *
        simp only [Int.toNat_natCast]
        rw [hidx]
        dsimp only
        exact ih s' (i + d.length) (frames ++ [copyOfRange s'.buffer start i])
          (by omega) (by omega)

/-! ## `onInput` and the stage -/

theorem onInput_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (fuel : Nat) (m : EM)
    (c : List UInt8) (hf : m.buffer.length + c.length + d.length + 2 ≤ fuel) :
    EngineFramerExtracted.onInput d (max : Int) fuel (embed m) c =
      lift (fun r => (embed r.1, r.2)) (EngineFramerModel.onInput d hd max m c) := by
  have hidx : EngineFramerExtracted.indexOfDelimiter d (max : Int) fuel
      (embed (⟨m.buffer ++ c, m.scanFrom⟩ : EM)) (0 : Int) =
      .ok (idx (EngineFramerModel.indexOfDelimiter d (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0)) :=
    indexOfDelimiter_eq d (max : Int) fuel (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0
      (by simp only [List.length_append]; omega)
  have hloop : EngineFramerExtracted.onInput_loop1 d (max : Int) fuel fuel
      (idx (EngineFramerModel.indexOfDelimiter d (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0)).1 []
      (0 : Int)
      (idx (EngineFramerModel.indexOfDelimiter d (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0)).2 =
      lift loopResult (EngineFramerModel.frameLoop d hd max (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0 []) :=
    onInput_loop_eq d hd max fuel fuel (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0 []
      (by simp only [List.length_append]; omega) (by simp only [List.length_append]; omega)
  unfold EngineFramerExtracted.onInput EngineFramerModel.onInput
  simp only [embed] at hidx hloop ⊢
  rw [hidx]
  simp only [bind, Except.bind]
  rw [hloop]
  cases hF : EngineFramerModel.frameLoop d hd max (⟨m.buffer ++ c, m.scanFrom⟩ : EM) 0 [] with
  | error e => simp [lift]
  | ok v =>
    obtain ⟨s2, start, fr⟩ := v
    simp only [lift, loopResult, embed, pure, Except.pure]
    have hlen : (copyOfRange s2.buffer start s2.buffer.length).length =
        s2.buffer.length - start := length_copyOfRange (Nat.le_refl _)
    by_cases hs : start > 0
    · have hs' : (start : Int) > 0 := by omega
      simp only [hs, hs', if_true, Int.toNat_natCast, hlen]
      by_cases hB : s2.buffer.length - start > max
      · have hB' : ((s2.buffer.length - start : Nat) : Int) > (max : Int) := by omega
        rw [if_pos hB', if_pos hB]
        rfl
      · have hB' : ¬ ((s2.buffer.length - start : Nat) : Int) > (max : Int) := by omega
        rw [if_neg hB', if_neg hB]
        have hsf : Max.max (0 : Int) ((s2.scanFrom : Int) - (start : Int)) =
            ((s2.scanFrom - start : Nat) : Int) := by omega
        rw [hsf]
    · have hs' : ¬ (start : Int) > 0 := by omega
      simp only [hs, hs', if_false]
      by_cases hB : s2.buffer.length > max
      · have hB' : (s2.buffer.length : Int) > (max : Int) := by omega
        rw [if_pos hB', if_pos hB]
        rfl
      · have hB' : ¬ (s2.buffer.length : Int) > (max : Int) := by omega
        rw [if_neg hB', if_neg hB]

theorem engineEnd_toExt (e : EngineFramerModel.Failure) :
    ExtractedDrivers.engineEnd (toExt e) = .tooLong := by
  cases e <;> rfl

/-- **The extracted transformer is the model's**, from every state. -/
theorem engineRunFrom_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) :
    ∀ (cs : List (List UInt8)) (m : EM),
      ExtractedDrivers.engineRunFrom d (max : Int) (embed m) cs =
        embedResult (EngineFramerModel.runFrom d hd max m cs)
  | [], m => by
    unfold ExtractedDrivers.engineRunFrom EngineFramerModel.runFrom
    simp only [EngineFramerExtracted.onUpstreamComplete, EngineFramerModel.onUpstreamComplete,
      embed, embedResult, embedEnd]
    by_cases h : m.buffer.isEmpty
    · simp [h, pure, Except.pure]
    · simp [h, pure, Except.pure]
  | c :: cs, m => by
    have hpush := onInput_eq d hd max
      (ExtractedDrivers.fuelFor d.length m.buffer.length c.length) m c
      (by unfold ExtractedDrivers.fuelFor; omega)
    unfold ExtractedDrivers.engineRunFrom EngineFramerModel.runFrom
    rw [show (embed m).buffer.length = m.buffer.length from rfl, hpush]
    cases hP : EngineFramerModel.onInput d hd max m c with
    | error e => simp [lift, embedResult, engineEnd_toExt, embedEnd]
    | ok r =>
      obtain ⟨s', fr⟩ := r
      simp only [lift]
      rw [engineRunFrom_eq d hd max cs s']
      rfl

theorem engineRun_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (cs : List (List UInt8)) :
    ExtractedDrivers.engineRun d (max : Int) cs =
      embedResult (EngineFramerModel.run d hd max cs) := by
  unfold ExtractedDrivers.engineRun EngineFramerModel.run
  exact engineRunFrom_eq d hd max cs EngineFramerModel.init

end WsClientProofs.Framing.EngineFramerRefinement
