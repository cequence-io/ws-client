import WsClientProofs.Framing.ExtractedDrivers
import WsClientProofs.Framing.ByteFramingProof

/-!
# The extracted `ByteFraming.DelimiterFramer` is the model the proofs are about

`ByteFramingExtracted` is the shipped Scala translated mechanically (`sbt leanExtract`): `Int`
indices, a loop is a recursive def over a step counter (`fuel`). `ByteFramingModel` is the
hand-written model `ByteFramingProof` reasons about: `Nat` indices, the loops as well-founded
recursion. This file proves them equal - function by function, for every state satisfying the
invariant and every fuel above the bound the drivers give - so that every theorem about the
model (`framers_equivalent`, `byteFraming_*`) is a theorem about the shipped code.
-/

namespace WsClientProofs.Framing.ByteFramingRefinement

open WsClientProofs.Java WsClientProofs.Framing
open WsClientProofs.Framing.ExtractedDrivers (embedEnd embedResult)

abbrev E := WsClientProofs.Framing.ByteFramingExtracted.State
abbrev M := WsClientProofs.Framing.ByteFramingModel.State UInt8

/-- The extracted state of a model state (`Nat` fields as `Int`). -/
def embed (m : M) : E :=
  { buffer := m.buffer, start := m.start, «end» := m.end_, scanFrom := m.scanFrom }

/-- The failures of the model as the exceptions the code throws. -/
def toExt : ByteFramingModel.Failure → ByteFramingExtracted.Failure
  | .frameTooLong => .CequenceWSFrameTooLongException
  | .restTooLong => .CequenceWSFrameTooLongException
  | .bufferTooLarge => .CequenceWSFrameTooLongException
  | .truncated => .CequenceWSException

/-- A model result as the extracted code's (`Except` over the other failure type). -/
def lift {α β : Type} (f : α → β) :
    Except ByteFramingModel.Failure α → Except ByteFramingExtracted.Failure β
  | .ok a => .ok (f a)
  | .error e => .error (toExt e)

theorem arrayGet_natCast {α : Type} (a : List α) (n : Nat) :
    Scala.arrayGet a (n : Int) = a[n]? := by
  unfold Scala.arrayGet
  rw [if_neg (by omega)]
  simp

/-! ## The search loops -/

/-- The inner loop of `indexOfDelimiter` is `matchLength`. -/
theorem loop2_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (i : Nat) :
    ∀ (steps j : Nat), d.length - j < steps →
      ByteFramingExtracted.indexOfDelimiter_loop2 d mfl fuel steps s (i : Int) (j : Int) =
        .ok ((matchLength d s.buffer i j : Nat) : Int) := by
  intro steps
  induction steps with
  | zero => intro j h; omega
  | succ n ih =>
    intro j hj
    rw [ByteFramingExtracted.indexOfDelimiter_loop2, matchLength]
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
theorem loop1_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (hf : d.length < fuel) (s : E)
    (e : Nat) :
    ∀ (steps i : Nat), e + 1 - i < steps →
      ByteFramingExtracted.indexOfDelimiter_loop1 d mfl fuel steps s (i : Int)
          ((e : Int) - (d.length : Int)) =
        match scan d s.buffer e i with
        | some j => .ok (.inl (s, (j : Int)))
        | none => .ok (.inr (max (i : Int) ((e : Int) - (d.length : Int) + 1))) := by
  intro steps
  induction steps with
  | zero => intro i h; omega
  | succ n ih =>
    intro i hi
    rw [ByteFramingExtracted.indexOfDelimiter_loop1, scan]
    by_cases hle : i + d.length ≤ e
    · have hle' : (i : Int) ≤ (e : Int) - (d.length : Int) := by omega
      rw [if_pos hle]
      simp only [hle', if_true]
      have hm : ByteFramingExtracted.indexOfDelimiter_loop2 d mfl fuel fuel s (i : Int) (0 : Int) =
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

/-- What `indexOfDelimiter` returns, from the model's `Option Nat × State`: the index (-1: none)
and the state. -/
def idx (r : Option Nat × M) : E × Int :=
  (embed r.2, match r.1 with
    | some i => (i : Int)
    | none => -1)

/-- `indexOfDelimiter` of the extracted code is the model's, for any fuel above the buffered
bytes and the delimiter. -/
theorem indexOfDelimiter_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (m : M)
    (hf : m.end_ + d.length + 2 ≤ fuel) :
    ByteFramingExtracted.indexOfDelimiter d mfl fuel (embed m) =
      .ok (idx (ByteFramingModel.indexOfDelimiter d m)) := by
  have hi : max (m.start : Int) (m.scanFrom : Int) = ((max m.start m.scanFrom : Nat) : Int) := by
    omega
  have h1 := loop1_eq d mfl fuel (by omega) (embed m) m.end_ fuel (max m.start m.scanFrom)
    (by omega)
  simp only [embed] at h1 ⊢
  unfold ByteFramingExtracted.indexOfDelimiter ByteFramingModel.indexOfDelimiter
  simp only [hi, h1, bind, Except.bind, pure, Except.pure]
  cases hs : scan d m.buffer m.end_ (max m.start m.scanFrom) with
  | some j => simp [idx, embed]
  | none =>
    simp only [idx, embed]
    rw [show max (m.start : Int) ((m.end_ : Int) - (d.length : Int) + 1) =
      ((max m.start (m.end_ + 1 - d.length) : Nat) : Int) by omega]

/-! ## `append` -/

theorem append_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (m : M) (c : List UInt8)
    (hinv : ByteFramingModel.Inv d m) :
    ByteFramingExtracted.append d mfl fuel (embed m) c =
      lift embed (ByteFramingModel.append m c) := by
  have h1 := hinv.start_le
  have h2 := hinv.scan_le
  unfold ByteFramingExtracted.append ByteFramingModel.append
  simp only [embed, ByteFramingModel.compact, ByteFramingModel.grow, lift, bind, Except.bind,
    pure, Except.pure, Java.intMax, Int.toNat_natCast]
  by_cases hl : c.length > 0
  · have hl' : (c.length : Int) > 0 := by omega
    simp only [hl, hl', if_true]
    by_cases hc : m.start > 0 ∧ m.end_ + c.length > m.buffer.length
    · have hc1 : (decide ((m.start : Int) > 0) &&
          decide ((m.end_ : Int) + (c.length : Int) > (m.buffer.length : Int))) = true := by
        simp only [Bool.and_eq_true, decide_eq_true_eq]
        omega
      rw [if_pos hc1, if_pos hc]
      have hA : ((m.end_ : Int) - (m.start : Int)).toNat = m.end_ - m.start := by omega
      have hS : (m.end_ : Int) - (m.start : Int) + (c.length : Int) =
          ((m.end_ - m.start + c.length : Nat) : Int) := by omega
      have hG : ∀ L : Nat, (max (((m.end_ - m.start + c.length : Nat)) : Int)
          (min ((L : Int) * 2) (2147483647 - 8))).toNat =
          max (m.end_ - m.start + c.length) (min (L * 2) (2147483647 - 8)) := by
        intro L; omega
      simp only [hA, Int.toNat_zero, hS, hG]
      generalize arraycopy m.buffer m.start m.buffer 0 (m.end_ - m.start) = A
      have hsf : (m.scanFrom : Int) - (m.start : Int) = ((m.scanFrom - m.start : Nat) : Int) := by
        omega
      by_cases g1 : m.end_ - m.start + c.length > A.length
      · have g1' : ((m.end_ - m.start + c.length : Nat) : Int) > (A.length : Int) := by omega
        rw [if_pos g1', if_pos g1]
        by_cases g2 : m.end_ - m.start + c.length > 2147483647 - 8
        · have g2' : ((m.end_ - m.start + c.length : Nat) : Int) > 2147483647 - 8 := by omega
          rw [if_pos g2', if_pos g2]
          rfl
        · have g2' : ¬ ((m.end_ - m.start + c.length : Nat) : Int) > 2147483647 - 8 := by omega
          rw [if_neg g2', if_neg g2]
          simp only [Except.map, hsf]
          rfl
      · have g1' : ¬ ((m.end_ - m.start + c.length : Nat) : Int) > (A.length : Int) := by omega
        rw [if_neg g1', if_neg g1]
        simp only [Except.map, hsf]
        rfl
    · have hc1 : ¬ (decide ((m.start : Int) > 0) &&
          decide ((m.end_ : Int) + (c.length : Int) > (m.buffer.length : Int))) = true := by
        simp only [Bool.and_eq_true, decide_eq_true_eq]
        omega
      rw [if_neg hc1, if_neg hc]
      have hS : (m.end_ : Int) + (c.length : Int) = ((m.end_ + c.length : Nat) : Int) := by omega
      have hG : ∀ L : Nat, (max (((m.end_ + c.length : Nat)) : Int)
          (min ((L : Int) * 2) (2147483647 - 8))).toNat =
          max (m.end_ + c.length) (min (L * 2) (2147483647 - 8)) := by
        intro L; omega
      simp only [hS, hG, Int.toNat_zero]
      by_cases g1 : m.end_ + c.length > m.buffer.length
      · have g1' : ((m.end_ + c.length : Nat) : Int) > (m.buffer.length : Int) := by omega
        rw [if_pos g1', if_pos g1]
        by_cases g2 : m.end_ + c.length > 2147483647 - 8
        · have g2' : ((m.end_ + c.length : Nat) : Int) > 2147483647 - 8 := by omega
          rw [if_pos g2', if_pos g2]
          rfl
        · have g2' : ¬ ((m.end_ + c.length : Nat) : Int) > 2147483647 - 8 := by omega
          rw [if_neg g2', if_neg g2]
          simp only [Except.map]
      · have g1' : ¬ ((m.end_ + c.length : Nat) : Int) > (m.buffer.length : Int) := by omega
        rw [if_neg g1', if_neg g1]
        simp only [Except.map]
  · have hl' : ¬ (c.length : Int) > 0 := by omega
    simp only [hl, hl', if_false]

/-! ## The frame loop of `push` -/

/-- A model frame loop's result as the extracted loop's: the state, the frames, and the
`delimiterAt` the loop ends with (-1: the search found nothing). -/
def loopResult (r : M × List (List UInt8)) : E × List (List UInt8) × Int :=
  (embed r.1, r.2, -1)

theorem push_loop_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (fuel : Nat) :
    ∀ (steps : Nat) (m : M) (frames : List (List UInt8)),
      m.end_ - m.start < steps → m.end_ + d.length + 2 ≤ fuel →
      ByteFramingExtracted.push_loop1 d (max : Int) fuel steps
          (idx (ByteFramingModel.indexOfDelimiter d m)).1 frames
          (idx (ByteFramingModel.indexOfDelimiter d m)).2 =
        lift loopResult (ByteFramingModel.frameLoop d hd max m frames) := by
  intro steps
  induction steps with
  | zero => intro m frames h; omega
  | succ n ih =>
    intro m frames hs hf
    rw [ByteFramingModel.frameLoop]
    have hlen : 0 < d.length := List.length_pos_iff.mpr hd
    split
    · rename_i s' heq
      rw [heq]
      rw [ByteFramingExtracted.push_loop1]
      simp [idx, lift, loopResult]
      rfl
    · rename_i i s' heq
      obtain ⟨rfl, h1, h2⟩ := ByteFramingModel.indexOfDelimiter_some heq
      rw [heq]
      rw [ByteFramingExtracted.push_loop1]
      simp only [idx, embed]
      rw [if_pos (by omega)]
      by_cases hB : i - s'.start > max
      · have hB' : (i : Int) - (s'.start : Int) > (max : Int) := by omega
        rw [if_pos hB', if_pos hB]
        rfl
      · have hB' : ¬ ((i : Int) - (s'.start : Int) > (max : Int)) := by omega
        rw [if_neg hB', if_neg hB]
        have he : embed (⟨s'.buffer, i + d.length, s'.end_, i + d.length⟩ : M) =
            (⟨s'.buffer, (i : Int) + d.length, s'.end_, (i : Int) + d.length⟩ : E) := by
          simp [embed]
        have hidx := indexOfDelimiter_eq d (max : Int) fuel
          (⟨s'.buffer, i + d.length, s'.end_, i + d.length⟩ : M) (by simp only []; omega)
        rw [he] at hidx
        simp only [pure, Except.pure, bind, Except.bind]
        rw [hidx]
        simp only [Int.toNat_natCast]
        exact ih (⟨s'.buffer, i + d.length, s'.end_, i + d.length⟩ : M)
          (copyOfRange s'.buffer s'.start i :: frames)
          (by simp only []; omega) (by simp only []; omega)

/-! ## `push` -/

/-- One chunk: the extracted `push` is the model's, for any fuel above the buffered bytes, the
chunk and the delimiter. -/
theorem push_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (fuel : Nat) (m : M)
    (c : List UInt8) (hinv : ByteFramingModel.Inv d m) (hbound : m.end_ + c.length ≤ intMax - 8)
    (hf : m.buffer.length + c.length + d.length + 2 ≤ fuel) :
    ByteFramingExtracted.push d (max : Int) fuel (embed m) c =
      lift (fun r => (embed r.1, r.2)) (ByteFramingModel.push d hd max m c) := by
  obtain ⟨s1, ha, hinv1, _, he1⟩ := ByteFramingModel.append_spec hinv hbound
  have hend := hinv.end_le
  have hs1 := hinv1.end_le
  have hlen : s1.end_ ≤ m.buffer.length + c.length := by omega
  have hidx := indexOfDelimiter_eq d (max : Int) fuel s1 (by omega)
  have hloop := push_loop_eq d hd max fuel fuel s1 [] (by omega) (by omega)
  unfold ByteFramingExtracted.push ByteFramingModel.push
  dsimp only
  rw [append_eq d (max : Int) fuel m c hinv, ha]
  simp only [lift, bind, Except.bind, pure, Except.pure]
  rw [hidx]
  dsimp only
  rw [hloop]
  cases hF : ByteFramingModel.frameLoop d hd max s1 [] with
  | error e => simp [lift]
  | ok v =>
    obtain ⟨s2, fr⟩ := v
    simp only [lift, loopResult, embed]
    by_cases hB : s2.end_ - s2.start > max
    · have hB' : (s2.end_ : Int) - (s2.start : Int) > (max : Int) := by omega
      rw [if_pos hB', if_pos hB]
      rfl
    · have hB' : ¬ ((s2.end_ : Int) - (s2.start : Int) > (max : Int)) := by omega
      rw [if_neg hB', if_neg hB]

/-! ## `finish` and the stage -/

theorem finish_eq (d : List UInt8) (mfl : Int) (fuel : Nat) (t : Bool) (m : M) :
    ByteFramingExtracted.finish d mfl fuel (embed m) t =
      lift id (ByteFramingModel.finish t m) := by
  unfold ByteFramingExtracted.finish ByteFramingModel.finish
  simp only [embed, Int.toNat_natCast, pure, Except.pure]
  by_cases he : m.end_ = m.start
  · have he' : ((m.end_ : Int) == (m.start : Int)) = true := by simp [he]
    rw [if_pos he', if_pos he]
    rfl
  · have he' : ¬ ((m.end_ : Int) == (m.start : Int)) = true :=
      fun h => he (by exact_mod_cast beq_iff_eq.mp h)
    rw [if_neg he', if_neg he]
    cases t <;> rfl

theorem byteEnd_toExt (e : ByteFramingModel.Failure) :
    ExtractedDrivers.byteEnd (toExt e) = embedEnd e.ending := by
  cases e <;> rfl

/-- A successful `push` leaves a state satisfying the invariant, no further than the chunk. -/
theorem push_ok_inv (d : List UInt8) (hd : d ≠ []) (max : Nat) (m : M) (c : List UInt8)
    (hinv : ByteFramingModel.Inv d m) (hbound : m.end_ + c.length ≤ intMax - 8) {s' : M}
    {fr : List (List UInt8)} (h : ByteFramingModel.push d hd max m c = .ok (s', fr)) :
    ByteFramingModel.Inv d s' ∧ s'.end_ ≤ m.end_ + c.length := by
  have hp := ByteFramingModel.push_spec hd (max := max) hinv hbound
  by_cases hc : (∀ f ∈ (splitOn d (ByteFramingModel.pending m ++ c)).1, f.length ≤ max) ∧
      (splitOn d (ByteFramingModel.pending m ++ c)).2.length ≤ max
  · rw [if_pos hc] at hp
    obtain ⟨s'', hp', hinv', _, he⟩ := hp
    rw [h] at hp'
    have : s'' = s' := by
      have := Except.ok.inj hp'
      exact (Prod.mk.inj this).1.symm
    subst this
    exact ⟨hinv', he⟩
  · rw [if_neg hc] at hp
    obtain ⟨e, hp', _⟩ := hp
    rw [h] at hp'
    cases hp'

/-- **The extracted stage is the model's**, from any state satisfying the invariant. -/
theorem byteRunFrom_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (t : Bool) :
    ∀ (cs : List (List UInt8)) (m : M), ByteFramingModel.Inv d m →
      m.end_ + cs.flatten.length ≤ intMax - 8 →
      ExtractedDrivers.byteRunFrom d (max : Int) t (embed m) cs =
        embedResult (ByteFramingModel.runFrom d hd max t m cs)
  | [], m, _, _ => by
    unfold ExtractedDrivers.byteRunFrom ByteFramingModel.runFrom
    rw [finish_eq]
    cases hF : ByteFramingModel.finish t m with
    | ok fs => rfl
    | error e => simp [lift, embedResult, byteEnd_toExt]
  | c :: cs, m, hinv, hbound => by
    simp only [List.flatten_cons, List.length_append] at hbound
    have hend := hinv.end_le
    have hpush := push_eq d hd max
      (ExtractedDrivers.fuelFor d.length m.buffer.length c.length) m c hinv (by omega)
      (by unfold ExtractedDrivers.fuelFor; omega)
    unfold ExtractedDrivers.byteRunFrom ByteFramingModel.runFrom
    rw [show (embed m).buffer.length = m.buffer.length from rfl, hpush]
    cases hP : ByteFramingModel.push d hd max m c with
    | error e => simp [lift, embedResult, byteEnd_toExt]
    | ok r =>
      obtain ⟨s', fr⟩ := r
      obtain ⟨hinv', he⟩ := push_ok_inv d hd max m c hinv (by omega) hP
      simp only [lift]
      rw [byteRunFrom_eq d hd max t cs s' hinv' (by omega)]
      rfl

theorem byteRun_eq (d : List UInt8) (hd : d ≠ []) (max : Nat) (t : Bool)
    (cs : List (List UInt8)) (hbound : cs.flatten.length ≤ intMax - 8) :
    ExtractedDrivers.byteRun d (max : Int) t cs =
      embedResult (ByteFramingModel.run d hd max t cs) := by
  have hinv : ByteFramingModel.Inv d (ByteFramingModel.init : M) := ByteFramingModel.inv_init d
  unfold ExtractedDrivers.byteRun ByteFramingModel.run
  have := byteRunFrom_eq d hd max t cs ByteFramingModel.init hinv (by
    show 0 + cs.flatten.length ≤ intMax - 8; omega)
  have hi : ByteFramingExtracted.init d (max : Int) = embed (ByteFramingModel.init : M) := by
    rfl
  rw [hi]
  exact this

end WsClientProofs.Framing.ByteFramingRefinement
