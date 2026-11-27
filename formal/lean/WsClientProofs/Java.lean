/-!
# Java array primitives, functionally

The models in this project transliterate Scala code that works on mutable `Array[Byte]`s. An
array is modelled as a `List`, and each Java primitive as the function that returns the array
it produces. Every definition states the Java semantics it stands for; the models only call
them in bounds (the proofs show it), so out-of-bounds behavior (Java throws) is not modelled.
-/

namespace WsClientProofs.Java

variable {α : Type}

/-- `java.util.Arrays.copyOfRange(a, fromIndex, toIndex)`, for
`fromIndex ≤ toIndex ≤ a.length`. -/
def copyOfRange (a : List α) (fromIndex toIndex : Nat) : List α :=
  (a.drop fromIndex).take (toIndex - fromIndex)

/-- `System.arraycopy(src, srcPos, dest, destPos, length)`: the new contents of `dest`, for
`srcPos + length ≤ src.length` and `destPos + length ≤ dest.length`. When `src` and `dest`
are the same array, Java copies as if through a temporary array - which this function does
by construction. -/
def arraycopy (src : List α) (srcPos : Nat) (dest : List α) (destPos length : Nat) :
    List α :=
  dest.take destPos ++ (src.drop srcPos).take length ++ dest.drop (destPos + length)

/-- `java.util.Arrays.copyOf(a, newLength)`: the first `newLength` elements, padded with the
default value (`0` for bytes). -/
def copyOf [Inhabited α] (a : List α) (newLength : Nat) : List α :=
  a.take newLength ++ List.replicate (newLength - a.length) default

/-- `Int.MaxValue`. -/
def intMax : Nat := 2147483647

theorem length_copyOfRange {a : List α} {i j : Nat} (h : j ≤ a.length) :
    (copyOfRange a i j).length = j - i := by
  simp [copyOfRange]; omega

theorem length_arraycopy {src dest : List α} {srcPos destPos length : Nat}
    (hs : srcPos + length ≤ src.length) (hd : destPos + length ≤ dest.length) :
    (arraycopy src srcPos dest destPos length).length = dest.length := by
  simp [arraycopy]; omega

theorem length_copyOf [Inhabited α] {a : List α} {n : Nat} : (copyOf a n).length = n := by
  simp [copyOf]; omega

/-- The prefix that `copyOf` keeps. -/
theorem take_copyOf [Inhabited α] {a : List α} {n k : Nat} (hk : k ≤ a.length) (hn : k ≤ n) :
    (copyOf a n).take k = a.take k := by
  simp only [copyOf]
  rw [List.take_append_of_le_length (by simp; omega), List.take_take]
  congr 1; omega

/-! ## The operations that throw

The checked extraction (`ClassExtract`, `checked = true`) makes every array operation that can
throw in Java a step of `Except`; these are those operations, from the javadoc of
`java.util.Arrays` / `System`, run against the JVM by the `lib` conformance target. -/

/-- The Java exceptions these operations throw. -/
inductive Throw where
  | indexOutOfBounds
  | illegalArgument
  | negativeSize
  deriving DecidableEq, Repr

/-- `a(i)`: "ArrayIndexOutOfBoundsException" unless `0 ≤ i < a.length`. -/
def readChecked [Inhabited α] (a : List α) (i : Int) : Except Throw α :=
  if 0 ≤ i ∧ i < a.length then .ok (a.getD i.toNat default) else .error .indexOutOfBounds

/-- `Arrays.copyOfRange(a, from, to)`: "ArrayIndexOutOfBoundsException - if from < 0 or from >
original.length; IllegalArgumentException - if from > to"; the copy is padded with the default
value where `to` is past the end. The JDK computes `to - from` first, so `from > to` is
reported before a bad `from` (the conformance run against the JVM found the order; the
arguments are `int`s with `to - from` in range - at every call site `from ≤ to`). -/
def copyOfRangeChecked [Inhabited α] (a : List α) (f t : Int) : Except Throw (List α) :=
  if f > t then .error .illegalArgument
  else if f < 0 ∨ f > a.length then .error .indexOutOfBounds
  else
    .ok ((a.drop f.toNat).take (t - f).toNat ++
      List.replicate ((t - f).toNat - (a.length - f.toNat)) default)

/-- `Arrays.copyOf(a, n)`: "NegativeArraySizeException - if newLength is negative". -/
def copyOfChecked [Inhabited α] (a : List α) (n : Int) : Except Throw (List α) :=
  if n < 0 then .error .negativeSize else .ok (copyOf a n.toNat)

/-- `new Array[...](n)`: "NegativeArraySizeException". -/
def newArrayChecked [Inhabited α] (n : Int) : Except Throw (List α) :=
  if n < 0 then .error .negativeSize else .ok (List.replicate n.toNat default)

/-- `System.arraycopy(src, srcPos, dest, destPos, length)`: "IndexOutOfBoundsException" when a
position or the length is negative or a range ends past an array; the new contents of `dest`
otherwise. -/
def arraycopyChecked (src : List α) (sp : Int) (dst : List α) (dp len : Int) :
    Except Throw (List α) :=
  if sp < 0 ∨ dp < 0 ∨ len < 0 ∨ sp + len > src.length ∨ dp + len > dst.length then
    .error .indexOutOfBounds
  else .ok (arraycopy src sp.toNat dst dp.toNat len.toNat)

/-- `a ++ b` on arrays: the new array has `a.length + b.length` elements, an `int` size:
"NegativeArraySizeException" when the sum wraps (Java reports an array beyond the VM's limit as
an `OutOfMemoryError`, which no code of ours handles - the theorems bound the sizes far below). -/
def concatChecked (a b : List α) : Except Throw (List α) :=
  if a.length + b.length > 2147483647 then .error .negativeSize else .ok (a ++ b)

end WsClientProofs.Java
