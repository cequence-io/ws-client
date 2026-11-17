/-!
# `String.replace`, functionally

Java's `String.replace(CharSequence, CharSequence)` on texts (`List Char`), with the lemmas
the models that call it share.
-/

namespace WsClientProofs.Java

set_option linter.unusedVariables false in
/-- `String.replace(target, replacement)`: every occurrence of `target`, left to right, not
overlapping (for a non-empty `target`). -/
def replace (target replacement : List Char) : List Char → List Char
  | [] => []
  | c :: cs =>
    if h : target ≠ [] ∧ target.isPrefixOf (c :: cs) then
      replacement ++ replace target replacement ((c :: cs).drop target.length)
    else c :: replace target replacement cs
termination_by l => l.length
decreasing_by
  · have := List.length_pos_iff.mpr h.1
    simp only [List.length_drop, List.length_cons]
    omega
  · simp

theorem flatMap_congr' {α β : Type} {l : List α} {f g : α → List β}
    (h : ∀ x ∈ l, f x = g x) : l.flatMap f = l.flatMap g := by
  induction l with
  | nil => rfl
  | cons x xs ih => simp [List.flatMap_cons, h x (by simp), ih (fun y hy => h y (by simp [hy]))]


theorem replace_nil (t r : List Char) : replace t r [] = [] := by
  rw [replace]

theorem replace_cons (t r : List Char) (c : Char) (cs : List Char) :
    replace t r (c :: cs) =
      if t ≠ [] ∧ t.isPrefixOf (c :: cs) = true then r ++ replace t r ((c :: cs).drop t.length)
      else c :: replace t r cs := by
  rw [replace]; simp only [dite_eq_ite]

/-- Replacing a single character is a map over the characters. -/
theorem replace_single (t : Char) (r l : List Char) :
    replace [t] r l = l.flatMap fun x => if x = t then r else [x] := by
  induction l with
  | nil => simp [replace_nil]
  | cons x xs ih =>
    rw [replace_cons]
    by_cases hx : x = t
    · subst hx
      rw [if_pos (by simp [List.isPrefixOf])]
      simp [ih]
    · rw [if_neg (by simp [List.isPrefixOf]; exact fun h => hx h.symm)]
      simp [ih, hx]

theorem replace_cons_of_ne {t0 c : Char} {t r cs : List Char} (h : t0 ≠ c) :
    replace (t0 :: t) r (c :: cs) = c :: replace (t0 :: t) r cs := by
  rw [replace_cons, if_neg]
  simp [List.isPrefixOf, h]

end WsClientProofs.Java
