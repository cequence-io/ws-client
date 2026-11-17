import WsClientProofs.Framing.Spec

/-!
# Lemmas about occurrences and `splitOn`

The central one is `splitOn_append`: splitting `x ++ y` is splitting `x`, then splitting its
rest followed by `y` - the specification-level reason why framing can proceed chunk by chunk.
-/

namespace WsClientProofs.Framing

set_option linter.unusedSectionVars false

variable {α : Type} [DecidableEq α]

theorem prefix_append_iff_of_length_le {d a y : List α} (h : d.length ≤ a.length) :
    d <+: a ++ y ↔ d <+: a := by
  rw [List.prefix_iff_eq_take, List.prefix_iff_eq_take, List.take_append_of_le_length h]

theorem prefix_take_iff_of_length_le {d a : List α} {n : Nat} (h : d.length ≤ n) :
    d <+: a.take n ↔ d <+: a := by
  rw [List.prefix_iff_eq_take, List.prefix_iff_eq_take, List.take_take, Nat.min_eq_left h]

theorem occursAt_length {d l : List α} {p : Nat} (hd : d ≠ []) (h : OccursAt d l p) :
    p + d.length ≤ l.length := by
  have h1 := h.length_le
  have h2 := List.length_pos_iff.mpr hd
  simp only [List.length_drop] at h1
  omega

theorem occursAt_cons_zero {d : List α} {x : α} {xs : List α} :
    OccursAt d (x :: xs) 0 ↔ d <+: x :: xs := by
  simp [OccursAt]

theorem occursAt_cons_succ {d : List α} {x : α} {xs : List α} {q : Nat} :
    OccursAt d (x :: xs) (q + 1) ↔ OccursAt d xs q := by
  simp [OccursAt]

theorem not_occursAt_nil {d : List α} {q : Nat} (hd : d ≠ []) : ¬ OccursAt d [] q := by
  simp only [OccursAt, List.drop_nil, List.prefix_nil]
  exact hd

/-- An occurrence that ends inside `x` is one in `x ++ y`, and conversely. -/
theorem occursAt_append_iff_of_le {d x y : List α} {q : Nat} (h : q + d.length ≤ x.length) :
    OccursAt d (x ++ y) q ↔ OccursAt d x q := by
  simp only [OccursAt]
  rw [List.drop_append_of_le_length (by omega)]
  exact prefix_append_iff_of_length_le (by simp; omega)

theorem occursAt_append_of_occursAt {d x y : List α} {q : Nat} (hd : d ≠ [])
    (h : OccursAt d x q) : OccursAt d (x ++ y) q :=
  (occursAt_append_iff_of_le (occursAt_length hd h)).mpr h

theorem occursAt_drop {d l : List α} {p q : Nat} :
    OccursAt d (l.drop p) q ↔ OccursAt d l (p + q) := by
  simp [OccursAt, List.drop_drop]

theorem firstOcc_eq_some_iff {d l : List α} {p : Nat} (hd : d ≠ []) :
    firstOcc d l = some p ↔ OccursAt d l p ∧ ∀ q < p, ¬ OccursAt d l q := by
  induction l generalizing p with
  | nil =>
    constructor
    · intro h; simp [firstOcc] at h
    · rintro ⟨h, _⟩; exact absurd h (not_occursAt_nil hd)
  | cons x xs ih =>
    have hne : d.isEmpty = false := List.isEmpty_eq_false_iff.mpr hd
    simp only [firstOcc, hne, Bool.not_false, Bool.true_and]
    split
    · rename_i hpre
      have hpre := List.isPrefixOf_iff_prefix.mp hpre
      constructor
      · intro h
        simp only [Option.some.injEq] at h
        subst h
        exact ⟨occursAt_cons_zero.mpr hpre, fun q hq => absurd hq (Nat.not_lt_zero q)⟩
      · rintro ⟨_, hmin⟩
        cases p with
        | zero => rfl
        | succ p => exact absurd (occursAt_cons_zero.mpr hpre) (hmin 0 (Nat.succ_pos p))
    · rename_i hpre
      have hpre : ¬ d <+: x :: xs := fun h => hpre (List.isPrefixOf_iff_prefix.mpr h)
      constructor
      · intro h
        obtain ⟨q, hq, rfl⟩ := Option.map_eq_some_iff.mp h
        obtain ⟨hocc, hmin⟩ := (ih).mp hq
        refine ⟨occursAt_cons_succ.mpr hocc, fun r hr => ?_⟩
        cases r with
        | zero => exact fun h0 => hpre (occursAt_cons_zero.mp h0)
        | succ r => exact fun h1 => hmin r (by omega) (occursAt_cons_succ.mp h1)
      · rintro ⟨hocc, hmin⟩
        cases p with
        | zero => exact absurd (occursAt_cons_zero.mp hocc) hpre
        | succ p =>
          have : firstOcc d xs = some p :=
            (ih).mpr ⟨occursAt_cons_succ.mp hocc,
              fun q hq h => hmin (q + 1) (by omega) (occursAt_cons_succ.mpr h)⟩
          simp [this]

theorem firstOcc_eq_none_iff {d l : List α} (hd : d ≠ []) :
    firstOcc d l = none ↔ ∀ q, ¬ OccursAt d l q := by
  induction l with
  | nil => simp only [firstOcc, true_iff]; exact fun q => not_occursAt_nil hd
  | cons x xs ih =>
    have hne : d.isEmpty = false := List.isEmpty_eq_false_iff.mpr hd
    simp only [firstOcc, hne, Bool.not_false, Bool.true_and]
    split
    · rename_i hpre
      constructor
      · intro h; simp at h
      · intro h; exact absurd (occursAt_cons_zero.mpr (List.isPrefixOf_iff_prefix.mp hpre)) (h 0)
    · rename_i hpre
      have hpre : ¬ d <+: x :: xs := fun h => hpre (List.isPrefixOf_iff_prefix.mpr h)
      simp only [Option.map_eq_none_iff]
      rw [ih]
      constructor
      · intro h q
        cases q with
        | zero => exact fun h0 => hpre (occursAt_cons_zero.mp h0)
        | succ q => exact fun h1 => h q (occursAt_cons_succ.mp h1)
      · intro h q hq
        exact h (q + 1) (occursAt_cons_succ.mpr hq)

theorem splitOn_of_none {d l : List α} (h : firstOcc d l = none) : splitOn d l = ([], l) := by
  rw [splitOn]; split <;> simp_all

theorem splitOn_of_some {d l : List α} {p : Nat} (h : firstOcc d l = some p) :
    splitOn d l =
      (l.take p :: (splitOn d (l.drop (p + d.length))).1,
        (splitOn d (l.drop (p + d.length))).2) := by
  rw [splitOn]; split <;> simp_all

theorem splitOn_nil {d : List α} : splitOn d [] = ([], []) :=
  splitOn_of_none (by simp [firstOcc])

/-- The first occurrence in `x` is the first in `x ++ y`. -/
theorem firstOcc_append_of_some {d x y : List α} {p : Nat} (h : firstOcc d x = some p) :
    firstOcc d (x ++ y) = some p := by
  have hd := (firstOcc_some_bound h).1
  obtain ⟨hocc, hmin⟩ := (firstOcc_eq_some_iff hd).mp h
  have hlen := occursAt_length hd hocc
  refine (firstOcc_eq_some_iff hd).mpr ⟨occursAt_append_of_occursAt hd hocc, fun q hq hq' => ?_⟩
  exact hmin q hq ((occursAt_append_iff_of_le (by omega)).mp hq')

/-- **Splitting is compositional**: split `x`, then split its rest followed by `y`. -/
theorem splitOn_append (d x y : List α) :
    splitOn d (x ++ y) =
      ((splitOn d x).1 ++ (splitOn d ((splitOn d x).2 ++ y)).1,
        (splitOn d ((splitOn d x).2 ++ y)).2) := by
  fun_induction splitOn d x with
  | case1 x h => simp
  | case2 x p h rest ih =>
    have hb := firstOcc_some_bound h
    rw [splitOn_of_some (firstOcc_append_of_some h), List.drop_append_of_le_length hb.2,
      ih, List.take_append_of_le_length (by omega)]
    simp [rest]

/-- The rest of a split contains no occurrence. -/
theorem firstOcc_splitOn_rest (d l : List α) : firstOcc d (splitOn d l).2 = none := by
  fun_induction splitOn d l with
  | case1 l h => simpa [splitOn_of_none h] using h
  | case2 l p h rest ih => simpa [splitOn_of_some h] using ih

theorem splitOn_rest_rest (d l : List α) : splitOn d (splitOn d l).2 = ([], (splitOn d l).2) :=
  splitOn_of_none (firstOcc_splitOn_rest d l)

/-- A rest without an occurrence is shorter than the first frame found after it, plus the
delimiter. -/
theorem length_lt_of_firstOcc_append {d r y : List α} {q : Nat}
    (hr : firstOcc d r = none) (h : firstOcc d (r ++ y) = some q) :
    r.length < q + d.length := by
  have hd := (firstOcc_some_bound h).1
  have hocc := ((firstOcc_eq_some_iff hd).mp h).1
  have hnone := (firstOcc_eq_none_iff hd).mp hr
  apply Nat.lt_of_not_le
  intro hc
  exact hnone q ((occursAt_append_iff_of_le hc).mp hocc)

/-- Every frame of a split is a frame of the split of a longer input. -/
theorem splitOn_frames_prefix (d x y : List α) :
    (splitOn d x).1 <+: (splitOn d (x ++ y)).1 := by
  rw [splitOn_append]; exact List.prefix_append _ _

end WsClientProofs.Framing
