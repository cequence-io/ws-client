import WsClientProofs.Framing.Spec

/-!
# Model: the delimiter search loop

The two nested loops of `indexOfDelimiter`, which both framers - `ByteFraming.DelimiterFramer`
and `StreamTransformers.DelimiterFramer` - contain verbatim (with `limit = end -
delimiter.length` and `limit = buffer.length - delimiter.length` respectively):

```scala
while (i <= limit) {
  var j = 0
  while (j < delimiter.length && buffer(i + j) == delimiter(j)) j += 1
  if (j == delimiter.length) return i
  i += 1
}
```
-/

namespace WsClientProofs.Framing

variable {α : Type} [DecidableEq α]

/-- The inner loop:
`while (j < delimiter.length && buffer(i + j) == delimiter(j)) j += 1`. -/
def matchLength (delimiter buffer : List α) (i j : Nat) : Nat :=
  if j < delimiter.length ∧ buffer[i + j]? = delimiter[j]? then
    matchLength delimiter buffer i (j + 1)
  else j
termination_by delimiter.length - j

/-- The outer loop: `while (i <= limit)` with
`limit = end - delimiter.length` - an `Int`, negative while fewer bytes than the delimiter
are buffered, so over naturals the condition is `i + delimiter.length ≤ end`. -/
def scan (delimiter buffer : List α) (end_ i : Nat) : Option Nat :=
  if i + delimiter.length ≤ end_ then
    if matchLength delimiter buffer i 0 = delimiter.length then some i
    else scan delimiter buffer end_ (i + 1)
  else none
termination_by end_ + 1 - i

theorem matchLength_le (delimiter buffer : List α) (i j : Nat) :
    j ≤ matchLength delimiter buffer i j := by
  fun_induction matchLength delimiter buffer i j <;> omega

theorem scan_some_bounds {delimiter buffer : List α} {end_ i j : Nat}
    (h : scan delimiter buffer end_ i = some j) : i ≤ j ∧ j + delimiter.length ≤ end_ := by
  fun_induction scan delimiter buffer end_ i with
  | case1 i hle hm => simp at h; omega
  | case2 i hle hm ih => have := ih h; omega
  | case3 i hle => simp at h

end WsClientProofs.Framing
