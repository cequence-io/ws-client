import WsClientProofs.Framing.ByteFramingChecked
import WsClientProofs.Framing.ByteFramingExtracted
import WsClientProofs.Framing.ByteFramingRefinement

/-!
# `ByteFraming.DelimiterFramer` never overflows an `int`, reads out of bounds or throws a Java
# array exception

`ByteFramingChecked` is the same Scala, extracted with every Java failure a step of `Except`:
an `int` or `long` operation whose result would wrap (`i32`, `i64`), an array read out of
bounds, a `copyOfRange` / `copyOf` / `arraycopy` / `new Array` Java would reject. This file proves
it equal to `ByteFramingExtracted` - the code the refinement proofs are about - on every state
whose indices and buffer lie within `int` range and every chunk that fits: none of those failures
occurs, so Java's wrapping and its exceptions never change the result.
-/

namespace WsClientProofs.Framing.ByteFramingSafety

open WsClientProofs.Java
open WsClientProofs.Framing.ByteFramingRefinement (arrayGet_natCast)

abbrev E := ByteFramingExtracted.State
abbrev C := ByteFramingChecked.State

/-- `Int.MaxValue - 8`: the most bytes the framer buffers. -/
def M : Int := 2147483639

def toC (s : E) : C := ⟨s.buffer, s.start, s.«end», s.scanFrom⟩

def castF : ByteFramingExtracted.Failure → ByteFramingChecked.Failure
  | .CequenceWSFrameTooLongException => .CequenceWSFrameTooLongException
  | .CequenceWSException => .CequenceWSException
  | .outOfFuel => .outOfFuel

/-- The extracted result as the checked code's. -/
def lift {α β : Type} (f : α → β) (r : Except ByteFramingExtracted.Failure α) :
    Except ByteFramingChecked.Failure β :=
  match r with
  | .ok a => .ok (f a)
  | .error e => .error (castF e)

/-! ## The prelude operations in range -/

theorem i32_ok {x : Int} (h1 : -2147483648 ≤ x) (h2 : x ≤ 2147483647) :
    ByteFramingChecked.i32 x = .ok x := by
  unfold ByteFramingChecked.i32 Scala.int32?
  rw [if_pos ⟨h1, h2⟩]

theorem i64_ok {x : Int} (h1 : -9223372036854775808 ≤ x) (h2 : x ≤ 9223372036854775807) :
    ByteFramingChecked.i64 x = .ok x := by
  unfold ByteFramingChecked.i64 Scala.int64?
  rw [if_pos ⟨h1, h2⟩]

theorem readArr_ok {a : List UInt8} {i : Int} (h1 : 0 ≤ i) (h2 : i < a.length) :
    ByteFramingChecked.readArr a i = .ok (a.getD i.toNat default) := by
  unfold ByteFramingChecked.readArr Java.readChecked ByteFramingChecked.thrown
  rw [if_pos ⟨h1, h2⟩]

theorem arrayGet_of_lt {a : List UInt8} {i : Int} (h1 : 0 ≤ i) (h2 : i < a.length) :
    Scala.arrayGet a i = some (a.getD i.toNat default) := by
  unfold Scala.arrayGet
  rw [if_neg (by omega)]
  have : i.toNat < a.length := by omega
  simp [List.getElem?_eq_getElem this, List.getD_eq_getElem?_getD]

/-! ## The search loops -/

theorem loop2_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (i : Int) (hi : 0 ≤ i)
    (hroom : i + d.length ≤ s.buffer.length) (hL : (s.buffer.length : Int) ≤ 2147483639) :
    ∀ (steps : Nat) (j : Int), 0 ≤ j → j ≤ d.length →
      ByteFramingChecked.indexOfDelimiter_loop2 d mfl fuel steps (toC s) i j =
        lift id (ByteFramingExtracted.indexOfDelimiter_loop2 d mfl fuel steps s i j) := by
  intro steps
  induction steps with
  | zero => intro j _ _; rfl
  | succ n ih =>
    intro j hj0 hjd
    rw [ByteFramingChecked.indexOfDelimiter_loop2, ByteFramingExtracted.indexOfDelimiter_loop2]
    have hb : (toC s).buffer = s.buffer := rfl
    simp only [hb]
    by_cases hlt : j < (d.length : Int)
    · have h1 : ByteFramingChecked.i32 (i + j) = .ok (i + j) := i32_ok (by omega) (by omega)
      have h2 := readArr_ok (a := s.buffer) (i := i + j) (by omega) (by omega)
      have h3 := readArr_ok (a := d) (i := j) (by omega) (by omega)
      have e1 := arrayGet_of_lt (a := s.buffer) (i := i + j) (by omega) (by omega)
      have e2 := arrayGet_of_lt (a := d) (i := j) (by omega) (by omega)
      simp only [hlt, if_true, decide_true, Bool.true_and, h1, h2, h3, e1, e2, bind, Except.bind,
        pure, Except.pure, beq_iff_eq]
      by_cases hab : s.buffer.getD (i + j).toNat default = d.getD j.toNat default
      · have h4 : ByteFramingChecked.i32 (j + 1) = .ok (j + 1) := i32_ok (by omega) (by omega)
        simp only [hab, if_true, h4]
        exact ih (j + 1) (by omega) (by omega)
      · simp only [hab, Option.some.injEq, if_false]
        rfl
    · simp [hlt, lift, pure, Except.pure, bind, Except.bind]

def liftSum (r : Sum (E × Int) Int) : Sum (C × Int) Int :=
  r.map (fun p => (toC p.1, p.2)) id

theorem lift_bind_ok {α β γ : Type} {f : α → γ} {r : Except ByteFramingExtracted.Failure α}
    {g : γ → Except ByteFramingChecked.Failure β} {a : α} (h : r = .ok a) :
    (match lift f r with
      | .ok v => g v
      | .error e => .error e) = g (f a) := by
  subst h; rfl

theorem loop1_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (e : Int)
    (he0 : 0 ≤ e) (hend : e ≤ s.buffer.length) (hL : (s.buffer.length : Int) ≤ 2147483639)
    (hd : 0 < d.length) :
    ∀ (steps : Nat) (i : Int), 0 ≤ i →
      ByteFramingChecked.indexOfDelimiter_loop1 d mfl fuel steps (toC s) i (e - d.length) =
        lift liftSum
          (ByteFramingExtracted.indexOfDelimiter_loop1 d mfl fuel steps s i (e - d.length)) := by
  intro steps
  induction steps with
  | zero => intro i _; rfl
  | succ n ih =>
    intro i hi
    rw [ByteFramingChecked.indexOfDelimiter_loop1, ByteFramingExtracted.indexOfDelimiter_loop1]
    by_cases hle : i ≤ e - d.length
    · simp only [hle, if_true]
      have hl2 := loop2_safe d mfl fuel s i hi (by omega) hL fuel 0 (by omega) (by omega)
      rw [hl2]
      cases hE : ByteFramingExtracted.indexOfDelimiter_loop2 d mfl fuel fuel s i 0 with
      | error err => simp [lift, bind, Except.bind]
      | ok r =>
        by_cases hr : (r == (d.length : Int)) = true
        · simp [lift, bind, Except.bind, hr, pure, Except.pure, liftSum]
        · have hi32 : ByteFramingChecked.i32 (i + 1) = .ok (i + 1) :=
            i32_ok (by omega) (by omega)
          simp only [lift, bind, Except.bind, id_eq, hr, if_false, pure, Except.pure, hi32,
            Bool.false_eq_true]
          exact ih (i + 1) (by omega)
    · simp [hle, lift, liftSum, pure, Except.pure]

/-- The state the framer is in between calls, as far as the `int` arithmetic is concerned. -/
structure Ranges (s : E) : Prop where
  start0 : 0 ≤ s.start
  scan0 : 0 ≤ s.scanFrom
  startScan : s.start ≤ s.scanFrom
  scanLe : s.scanFrom ≤ s.«end»
  startLe : s.start ≤ s.«end»
  endLe : s.«end» ≤ s.buffer.length
  bufferLe : (s.buffer.length : Int) ≤ 2147483639

def liftIdx (p : E × Int) : C × Int := (toC p.1, p.2)

theorem indexOfDelimiter_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (hs : Ranges s)
    (hd : 0 < d.length) (hdM : (d.length : Int) ≤ 2147483639) :
    ByteFramingChecked.indexOfDelimiter d mfl fuel (toC s) =
      lift liftIdx (ByteFramingExtracted.indexOfDelimiter d mfl fuel s) := by
  obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
  have hl1 := loop1_safe d mfl fuel s s.«end» (by omega) h3 h4 hd fuel (max s.start s.scanFrom)
    (by omega)
  have hi32 : ByteFramingChecked.i32 (s.«end» - d.length) = .ok (s.«end» - d.length) :=
    i32_ok (by omega) (by omega)
  have hi32' : ByteFramingChecked.i32 (s.«end» - d.length + 1) = .ok (s.«end» - d.length + 1) :=
    i32_ok (by omega) (by omega)
  unfold ByteFramingChecked.indexOfDelimiter ByteFramingExtracted.indexOfDelimiter
  have hb : (toC s).«end» = s.«end» := rfl
  have hst : (toC s).start = s.start := rfl
  have hsc : (toC s).scanFrom = s.scanFrom := rfl
  simp only [hb, hst, hsc, hi32, bind, Except.bind]
  rw [hl1]
  cases hE : ByteFramingExtracted.indexOfDelimiter_loop1 d mfl fuel fuel s
      (max s.start s.scanFrom) (s.«end» - d.length) with
  | error err => simp [lift, bind, Except.bind]
  | ok v =>
    cases v with
    | inl v => simp [lift, liftSum, liftIdx, bind, Except.bind, pure, Except.pure, Sum.map]
    | inr i2 =>
      simp only [lift, liftSum, bind, Except.bind, pure, Except.pure, Sum.map, hi32']
      rfl

/-! ## `append` -/

theorem arraycopyC_ok {src dst : List UInt8} {sp dp len : Int} (h1 : 0 ≤ sp) (h2 : 0 ≤ dp)
    (h3 : 0 ≤ len) (h4 : sp + len ≤ src.length) (h5 : dp + len ≤ dst.length) :
    ByteFramingChecked.arraycopyC src sp dst dp len =
      .ok (Java.arraycopy src sp.toNat dst dp.toNat len.toNat) := by
  unfold ByteFramingChecked.arraycopyC Java.arraycopyChecked ByteFramingChecked.thrown
  rw [if_neg (by omega)]

theorem copyOfC_ok {a : List UInt8} {n : Int} (h : 0 ≤ n) :
    ByteFramingChecked.copyOfC a n = .ok (Java.copyOf a n.toNat) := by
  unfold ByteFramingChecked.copyOfC Java.copyOfChecked ByteFramingChecked.thrown
  rw [if_neg (by omega)]

theorem copyOfRangeC_ok {a : List UInt8} {f t : Int} (h1 : 0 ≤ f) (h2 : f ≤ a.length)
    (h3 : f ≤ t) (h4 : t ≤ a.length) :
    ByteFramingChecked.copyOfRangeC a f t = .ok (Java.copyOfRange a f.toNat t.toNat) := by
  unfold ByteFramingChecked.copyOfRangeC Java.copyOfRangeChecked ByteFramingChecked.thrown
  rw [if_neg (by omega), if_neg (by omega)]
  have : (t - f).toNat - (a.length - f.toNat) = 0 := by omega
  simp [this, Java.copyOfRange]
  congr 1
  omega

/-- Side conditions about arithmetic and the lengths of what the copies produce. -/
macro "safe_disch" : tactic =>
  `(tactic| first
    | omega
    | (simp (disch := omega) only [Java.length_copyOf, Java.length_arraycopy]; omega))

theorem append_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (c : List UInt8)
    (hs : Ranges s) (hc : s.«end» + c.length ≤ 2147483639) :
    ByteFramingChecked.append d mfl fuel (toC s) c =
      lift toC (ByteFramingExtracted.append d mfl fuel s c) := by
  obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
  unfold ByteFramingChecked.append ByteFramingExtracted.append
  dsimp only
  have hb : (toC s).buffer = s.buffer := rfl
  have he : (toC s).«end» = s.«end» := rfl
  have hst : (toC s).start = s.start := rfl
  have hsc : (toC s).scanFrom = s.scanFrom := rfl
  simp only [hb, he, hst, hsc]
  by_cases hl : (c.length : Int) > 0
  · simp only [hl, if_true]
    simp (disch := safe_disch) only [i32_ok, i64_ok, arraycopyC_ok, copyOfC_ok,
      Java.length_arraycopy, Java.length_copyOf, bind, Except.bind, pure, Except.pure]
    have e1 : ByteFramingChecked.i32 (s.scanFrom - s.start) = .ok (s.scanFrom - s.start) :=
      i32_ok (by omega) (by omega)
    by_cases hA : s.start > 0
    · by_cases hB : s.«end» + c.length > s.buffer.length
      · by_cases hG : s.«end» - s.start + c.length > s.buffer.length
        · have hM : ¬ (s.«end» - s.start + c.length > 2147483647 - 8) := by omega
          simp only [hA, hB, hG, hM, e1, decide_true, if_true, if_false, lift, Bool.and_self]
          try rfl
        · simp only [hA, hB, hG, e1, decide_true, if_true, if_false, lift, Bool.and_self]
          try rfl
      · simp only [hA, hB, decide_true, decide_false, if_true, if_false, lift, Bool.and_false,
          Bool.true_and]
        try rfl
    · have hB : (s.«end» + c.length > s.buffer.length) ∨ ¬ (s.«end» + c.length > s.buffer.length) :=
        Classical.em _
      rcases hB with hB | hB
      · have hM : ¬ (s.«end» + c.length > 2147483647 - 8) := by omega
        simp only [hA, hB, hM, decide_false, decide_true, if_true, if_false, lift,
          Bool.false_and]
        try rfl
      · simp only [hA, hB, decide_false, if_true, if_false, lift, Bool.false_and]
        try rfl
  · simp only [hl, if_false]
    rfl

/-! ## What `append` leaves -/

theorem append_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s s1 : E) (c : List UInt8)
    (hs : Ranges s) (hc : s.«end» + c.length ≤ 2147483639)
    (h : ByteFramingExtracted.append d mfl fuel s c = .ok s1) :
    Ranges s1 ∧ s1.«end» ≤ s.«end» + c.length := by
  have hs' := hs
  obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
  unfold ByteFramingExtracted.append at h
  dsimp only at h
  by_cases hl : (c.length : Int) > 0
  · simp only [hl, if_true] at h
    by_cases hA : s.start > 0
    · by_cases hB : s.«end» + c.length > s.buffer.length
      · by_cases hG : s.«end» - s.start + c.length > s.buffer.length
        · have hM : ¬ (s.«end» - s.start + c.length > 2147483647 - 8) := by omega
          simp (disch := safe_disch) only [hA, hB, hG, hM, decide_true, if_true, if_false,
            Bool.and_self, bind, Except.bind, pure, Except.pure, Java.length_arraycopy,
            Java.length_copyOf, Except.ok.injEq, Bool.false_eq_true] at h
          subst h
          refine ⟨⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
            by dsimp only; omega, by dsimp only; omega, ?_, ?_⟩, by dsimp only; omega⟩
          · dsimp only
            simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
            omega
          · dsimp only
            simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
            omega
        · simp (disch := safe_disch) only [hA, hB, hG, decide_true, if_true, if_false,
            Bool.and_self, bind, Except.bind, pure, Except.pure, Java.length_arraycopy,
            Java.length_copyOf, Except.ok.injEq, Bool.false_eq_true] at h
          subst h
          refine ⟨⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
            by dsimp only; omega, by dsimp only; omega, ?_, ?_⟩, by dsimp only; omega⟩
          · dsimp only
            simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
            omega
          · dsimp only
            simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
            omega
      · simp (disch := safe_disch) only [hA, hB, decide_true, decide_false, if_true, if_false,
          Bool.and_false, Bool.true_and, bind, Except.bind, pure, Except.pure,
          Java.length_arraycopy, Java.length_copyOf, Except.ok.injEq, Bool.false_eq_true] at h
        subst h
        refine ⟨⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
          by dsimp only; omega, by dsimp only; omega, ?_, ?_⟩, by dsimp only; omega⟩
        · dsimp only
          simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
          omega
        · dsimp only
          simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
          omega
    · by_cases hB : s.«end» + c.length > s.buffer.length
      · have hM : ¬ (s.«end» + c.length > 2147483647 - 8) := by omega
        simp (disch := safe_disch) only [hA, hB, hM, decide_false, decide_true, if_true, if_false,
          Bool.false_and, bind, Except.bind, pure, Except.pure, Java.length_arraycopy,
          Java.length_copyOf, Except.ok.injEq, Bool.false_eq_true] at h
        subst h
        refine ⟨⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
          by dsimp only; omega, by dsimp only; omega, ?_, ?_⟩, by dsimp only; omega⟩
        · dsimp only
          simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
          omega
        · dsimp only
          simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
          omega
      · simp (disch := safe_disch) only [hA, hB, decide_false, if_true, if_false, Bool.false_and,
          bind, Except.bind, pure, Except.pure, Java.length_arraycopy, Java.length_copyOf,
          Except.ok.injEq, Bool.false_eq_true] at h
        subst h
        refine ⟨⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
          by dsimp only; omega, by dsimp only; omega, ?_, ?_⟩, by dsimp only; omega⟩
        · dsimp only
          simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
          omega
        · dsimp only
          simp (disch := safe_disch) only [Java.length_arraycopy, Java.length_copyOf]
          omega
  · simp only [hl, if_false] at h
    have h' : s1 = s := by
      simpa [bind, Except.bind, pure, Except.pure] using h.symm
    subst h'
    exact ⟨hs', by omega⟩

/-! ## `finish` -/

theorem finish_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (t : Bool) (hs : Ranges s) :
    ByteFramingChecked.finish d mfl fuel (toC s) t =
      lift id (ByteFramingExtracted.finish d mfl fuel s t) := by
  obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
  unfold ByteFramingChecked.finish ByteFramingExtracted.finish
  have he : (toC s).«end» = s.«end» := rfl
  have hst : (toC s).start = s.start := rfl
  have hb : (toC s).buffer = s.buffer := rfl
  simp only [he, hst, hb]
  by_cases hq : s.«end» = s.start
  · simp [hq, lift, pure, Except.pure]
  · have hq' : ¬ ((s.«end» == s.start) = true) := fun h => hq (beq_iff_eq.mp h)
    cases t
    · simp [hq', lift]
      rfl
    · have hc := copyOfRangeC_ok (a := s.buffer) (f := s.start) (t := s.«end») h0 (by omega)
        (by omega) h3
      simp only [hq', if_false, hc, bind, Except.bind, pure, Except.pure, lift]
      try rfl

/-! ## What the search returns -/

theorem loop1_inl_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (limit : Int) :
    ∀ (steps : Nat) (i : Int) (s' : E) (r : Int),
      ByteFramingExtracted.indexOfDelimiter_loop1 d mfl fuel steps s i limit = .ok (.inl (s', r)) →
        i ≤ r ∧ r ≤ limit ∧ s' = s := by
  intro steps
  induction steps with
  | zero => intro i s' r h; simp [ByteFramingExtracted.indexOfDelimiter_loop1] at h
  | succ n ih =>
    intro i s' r h
    unfold ByteFramingExtracted.indexOfDelimiter_loop1 at h
    by_cases hle : i ≤ limit
    · simp only [hle, if_true, bind, Except.bind, pure, Except.pure] at h
      cases hj : ByteFramingExtracted.indexOfDelimiter_loop2 d mfl fuel fuel s i 0 with
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

theorem idx_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s s' : E) (r : Int) (hs : Ranges s)
    (hd : 0 < d.length)
    (h : ByteFramingExtracted.indexOfDelimiter d mfl fuel s = .ok (s', r)) :
    s'.buffer = s.buffer ∧ s'.start = s.start ∧ s'.«end» = s.«end» ∧ Ranges s' ∧
      (r < 0 ∨ (s.start ≤ r ∧ r + d.length ≤ s.«end»)) := by
  obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
  unfold ByteFramingExtracted.indexOfDelimiter at h
  simp only [bind, Except.bind, pure, Except.pure] at h
  cases hl : ByteFramingExtracted.indexOfDelimiter_loop1 d mfl fuel fuel s (max s.start s.scanFrom)
      (s.«end» - d.length) with
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
      exact ⟨rfl, rfl, rfl, ⟨h0, h1, hss, h1', h2, h3, h4⟩, Or.inr ⟨by omega, by omega⟩⟩
    | inr i2 =>
      simp only [Except.ok.injEq, Prod.mk.injEq] at h
      obtain ⟨rfl, rfl⟩ := h
      refine ⟨rfl, rfl, rfl, ⟨h0, by dsimp only; omega, by dsimp only; omega,
        by dsimp only; omega, h2, h3, h4⟩, Or.inl (by omega)⟩

/-! ## The frame loop of `push` -/

def liftLoop (p : E × List (List UInt8) × Int) : C × List (List UInt8) × Int :=
  (toC p.1, p.2.1, p.2.2)

theorem push_loop_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) :
    ∀ (steps : Nat) (s : E) (frames : List (List UInt8)) (δ : Int), Ranges s →
      (δ < 0 ∨ (s.start ≤ δ ∧ δ + d.length ≤ s.«end»)) →
      ByteFramingChecked.push_loop1 d mfl fuel steps (toC s) frames δ =
        lift liftLoop (ByteFramingExtracted.push_loop1 d mfl fuel steps s frames δ) := by
  intro steps
  induction steps with
  | zero => intro s frames δ _ _; rfl
  | succ n ih =>
    intro s frames δ hs hδ
    obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
    rw [ByteFramingChecked.push_loop1, ByteFramingExtracted.push_loop1]
    by_cases hneg : δ ≥ 0
    · have hδ' : s.start ≤ δ ∧ δ + d.length ≤ s.«end» := by
        rcases hδ with h | h
        · omega
        · exact h
      have e1 : ByteFramingChecked.i32 (δ - s.start) = .ok (δ - s.start) :=
        i32_ok (by omega) (by omega)
      have e3 : ByteFramingChecked.i32 (δ + d.length) = .ok (δ + d.length) :=
        i32_ok (by omega) (by omega)
      have e2 := copyOfRangeC_ok (a := s.buffer) (f := s.start) (t := δ) h0 (by omega) (by omega)
        (by omega)
      have hb : (toC s).buffer = s.buffer := rfl
      have hst : (toC s).start = s.start := rfl
      simp only [hneg, if_true, hb, hst, e1, e2, e3, bind, Except.bind, pure, Except.pure]
      by_cases hf : δ - s.start > mfl
      · simp only [hf, if_true, lift]
        rfl
      · simp only [hf, if_false]
        have he : (toC s).«end» = s.«end» := rfl
        have hr2 : Ranges (⟨s.buffer, δ + d.length, s.«end», δ + d.length⟩ : E) :=
          ⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
            by dsimp only; omega, by dsimp only; omega, h3, h4⟩
        have hidx := indexOfDelimiter_safe d mfl fuel _ hr2 hd hdM
        have hrw : toC (⟨s.buffer, δ + d.length, s.«end», δ + d.length⟩ : E) =
            ⟨s.buffer, δ + d.length, (toC s).«end», δ + d.length⟩ := rfl
        rw [hrw] at hidx
        rw [hidx]
        cases hE : ByteFramingExtracted.indexOfDelimiter d mfl fuel
            (⟨s.buffer, δ + d.length, s.«end», δ + d.length⟩ : E) with
        | error err => simp [lift]
        | ok v =>
          obtain ⟨s3, r4⟩ := v
          obtain ⟨p1, p2, p3, p4, p5⟩ := idx_post d mfl fuel _ s3 r4 hr2 hd hE
          simp only [lift, liftIdx]
          refine ih s3 _ r4 p4 ?_
          rcases p5 with h | h
          · exact Or.inl h
          · refine Or.inr ⟨?_, ?_⟩
            · rw [p2]; exact h.1
            · rw [p3]; exact h.2
    · simp [hneg, lift, liftLoop, pure, Except.pure]

/-! ## `push` -/

theorem push_loop_post (d : List UInt8) (mfl : Int) (fuel : Nat) (hd : 0 < d.length) :
    ∀ (steps : Nat) (s s' : E) (frames fr : List (List UInt8)) (δ δ' : Int), Ranges s →
      (δ < 0 ∨ (s.start ≤ δ ∧ δ + d.length ≤ s.«end»)) →
      ByteFramingExtracted.push_loop1 d mfl fuel steps s frames δ = .ok (s', fr, δ') →
        Ranges s' ∧ s'.«end» = s.«end» := by
  intro steps
  induction steps with
  | zero => intro s s' frames fr δ δ' _ _ h; simp [ByteFramingExtracted.push_loop1] at h
  | succ n ih =>
    intro s s' frames fr δ δ' hs hδ h
    have hs' := hs
    obtain ⟨h0, h1, hss, h1', h2, h3, h4⟩ := hs
    unfold ByteFramingExtracted.push_loop1 at h
    by_cases hneg : δ ≥ 0
    · have hδ' : s.start ≤ δ ∧ δ + d.length ≤ s.«end» := by
        rcases hδ with h | h
        · omega
        · exact h
      simp only [hneg, if_true, bind, Except.bind, pure, Except.pure] at h
      by_cases hf : δ - s.start > mfl
      · simp [hf] at h
      · simp only [hf, if_false] at h
        have hr2 : Ranges (⟨s.buffer, δ + d.length, s.«end», δ + d.length⟩ : E) :=
          ⟨by dsimp only; omega, by dsimp only; omega, by dsimp only; omega,
            by dsimp only; omega, by dsimp only; omega, h3, h4⟩
        cases hE : ByteFramingExtracted.indexOfDelimiter d mfl fuel
            (⟨s.buffer, δ + d.length, s.«end», δ + d.length⟩ : E) with
        | error err => rw [hE] at h; simp at h
        | ok v =>
          obtain ⟨s3, r4⟩ := v
          obtain ⟨p1, p2, p3, p4, p5⟩ := idx_post d mfl fuel _ s3 r4 hr2 hd hE
          rw [hE] at h
          simp only [] at h
          have hrec := ih s3 s' _ fr r4 δ' p4 (by
            rcases p5 with h5 | h5
            · exact Or.inl h5
            · refine Or.inr ⟨?_, ?_⟩
              · rw [p2]; exact h5.1
              · rw [p3]; exact h5.2) h
          exact ⟨hrec.1, by rw [hrec.2, p3]⟩
    · simp only [hneg, if_false, pure, Except.pure, Except.ok.injEq, Prod.mk.injEq] at h
      obtain ⟨rfl, -, -⟩ := h
      exact ⟨hs', rfl⟩

def liftPush (p : E × List (List UInt8)) : C × List (List UInt8) := (toC p.1, p.2)

theorem push_safe (d : List UInt8) (mfl : Int) (fuel : Nat) (s : E) (c : List UInt8)
    (hs : Ranges s) (hc : s.«end» + c.length ≤ 2147483639) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) :
    ByteFramingChecked.push d mfl fuel (toC s) c =
      lift liftPush (ByteFramingExtracted.push d mfl fuel s c) := by
  unfold ByteFramingChecked.push ByteFramingExtracted.push
  dsimp only
  rw [append_safe d mfl fuel s c hs hc]
  cases hA : ByteFramingExtracted.append d mfl fuel s c with
  | error err => simp [lift, bind, Except.bind]
  | ok s1 =>
    obtain ⟨hr1, he1⟩ := append_post d mfl fuel s s1 c hs hc hA
    simp only [lift, bind, Except.bind]
    rw [indexOfDelimiter_safe d mfl fuel s1 hr1 hd hdM]
    cases hI : ByteFramingExtracted.indexOfDelimiter d mfl fuel s1 with
    | error err => simp [lift, bind, Except.bind]
    | ok v =>
      obtain ⟨s2, r2⟩ := v
      obtain ⟨p1, p2, p3, p4, p5⟩ := idx_post d mfl fuel s1 s2 r2 hr1 hd hI
      simp only [lift, liftIdx, bind, Except.bind]
      have hδ : r2 < 0 ∨ (s2.start ≤ r2 ∧ r2 + d.length ≤ s2.«end») := by
        rcases p5 with h | h
        · exact Or.inl h
        · exact Or.inr ⟨by rw [p2]; exact h.1, by rw [p3]; exact h.2⟩
      rw [push_loop_safe d mfl fuel hd hdM fuel s2 [] r2 p4 hδ]
      cases hL : ByteFramingExtracted.push_loop1 d mfl fuel fuel s2 [] r2 with
      | error err => simp [lift, bind, Except.bind]
      | ok w =>
        obtain ⟨s5, fr5, d5⟩ := w
        obtain ⟨hr5, hend5⟩ := push_loop_post d mfl fuel hd fuel s2 s5 [] fr5 r2 d5 p4 hδ hL
        obtain ⟨q0, q1, qss, q1', q2, q3, q4⟩ := hr5
        have e4 : ByteFramingChecked.i32 (s5.«end» - s5.start) = .ok (s5.«end» - s5.start) :=
          i32_ok (by omega) (by omega)
        have hb5 : (toC s5).«end» = s5.«end» := rfl
        have hs5 : (toC s5).start = s5.start := rfl
        simp only [lift, liftLoop, bind, Except.bind, hb5, hs5, e4]
        by_cases hq : s5.«end» - s5.start > mfl
        · simp [hq, lift]
          rfl
        · simp [hq, lift, liftPush, pure, Except.pure]

theorem push_post (d : List UInt8) (mfl : Int) (fuel : Nat) (s s' : E) (c : List UInt8)
    (fr : List (List UInt8)) (hs : Ranges s) (hc : s.«end» + c.length ≤ 2147483639)
    (hd : 0 < d.length)
    (h : ByteFramingExtracted.push d mfl fuel s c = .ok (s', fr)) :
    Ranges s' ∧ s'.«end» ≤ s.«end» + c.length := by
  unfold ByteFramingExtracted.push at h
  dsimp only at h
  cases hA : ByteFramingExtracted.append d mfl fuel s c with
  | error err => rw [hA] at h; simp [bind, Except.bind] at h
  | ok s1 =>
    obtain ⟨hr1, he1⟩ := append_post d mfl fuel s s1 c hs hc hA
    rw [hA] at h
    simp only [bind, Except.bind] at h
    cases hI : ByteFramingExtracted.indexOfDelimiter d mfl fuel s1 with
    | error err => rw [hI] at h; simp at h
    | ok v =>
      obtain ⟨s2, r2⟩ := v
      obtain ⟨p1, p2, p3, p4, p5⟩ := idx_post d mfl fuel s1 s2 r2 hr1 hd hI
      rw [hI] at h
      simp only [] at h
      have hδ : r2 < 0 ∨ (s2.start ≤ r2 ∧ r2 + d.length ≤ s2.«end») := by
        rcases p5 with h5 | h5
        · exact Or.inl h5
        · exact Or.inr ⟨by rw [p2]; exact h5.1, by rw [p3]; exact h5.2⟩
      cases hL : ByteFramingExtracted.push_loop1 d mfl fuel fuel s2 [] r2 with
      | error err => rw [hL] at h; simp at h
      | ok w =>
        obtain ⟨s5, fr5, d5⟩ := w
        obtain ⟨hr5, hend5⟩ := push_loop_post d mfl fuel hd fuel s2 s5 [] fr5 r2 d5 p4 hδ hL
        rw [hL] at h
        simp only [] at h
        by_cases hq : s5.«end» - s5.start > mfl
        · simp [hq] at h
        · simp only [hq, if_false, pure, Except.pure, Except.ok.injEq, Prod.mk.injEq] at h
          obtain ⟨rfl, -⟩ := h
          refine ⟨hr5, ?_⟩
          rw [hend5, p3]
          exact he1

/-! ## The stage -/

/-- A failure of the checked code that is the stream's own ending; a Java failure (an `int` that
would wrap, an array access out of bounds, a negative size) is `none`. -/
def endC : ByteFramingChecked.Failure → Option ExtractedDrivers.End
  | .CequenceWSException => some .truncated
  | .CequenceWSFrameTooLongException => some .tooLong
  | .outOfFuel => some .outOfFuel
  | _ => none

theorem endC_castF (e : ByteFramingExtracted.Failure) :
    endC (castF e) = some (ExtractedDrivers.byteEnd e) := by
  cases e <;> rfl

/-- `ByteFraming.delimiter`'s stage on the CHECKED code: `none` when a Java failure happens. -/
def byteRunFromC (d : List UInt8) (mfl : Int) (t : Bool) (s : C) :
    List (List UInt8) → Option ExtractedDrivers.Result
  | [] =>
    match ByteFramingChecked.finish d mfl 0 s t with
    | .ok frames => some ⟨frames, .completed⟩
    | .error e => (endC e).map fun en => ⟨[], en⟩
  | c :: cs =>
    match ByteFramingChecked.push d mfl
        (ExtractedDrivers.fuelFor d.length s.buffer.length c.length) s c with
    | .ok (s', frames) =>
      (byteRunFromC d mfl t s' cs).map (ExtractedDrivers.Result.prepend frames)
    | .error e => (endC e).map fun en => ⟨[], en⟩

def byteRunC (d : List UInt8) (mfl : Int) (t : Bool) (chunks : List (List UInt8)) :
    Option ExtractedDrivers.Result :=
  byteRunFromC d mfl t (ByteFramingChecked.init d mfl) chunks

theorem byteRunFromC_eq (d : List UInt8) (mfl : Int) (t : Bool) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) :
    ∀ (cs : List (List UInt8)) (s : E), Ranges s → s.«end» + cs.flatten.length ≤ 2147483639 →
      byteRunFromC d mfl t (toC s) cs = some (ExtractedDrivers.byteRunFrom d mfl t s cs)
  | [], s, hs, _ => by
    unfold byteRunFromC ExtractedDrivers.byteRunFrom
    rw [finish_safe d mfl 0 s t hs]
    cases hF : ByteFramingExtracted.finish d mfl 0 s t with
    | ok fs => simp [lift]
    | error e => simp [lift, endC_castF]
  | c :: cs, s, hs, hb => by
    simp only [List.flatten_cons, List.length_append] at hb
    unfold byteRunFromC ExtractedDrivers.byteRunFrom
    have hbuf : (toC s).buffer = s.buffer := rfl
    rw [hbuf, push_safe d mfl _ s c hs (by omega) hd hdM]
    cases hP : ByteFramingExtracted.push d mfl
        (ExtractedDrivers.fuelFor d.length s.buffer.length c.length) s c with
    | error e => simp [lift, endC_castF]
    | ok r =>
      obtain ⟨s', fr⟩ := r
      obtain ⟨hr', he'⟩ := push_post d mfl _ s s' c fr hs (by omega) hd hP
      simp only [lift, liftPush]
      rw [byteRunFromC_eq d mfl t hd hdM cs s' hr' (by omega)]
      rfl

theorem init_ranges (d : List UInt8) (mfl : Int) :
    Ranges (ByteFramingExtracted.init d mfl) := by
  have e : ByteFramingExtracted.init d mfl =
      (⟨List.replicate 256 0, 0, 0, 0⟩ : E) := rfl
  rw [e]
  refine ⟨Int.le_refl _, Int.le_refl _, Int.le_refl _, Int.le_refl _, Int.le_refl _, ?_, ?_⟩ <;>
    simp only [List.length_replicate] <;> omega

/-- **Neither `int` overflow nor an array exception ever occurs** in `ByteFraming.delimiter`'s
framer, for any input of at most `Int.MaxValue - 8` bytes: the checked run - every Java failure
a step of `Except` - never meets one, and ends as the unchecked run does. -/
theorem byteRunC_eq (d : List UInt8) (mfl : Int) (t : Bool) (hd : 0 < d.length)
    (hdM : (d.length : Int) ≤ 2147483639) (cs : List (List UInt8))
    (hb : cs.flatten.length ≤ 2147483639) :
    byteRunC d mfl t cs = some (ExtractedDrivers.byteRun d mfl t cs) := by
  unfold byteRunC ExtractedDrivers.byteRun
  have hi : ByteFramingChecked.init d mfl = toC (ByteFramingExtracted.init d mfl) := rfl
  rw [hi]
  have e : ByteFramingExtracted.init d mfl = (⟨List.replicate 256 0, 0, 0, 0⟩ : E) := rfl
  exact byteRunFromC_eq d mfl t hd hdM cs _ (init_ranges d mfl) (by rw [e]; dsimp only; omega)

end WsClientProofs.Framing.ByteFramingSafety
