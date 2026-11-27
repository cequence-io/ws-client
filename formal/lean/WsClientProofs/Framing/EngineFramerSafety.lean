import WsClientProofs.Framing.EngineFramerChecked
import WsClientProofs.Framing.EngineFramerExtracted
import WsClientProofs.Framing.EngineFramerRefinement

/-!
# The engines' `DelimiterFramer` never overflows an `int`, reads out of bounds or throws a
# Java array exception

As `ByteFramingSafety`: `EngineFramerChecked` is the same Scala with every Java failure a step of
`Except` (an `int` / `long` operation that would wrap, an array access out of bounds, a
`copyOfRange` Java would reject, an array concatenation whose size wraps), proved equal to
`EngineFramerExtracted` on every state whose buffer is within `int` range and every chunk that
fits.
-/

namespace WsClientProofs.Framing.EngineFramerSafety

open WsClientProofs.Java

abbrev E := EngineFramerExtracted.State
abbrev C := EngineFramerChecked.State

def toC (s : E) : C := ⟨s.buffer, s.scanFrom⟩

def castF : EngineFramerExtracted.Failure → EngineFramerChecked.Failure
  | .CequenceWSFrameTooLongException => .CequenceWSFrameTooLongException
  | .outOfFuel => .outOfFuel

def lift {α β : Type} (f : α → β) (r : Except EngineFramerExtracted.Failure α) :
    Except EngineFramerChecked.Failure β :=
  match r with
  | .ok a => .ok (f a)
  | .error e => .error (castF e)

/-! ## The prelude operations in range -/

theorem i32_ok {x : Int} (h1 : -2147483648 ≤ x) (h2 : x ≤ 2147483647) :
    EngineFramerChecked.i32 x = .ok x := by
  unfold EngineFramerChecked.i32 Scala.int32?
  rw [if_pos ⟨h1, h2⟩]


theorem i64_ok {x : Int} (h1 : -9223372036854775808 ≤ x) (h2 : x ≤ 9223372036854775807) :
    EngineFramerChecked.i64 x = .ok x := by
  unfold EngineFramerChecked.i64 Scala.int64?
  rw [if_pos ⟨h1, h2⟩]


theorem readArr_ok {a : List UInt8} {i : Int} (h1 : 0 ≤ i) (h2 : i < a.length) :
    EngineFramerChecked.readArr a i = .ok (a.getD i.toNat default) := by
  unfold EngineFramerChecked.readArr Java.readChecked EngineFramerChecked.thrown
  rw [if_pos ⟨h1, h2⟩]


theorem arrayGet_of_lt {a : List UInt8} {i : Int} (h1 : 0 ≤ i) (h2 : i < a.length) :
    Scala.arrayGet a i = some (a.getD i.toNat default) := by
  unfold Scala.arrayGet
  rw [if_neg (by omega)]
  have : i.toNat < a.length := by omega
  simp [List.getElem?_eq_getElem this, List.getD_eq_getElem?_getD]


theorem loop2_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (i : Int) (hi : 0 ≤ i)
    (hroom : i + d.length ≤ s.buffer.length) (hL : (s.buffer.length : Int) ≤ 2147483639) :
    ∀ (steps : Nat) (j : Int), 0 ≤ j → j ≤ d.length →
      EngineFramerChecked.indexOfDelimiter_loop2 d mfl fuel steps (toC s) i j =
        lift id (EngineFramerExtracted.indexOfDelimiter_loop2 d mfl fuel steps s i j) := by
  intro steps
  induction steps with
  | zero => intro j _ _; rfl
  | succ n ih =>
    intro j hj0 hjd
    rw [EngineFramerChecked.indexOfDelimiter_loop2, EngineFramerExtracted.indexOfDelimiter_loop2]
    have hb : (toC s).buffer = s.buffer := rfl
    simp only [hb]
    by_cases hlt : j < (d.length : Int)
    · have h1 : EngineFramerChecked.i32 (i + j) = .ok (i + j) := i32_ok (by omega) (by omega)
      have h2 := readArr_ok (a := s.buffer) (i := i + j) (by omega) (by omega)
      have h3 := readArr_ok (a := d) (i := j) (by omega) (by omega)
      have e1 := arrayGet_of_lt (a := s.buffer) (i := i + j) (by omega) (by omega)
      have e2 := arrayGet_of_lt (a := d) (i := j) (by omega) (by omega)
      simp only [hlt, if_true, decide_true, Bool.true_and, h1, h2, h3, e1, e2, bind, Except.bind,
        pure, Except.pure, beq_iff_eq]
      by_cases hab : s.buffer.getD (i + j).toNat default = d.getD j.toNat default
      · have h4 : EngineFramerChecked.i32 (j + 1) = .ok (j + 1) := i32_ok (by omega) (by omega)
        simp only [hab, if_true, h4]
        exact ih (j + 1) (by omega) (by omega)
      · simp only [hab, Option.some.injEq, if_false]
        rfl
    · simp [hlt, lift, pure, Except.pure, bind, Except.bind]


def liftSum (r : Sum (E × Int) Int) : Sum (C × Int) Int :=
  r.map (fun p => (toC p.1, p.2)) id


theorem loop1_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (e : Int)
    (he0 : 0 ≤ e) (hend : e ≤ s.buffer.length) (hL : (s.buffer.length : Int) ≤ 2147483639)
    (hd : 0 < d.length) :
    ∀ (steps : Nat) (i : Int), 0 ≤ i →
      EngineFramerChecked.indexOfDelimiter_loop1 d mfl fuel steps (toC s) i (e - d.length) =
        lift liftSum
          (EngineFramerExtracted.indexOfDelimiter_loop1 d mfl fuel steps s i (e - d.length)) := by
  intro steps
  induction steps with
  | zero => intro i _; rfl
  | succ n ih =>
    intro i hi
    rw [EngineFramerChecked.indexOfDelimiter_loop1, EngineFramerExtracted.indexOfDelimiter_loop1]
    by_cases hle : i ≤ e - d.length
    · simp only [hle, if_true]
      have hl2 := loop2_safe d mfl fuel s i hi (by omega) hL fuel 0 (by omega) (by omega)
      rw [hl2]
      cases hE : EngineFramerExtracted.indexOfDelimiter_loop2 d mfl fuel fuel s i 0 with
      | error err => simp [lift, bind, Except.bind]
      | ok r =>
        by_cases hr : (r == (d.length : Int)) = true
        · simp [lift, bind, Except.bind, hr, pure, Except.pure, liftSum]
        · have hi32 : EngineFramerChecked.i32 (i + 1) = .ok (i + 1) :=
            i32_ok (by omega) (by omega)
          simp only [lift, bind, Except.bind, id_eq, hr, if_false, pure, Except.pure, hi32,
            Bool.false_eq_true]
          exact ih (i + 1) (by omega)
    · simp [hle, lift, liftSum, pure, Except.pure]


theorem copyOfRangeC_ok {a : List UInt8} {f t : Int} (h1 : 0 ≤ f) (h2 : f ≤ a.length)
    (h3 : f ≤ t) (h4 : t ≤ a.length) :
    EngineFramerChecked.copyOfRangeC a f t = .ok (Java.copyOfRange a f.toNat t.toNat) := by
  unfold EngineFramerChecked.copyOfRangeC Java.copyOfRangeChecked EngineFramerChecked.thrown
  rw [if_neg (by omega), if_neg (by omega)]
  have : (t - f).toNat - (a.length - f.toNat) = 0 := by omega
  simp [this, Java.copyOfRange]
  congr 1
  omega


theorem loop1_inl_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (limit : Int) :
    ∀ (steps : Nat) (i : Int) (s' : E) (r : Int),
      EngineFramerExtracted.indexOfDelimiter_loop1 d mfl fuel steps s i limit = .ok (.inl (s', r)) →
        i ≤ r ∧ r ≤ limit ∧ s' = s := by
  intro steps
  induction steps with
  | zero => intro i s' r h; simp [EngineFramerExtracted.indexOfDelimiter_loop1] at h
  | succ n ih =>
    intro i s' r h
    unfold EngineFramerExtracted.indexOfDelimiter_loop1 at h
    by_cases hle : i ≤ limit
    · simp only [hle, if_true, bind, Except.bind, pure, Except.pure] at h
      cases hj : EngineFramerExtracted.indexOfDelimiter_loop2 d mfl fuel fuel s i 0 with
      | error err => rw [hj] at h; simp at h
      | ok j =>
        rw [hj] at h
        by_cases hq : (j == (d.length : Int)) = true
        · simp only [hq, if_true] at h
          simp only [Except.ok.injEq, Sum.inl.injEq, Prod.mk.injEq] at h
          obtain ⟨rfl, rfl⟩ := h
          exact ⟨Int.le_refl _, hle, rfl⟩
        · simp only [hq, if_false, Bool.false_eq_true] at h
          obtain ⟨a, b, c⟩ := ih (i + 1) s' r h
          exact ⟨by omega, b, c⟩
    · simp [hle, pure, Except.pure] at h


/-! ## The search from an offset -/

structure ERanges (s : E) : Prop where
  scan0 : 0 ≤ s.scanFrom
  scanLe : s.scanFrom ≤ 2147483639
  bufferLe : (s.buffer.length : Int) ≤ 2147483639

def liftIdx (p : E × Int) : C × Int := (toC p.1, p.2)

theorem indexOfDelimiter_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (fr : Int)
    (hs : ERanges s) (hf : 0 ≤ fr) (hd : 0 < d.length) (hdM : (d.length : Int) ≤ 2147483639) :
    EngineFramerChecked.indexOfDelimiter d mfl fuel (toC s) fr =
      lift liftIdx (EngineFramerExtracted.indexOfDelimiter d mfl fuel s fr) := by
  obtain ⟨h1, h1', h4⟩ := hs
  have hl1 := loop1_safe d mfl fuel s s.buffer.length (by omega) (Int.le_refl _) h4 hd fuel
    (max fr s.scanFrom) (by omega)
  have hi32 : EngineFramerChecked.i32 ((s.buffer.length : Int) - d.length) =
      .ok ((s.buffer.length : Int) - d.length) := i32_ok (by omega) (by omega)
  have hi32' : EngineFramerChecked.i32 ((s.buffer.length : Int) - d.length + 1) =
      .ok ((s.buffer.length : Int) - d.length + 1) := i32_ok (by omega) (by omega)
  unfold EngineFramerChecked.indexOfDelimiter EngineFramerExtracted.indexOfDelimiter
  have hb : (toC s).buffer = s.buffer := rfl
  have hsc : (toC s).scanFrom = s.scanFrom := rfl
  simp only [hb, hsc, hi32, bind, Except.bind]
  rw [hl1]
  cases hE : EngineFramerExtracted.indexOfDelimiter_loop1 d mfl fuel fuel s (max fr s.scanFrom)
      (s.buffer.length - d.length) with
  | error err => simp [lift]
  | ok v =>
    cases v with
    | inl v => simp [lift, liftSum, liftIdx, pure, Except.pure, Sum.map]
    | inr i2 =>
      simp only [lift, liftSum, bind, Except.bind, pure, Except.pure, Sum.map, hi32']
      rfl

theorem idx_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s s' : E) (fr r : Int)
    (hs : ERanges s) (hf : 0 ≤ fr) (hd : 0 < d.length)
    (h : EngineFramerExtracted.indexOfDelimiter d mfl fuel s fr = .ok (s', r)) :
    s'.buffer = s.buffer ∧ ERanges s' ∧
      (r < 0 ∨ (fr ≤ r ∧ r + d.length ≤ s.buffer.length)) := by
  obtain ⟨h1, h1', h4⟩ := hs
  unfold EngineFramerExtracted.indexOfDelimiter at h
  simp only [bind, Except.bind, pure, Except.pure] at h
  cases hl : EngineFramerExtracted.indexOfDelimiter_loop1 d mfl fuel fuel s (max fr s.scanFrom)
      (s.buffer.length - d.length) with
  | error err => rw [hl] at h; simp at h
  | ok v =>
    rw [hl] at h
    cases v with
    | inl p =>
      obtain ⟨s2, r2⟩ := p
      simp only [Except.ok.injEq, Prod.mk.injEq] at h
      obtain ⟨rfl, rfl⟩ := h
      obtain ⟨a, b, c⟩ := loop1_inl_post d mfl fuel s _ fuel _ s2 r2 hl
      subst c
      exact ⟨rfl, ⟨h1, h1', h4⟩, Or.inr ⟨by omega, by omega⟩⟩
    | inr i2 =>
      simp only [Except.ok.injEq, Prod.mk.injEq] at h
      obtain ⟨rfl, rfl⟩ := h
      refine ⟨rfl, ⟨by dsimp only; omega, by dsimp only; omega, h4⟩, Or.inl (by omega)⟩

/-! ## The frame loop of `onInput` -/

def liftLoop (p : E × List (List UInt8) × Int × Int) : C × List (List UInt8) × Int × Int :=
  (toC p.1, p.2.1, p.2.2.1, p.2.2.2)

theorem onInput_loop_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) :
    ∀ (steps : Nat) (s : E) (frames : List (List UInt8)) (st δ : Int), ERanges s → 0 ≤ st →
      st ≤ s.buffer.length →
      (δ < 0 ∨ (st ≤ δ ∧ δ + d.length ≤ s.buffer.length)) →
      EngineFramerChecked.onInput_loop1 d mfl fuel steps (toC s) frames st δ =
        lift liftLoop (EngineFramerExtracted.onInput_loop1 d mfl fuel steps s frames st δ) := by
  intro steps
  induction steps with
  | zero => intro s frames st δ _ _ _ _; rfl
  | succ n ih =>
    intro s frames st δ hs hst0 hstL hδ
    have hs' := hs
    obtain ⟨h1, h1', h4⟩ := hs
    rw [EngineFramerChecked.onInput_loop1, EngineFramerExtracted.onInput_loop1]
    by_cases hneg : δ ≥ 0
    · have hδ' : st ≤ δ ∧ δ + d.length ≤ s.buffer.length := by
        rcases hδ with h | h
        · omega
        · exact h
      have e1 : EngineFramerChecked.i32 (δ - st) = .ok (δ - st) := i32_ok (by omega) (by omega)
      have e4 : EngineFramerChecked.i32 (δ + d.length) = .ok (δ + d.length) :=
        i32_ok (by omega) (by omega)
      have e2 := copyOfRangeC_ok (a := s.buffer) (f := st) (t := δ) hst0 (by omega) (by omega)
        (by omega)
      have hb : (toC s).buffer = s.buffer := rfl
      simp only [hneg, if_true, hb, e1, e2, e4, bind, Except.bind, pure, Except.pure]
      by_cases hf : δ - st > mfl
      · simp only [hf, if_true, lift]
        rfl
      · simp only [hf, if_false]
        have hidx := indexOfDelimiter_safe d mfl fuel s (δ + d.length) hs' (by omega) hd hdM
        have hrw : toC s = toC s := rfl
        rw [hidx]
        cases hE : EngineFramerExtracted.indexOfDelimiter d mfl fuel s (δ + d.length) with
        | error err => simp [lift]
        | ok v =>
          obtain ⟨s3, r4⟩ := v
          obtain ⟨p1, p2, p3⟩ := idx_post d mfl fuel s s3 (δ + d.length) r4 hs' (by omega) hd hE
          simp only [lift, liftIdx, bind, Except.bind]
          refine ih s3 _ _ r4 p2 (by omega) (by rw [p1]; omega) ?_
          rcases p3 with h | h
          · exact Or.inl h
          · refine Or.inr ⟨h.1, ?_⟩
            rw [p1]; exact h.2
    · simp [hneg, lift, liftLoop, pure, Except.pure]

theorem onInput_loop_post (d : List UInt8) (mfl : Int) (fuel : Nat) (hd : 0 < d.length) :
    ∀ (steps : Nat) (s s' : E) (frames fr : List (List UInt8)) (st st' δ δ' : Int), ERanges s →
      0 ≤ st → st ≤ s.buffer.length →
      (δ < 0 ∨ (st ≤ δ ∧ δ + d.length ≤ s.buffer.length)) →
      EngineFramerExtracted.onInput_loop1 d mfl fuel steps s frames st δ = .ok (s', fr, st', δ') →
        ERanges s' ∧ s'.buffer = s.buffer ∧ 0 ≤ st' ∧ st' ≤ s.buffer.length := by
  intro steps
  induction steps with
  | zero => intro s s' frames fr st st' δ δ' _ _ _ _ h; simp [EngineFramerExtracted.onInput_loop1] at h
  | succ n ih =>
    intro s s' frames fr st st' δ δ' hs hst0 hstL hδ h
    have hs' := hs
    obtain ⟨h1, h1', h4⟩ := hs
    unfold EngineFramerExtracted.onInput_loop1 at h
    by_cases hneg : δ ≥ 0
    · have hδ' : st ≤ δ ∧ δ + d.length ≤ s.buffer.length := by
        rcases hδ with h | h
        · omega
        · exact h
      simp only [hneg, if_true, bind, Except.bind, pure, Except.pure] at h
      by_cases hf : δ - st > mfl
      · simp [hf] at h
      · simp only [hf, if_false] at h
        cases hE : EngineFramerExtracted.indexOfDelimiter d mfl fuel s (δ + d.length) with
        | error err => rw [hE] at h; simp at h
        | ok v =>
          obtain ⟨s3, r4⟩ := v
          obtain ⟨p1, p2, p3⟩ := idx_post d mfl fuel s s3 (δ + d.length) r4 hs' (by omega) hd hE
          rw [hE] at h
          simp only [] at h
          have hrec := ih s3 s' _ fr _ st' r4 δ' p2 (by omega) (by rw [p1]; omega) (by
            rcases p3 with h5 | h5
            · exact Or.inl h5
            · refine Or.inr ⟨h5.1, ?_⟩
              rw [p1]; exact h5.2) h
          exact ⟨hrec.1, by rw [hrec.2.1, p1], hrec.2.2.1, by rw [← p1]; exact hrec.2.2.2⟩
    · simp only [hneg, if_false, pure, Except.pure, Except.ok.injEq, Prod.mk.injEq] at h
      obtain ⟨rfl, -, rfl, -⟩ := h
      exact ⟨hs', rfl, hst0, hstL⟩

/-! ## `onInput` -/

theorem concatC_ok {a b : List UInt8} (h : a.length + b.length ≤ 2147483647) :
    EngineFramerChecked.concatC a b = .ok (a ++ b) := by
  unfold EngineFramerChecked.concatC Java.concatChecked EngineFramerChecked.thrown
  rw [if_neg (by omega)]

def liftPush (p : E × List (List UInt8)) : C × List (List UInt8) := (toC p.1, p.2)

theorem onInput_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (input : List UInt8)
    (hs : ERanges s) (hc : s.buffer.length + input.length ≤ 2147483639) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) :
    EngineFramerChecked.onInput d mfl fuel (toC s) input =
      lift liftPush (EngineFramerExtracted.onInput d mfl fuel s input) := by
  obtain ⟨h1, h1', h4⟩ := hs
  have hr1 : ERanges (⟨s.buffer ++ input, s.scanFrom⟩ : E) :=
    ⟨h1, h1', by dsimp only; simp only [List.length_append]; omega⟩
  unfold EngineFramerChecked.onInput EngineFramerExtracted.onInput
  dsimp only
  have hb : (toC s).buffer = s.buffer := rfl
  have hsc : (toC s).scanFrom = s.scanFrom := rfl
  rw [hb, concatC_ok (by omega)]
  simp only [bind, Except.bind, pure, Except.pure]
  have hrw : toC (⟨s.buffer ++ input, s.scanFrom⟩ : E) =
      (⟨s.buffer ++ input, (toC s).scanFrom⟩ : C) := rfl
  have hidx := indexOfDelimiter_safe d mfl fuel (⟨s.buffer ++ input, s.scanFrom⟩ : E) 0 hr1
    (Int.le_refl _) hd hdM
  rw [hrw] at hidx
  rw [hidx]
  cases hI : EngineFramerExtracted.indexOfDelimiter d mfl fuel
      (⟨s.buffer ++ input, s.scanFrom⟩ : E) 0 with
  | error err => simp [lift]
  | ok v =>
    obtain ⟨s2, r2⟩ := v
    obtain ⟨p1, p2, p3⟩ := idx_post d mfl fuel _ s2 0 r2 hr1 (Int.le_refl _) hd hI
    simp only [lift, liftIdx, bind, Except.bind]
    have hδ : r2 < 0 ∨ ((0 : Int) ≤ r2 ∧ r2 + d.length ≤ s2.buffer.length) := by
      rcases p3 with h | h
      · exact Or.inl h
      · exact Or.inr ⟨h.1, by rw [p1]; exact h.2⟩
    have hL := onInput_loop_safe d mfl fuel hd hdM fuel s2 [] 0 r2 p2 (Int.le_refl _)
      (by omega) hδ
    rw [hL]
    cases hE : EngineFramerExtracted.onInput_loop1 d mfl fuel fuel s2 [] 0 r2 with
    | error err => simp [lift]
    | ok w =>
      obtain ⟨s5, fr5, st5, d5⟩ := w
      obtain ⟨q1, q2, q3, q4⟩ := onInput_loop_post d mfl fuel hd fuel s2 s5 [] fr5 0 st5 r2 d5 p2
        (Int.le_refl _) (by omega) hδ hE
      obtain ⟨w1, w1', w4⟩ := q1
      have l2 : s2.buffer.length = s.buffer.length + input.length := by rw [p1]; simp
      have l5 : s5.buffer.length = s2.buffer.length := by rw [q2]
      simp only [lift, liftLoop, bind, Except.bind]
      by_cases hs5 : st5 > 0
      · have hc5 := copyOfRangeC_ok (a := s5.buffer) (f := st5) (t := (s5.buffer.length : Int))
          q3 (by omega) (by omega) (Int.le_refl _)
        have e6 : EngineFramerChecked.i32 (s5.scanFrom - st5) = .ok (s5.scanFrom - st5) :=
          i32_ok (by omega) (by omega)
        have hb5 : (toC s5).buffer = s5.buffer := rfl
        have hsc5 : (toC s5).scanFrom = s5.scanFrom := rfl
        simp only [hs5, if_true, hb5, hsc5, hc5, e6, bind, Except.bind, pure, Except.pure]
        by_cases hq : ((copyOfRange s5.buffer st5.toNat (s5.buffer.length : Int).toNat).length : Int)
            > mfl
        · simp only [hq, if_true, lift]
          rfl
        · simp only [hq, if_false, lift]
          rfl
      · have hb5 : (toC s5).buffer = s5.buffer := rfl
        simp only [hs5, if_false, hb5, pure, Except.pure]
        by_cases hq : (s5.buffer.length : Int) > mfl
        · simp only [hq, if_true, lift]
          rfl
        · simp only [hq, if_false, lift, liftPush]

theorem onInput_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s s' : E) (input : List UInt8)
    (fr : List (List UInt8)) (hs : ERanges s) (hc : s.buffer.length + input.length ≤ 2147483639)
    (hd : 0 < d.length)
    (h : EngineFramerExtracted.onInput d mfl fuel s input = .ok (s', fr)) :
    ERanges s' ∧ s'.buffer.length ≤ s.buffer.length + input.length := by
  obtain ⟨h1, h1', h4⟩ := hs
  have hr1 : ERanges (⟨s.buffer ++ input, s.scanFrom⟩ : E) :=
    ⟨h1, h1', by dsimp only; simp only [List.length_append]; omega⟩
  unfold EngineFramerExtracted.onInput at h
  dsimp only at h
  simp only [bind, Except.bind, pure, Except.pure] at h
  cases hI : EngineFramerExtracted.indexOfDelimiter d mfl fuel
      (⟨s.buffer ++ input, s.scanFrom⟩ : E) 0 with
  | error err => rw [hI] at h; simp at h
  | ok v =>
    obtain ⟨s2, r2⟩ := v
    obtain ⟨p1, p2, p3⟩ := idx_post d mfl fuel _ s2 0 r2 hr1 (Int.le_refl _) hd hI
    rw [hI] at h
    simp only [] at h
    have hδ : r2 < 0 ∨ ((0 : Int) ≤ r2 ∧ r2 + d.length ≤ s2.buffer.length) := by
      rcases p3 with h | h
      · exact Or.inl h
      · exact Or.inr ⟨h.1, by rw [p1]; exact h.2⟩
    have l2 : s2.buffer.length = s.buffer.length + input.length := by rw [p1]; simp
    cases hE : EngineFramerExtracted.onInput_loop1 d mfl fuel fuel s2 [] 0 r2 with
    | error err => rw [hE] at h; simp at h
    | ok w =>
      obtain ⟨s5, fr5, st5, d5⟩ := w
      obtain ⟨q1, q2, q3, q4⟩ := onInput_loop_post d mfl fuel hd fuel s2 s5 [] fr5 0 st5 r2 d5 p2
        (Int.le_refl _) (by omega) hδ hE
      obtain ⟨w1, w1', w4⟩ := q1
      have l5 : s5.buffer.length = s.buffer.length + input.length := by rw [q2]; exact l2
      rw [hE] at h
      simp only [] at h
      by_cases hs5 : st5 > 0
      · simp only [hs5, if_true] at h
        by_cases hq : ((copyOfRange s5.buffer st5.toNat (s5.buffer.length : Int).toNat).length : Int)
            > mfl
        · rw [if_pos hq] at h
          cases h
        · simp only [hq, if_false, pure, Except.pure, Except.ok.injEq, Prod.mk.injEq] at h
          obtain ⟨rfl, -⟩ := h
          have hlen : (copyOfRange s5.buffer st5.toNat (s5.buffer.length : Int).toNat).length ≤
              s5.buffer.length := by simp [copyOfRange]
          refine ⟨⟨?_, ?_, ?_⟩, ?_⟩ <;> dsimp only <;> omega
      · simp only [hs5, if_false] at h
        by_cases hq : (s5.buffer.length : Int) > mfl
        · rw [if_pos hq] at h
          cases h
        · simp only [hq, if_false, pure, Except.pure, Except.ok.injEq, Prod.mk.injEq] at h
          obtain ⟨rfl, -⟩ := h
          exact ⟨⟨w1, w1', w4⟩, by omega⟩

/-! ## The stage -/

def endC : EngineFramerChecked.Failure → Option ExtractedDrivers.End
  | .CequenceWSFrameTooLongException => some .tooLong
  | .outOfFuel => some .outOfFuel
  | _ => none

theorem endC_castF (e : EngineFramerExtracted.Failure) :
    endC (castF e) = some (ExtractedDrivers.engineEnd e) := by
  cases e <;> rfl

/-- The engines' stage on the CHECKED code: `none` when a Java failure happens. -/
def engineRunFromC (d : List UInt8) (mfl : Int) (s : C) :
    List (List UInt8) → Option ExtractedDrivers.Result
  | [] =>
    match EngineFramerChecked.onUpstreamComplete d mfl 0 s with
    | .ok frames => some ⟨frames, .completed⟩
    | .error e => (endC e).map fun en => ⟨[], en⟩
  | c :: cs =>
    match EngineFramerChecked.onInput d mfl
        (ExtractedDrivers.fuelFor d.length s.buffer.length c.length) s c with
    | .ok (s', frames) =>
      (engineRunFromC d mfl s' cs).map (ExtractedDrivers.Result.prepend frames)
    | .error e => (endC e).map fun en => ⟨[], en⟩

def engineRunC (d : List UInt8) (mfl : Int) (chunks : List (List UInt8)) :
    Option ExtractedDrivers.Result :=
  engineRunFromC d mfl (EngineFramerChecked.init d mfl) chunks

theorem engineRunFromC_eq (d : List UInt8) (mfl : Int) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) :
    ∀ (cs : List (List UInt8)) (s : E), ERanges s →
      s.buffer.length + cs.flatten.length ≤ 2147483639 →
      engineRunFromC d mfl (toC s) cs = some (ExtractedDrivers.engineRunFrom d mfl s cs)
  | [], s, hs, _ => by
    unfold engineRunFromC ExtractedDrivers.engineRunFrom
    unfold EngineFramerChecked.onUpstreamComplete EngineFramerExtracted.onUpstreamComplete
    by_cases hb : s.buffer.isEmpty
    · simp [toC, hb, pure, Except.pure]
    · simp [toC, hb, pure, Except.pure]
  | c :: cs, s, hs, hb => by
    simp only [List.flatten_cons, List.length_append] at hb
    unfold engineRunFromC ExtractedDrivers.engineRunFrom
    have hbuf : (toC s).buffer = s.buffer := rfl
    rw [hbuf, onInput_safe d mfl _ s c hs (by omega) hd hdM]
    cases hP : EngineFramerExtracted.onInput d mfl
        (ExtractedDrivers.fuelFor d.length s.buffer.length c.length) s c with
    | error e => simp [lift, endC_castF]
    | ok r =>
      obtain ⟨s', fr⟩ := r
      obtain ⟨hr', he'⟩ := onInput_post d mfl _ s s' c fr hs (by omega) hd hP
      simp only [lift, liftPush]
      rw [engineRunFromC_eq d mfl hd hdM cs s' hr' (by omega)]
      rfl

/-- **Neither `int` overflow nor an array exception ever occurs** in the engines' framer, for
any input of at most `Int.MaxValue - 8` bytes: the checked run - every Java failure a step of
`Except` - never meets one, and ends as the unchecked run does. -/
theorem engineRunC_eq (d : List UInt8) (mfl : Int) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) (cs : List (List UInt8))
    (hb : cs.flatten.length ≤ 2147483639) :
    engineRunC d mfl cs = some (ExtractedDrivers.engineRun d mfl cs) := by
  unfold engineRunC ExtractedDrivers.engineRun
  have e : EngineFramerExtracted.init d mfl = (⟨[], 0⟩ : E) := rfl
  have hi : EngineFramerChecked.init d mfl = toC (EngineFramerExtracted.init d mfl) := rfl
  rw [hi]
  exact engineRunFromC_eq d mfl hd hdM cs _
    (by rw [e]; exact ⟨Int.le_refl _, by decide, by simp⟩) (by rw [e]; simpa using hb)

end WsClientProofs.Framing.EngineFramerSafety
