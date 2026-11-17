import WsClientProofs.Java
import WsClientProofs.Framing.Scan
import WsClientProofs.Framing.SplitLemmas

/-!
# The delimiter search loop finds the leftmost occurrence

Plus the coordinate lemmas both framer proofs need: the pending bytes are a range of the
buffer, so an occurrence at an offset of the range is one at that index of the buffer.
-/

namespace WsClientProofs.Framing

open WsClientProofs.Java

set_option linter.unusedSectionVars false

variable {α : Type} [DecidableEq α]

theorem occursAt_iff_getElem? {d l : List α} {i : Nat} :
    OccursAt d l i ↔ ∀ k < d.length, l[i + k]? = d[k]? := by
  simp only [OccursAt, List.prefix_iff_eq_take]
  constructor
  · intro h k hk
    have := congrArg (fun x => x[k]?) h
    simp only [List.getElem?_take, hk, if_true, List.getElem?_drop] at this
    exact this.symm
  · intro h
    apply List.ext_getElem?
    intro k
    rw [List.getElem?_take, List.getElem?_drop]
    split
    · rename_i hk; exact (h k hk).symm
    · rename_i hk; exact List.getElem?_eq_none (by omega)

theorem matchLength_eq_length_iff {d buf : List α} {i j : Nat} (hj : j ≤ d.length) :
    matchLength d buf i j = d.length ↔ ∀ k, j ≤ k → k < d.length → buf[i + k]? = d[k]? := by
  fun_induction matchLength d buf i j with
  | case1 j hcond ih =>
    rw [ih (by omega)]
    constructor
    · intro h k hjk hk
      rcases Nat.eq_or_lt_of_le hjk with rfl | hlt
      · exact hcond.2
      · exact h k hlt hk
    · intro h k hjk hk
      exact h k (by omega) hk
  | case2 j hcond =>
    constructor
    · intro h k hjk hk
      omega
    · intro h
      by_cases hlt : j < d.length
      · exact absurd ⟨hlt, h j (Nat.le_refl _) hlt⟩ hcond
      · omega

theorem matchLength_zero_iff {d buf : List α} {i : Nat} :
    matchLength d buf i 0 = d.length ↔ OccursAt d buf i := by
  rw [matchLength_eq_length_iff (Nat.zero_le _), occursAt_iff_getElem?]
  simp

/-- A found index is the least occurrence at or after the start of the scan. -/
theorem scan_eq_some {d buf : List α} {e i j : Nat} (h : scan d buf e i = some j) :
    i ≤ j ∧ j + d.length ≤ e ∧ OccursAt d buf j ∧
      ∀ k, i ≤ k → k < j → ¬ OccursAt d buf k := by
  fun_induction scan d buf e i with
  | case1 i hle hm =>
    simp only [Option.some.injEq] at h
    subst h
    exact ⟨Nat.le_refl _, hle, matchLength_zero_iff.mp hm, fun k h1 h2 => by omega⟩
  | case2 i hle hm ih =>
    obtain ⟨h1, h2, h3, h4⟩ := ih h
    refine ⟨by omega, h2, h3, fun k hk1 hk2 => ?_⟩
    rcases Nat.eq_or_lt_of_le hk1 with rfl | hlt
    · exact fun hk => hm (matchLength_zero_iff.mpr hk)
    · exact h4 k hlt hk2
  | case3 i hle => simp at h

/-- No index found: no occurrence that fits before `e` starts at or after the scan's start. -/
theorem scan_eq_none {d buf : List α} {e i : Nat} (h : scan d buf e i = none) :
    ∀ k, i ≤ k → k + d.length ≤ e → ¬ OccursAt d buf k := by
  fun_induction scan d buf e i with
  | case1 i hle hm => simp at h
  | case2 i hle hm ih =>
    intro k hk1 hk2
    rcases Nat.eq_or_lt_of_le hk1 with rfl | hlt
    · exact fun hk => hm (matchLength_zero_iff.mpr hk)
    · exact ih h k hlt hk2
  | case3 i hle =>
    intro k hk1 hk2
    omega

theorem copyOfRange_eq_drop_take {a : List α} {i e : Nat} (h : i ≤ e) :
    copyOfRange a i e = (a.take e).drop i := by
  simp only [copyOfRange, List.take_drop]
  congr 2; omega

theorem drop_copyOfRange {a : List α} {i e k : Nat} :
    (copyOfRange a i e).drop k = copyOfRange a (i + k) e := by
  simp only [copyOfRange, List.drop_take, List.drop_drop]
  congr 1; omega

theorem take_copyOfRange {a : List α} {i e k : Nat} (h : i + k ≤ e) :
    (copyOfRange a i e).take k = copyOfRange a i (i + k) := by
  simp only [copyOfRange, List.take_take]
  congr 1; omega

/-- An occurrence at offset `q` of the range `[a, e)` of a buffer is one at `a + q`. -/
theorem occursAt_copyOfRange {d buf : List α} {a e q : Nat} (hq : q + d.length ≤ e - a) :
    OccursAt d (copyOfRange buf a e) q ↔ OccursAt d buf (a + q) := by
  simp only [OccursAt, copyOfRange, List.drop_take, List.drop_drop]
  exact prefix_take_iff_of_length_le (by omega)

end WsClientProofs.Framing
