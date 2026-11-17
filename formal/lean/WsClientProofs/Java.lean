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

end WsClientProofs.Java
