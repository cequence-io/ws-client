import WsClientProofs.Framing.Spec
import WsClientProofs.JavaString

/-!
# The Java / Scala library calls the extracted code makes

`sbt leanExtract` translates production Scala (`ProductionExtract`); a call into the Java or
Scala library becomes a call of one of the functions below (or of `Java.replace`,
`WireFormat.urlEncoderEncode`). They are the trusted base of the extraction: each is written
from the library's documentation, and the `lib` target of `sbt leanConformance` runs every
one against the JVM on random texts.

Texts are `List Char` (Unicode scalar values; a Java string with an unpaired surrogate is
outside the model). `Int` results are Lean's unbounded `Int`; an index argument is in bounds
(Java throws otherwise - the theorems about the extracted code say where that matters).
-/

namespace WsClientProofs.Scala

open WsClientProofs.Framing (firstOcc)

/-- `String.startsWith(prefix)`. -/
def startsWith (s p : List Char) : Bool := p.isPrefixOf s

/-- `StringOps.stripPrefix(prefix)`: "Returns this string with the given prefix removed, if it
starts with that prefix, or else the original string." -/
def stripPrefix (s p : List Char) : List Char := if startsWith s p then s.drop p.length else s

/-- `StringOps.stripSuffix(suffix)`. -/
def stripSuffix (s p : List Char) : List Char :=
  if startsWith s.reverse p.reverse then s.take (s.length - p.length) else s

/-- `String.trim()`: "Returns a string whose value is this string, with all leading and
trailing space removed, where space is defined as any character whose codepoint is less than
or equal to 'U+0020'". -/
def trim (s : List Char) : List Char :=
  ((s.dropWhile (· ≤ ' ')).reverse.dropWhile (· ≤ ' ')).reverse

/-- `String.indexOf(String)`: the index of the first occurrence (the empty string occurs at
0), or -1. -/
def indexOfText (s t : List Char) : Int :=
  if t = [] then 0 else
    match firstOcc t s with
    | some i => i
    | none => -1

/-- `String.contains(CharSequence)`: "true if and only if this string contains the specified
sequence" - `indexOf(s) > -1`, as the JDK defines it. -/
def containsText (s t : List Char) : Bool := decide (indexOfText s t > -1)

theorem firstOcc_single_isSome (c : Char) (l : List Char) :
    (firstOcc [c] l).isSome = true ↔ c ∈ l := by
  induction l with
  | nil => simp [firstOcc]
  | cons x xs ih =>
    by_cases h : c = x
    · subst h; simp [firstOcc, List.isPrefixOf]
    · simp [firstOcc, List.isPrefixOf, h, ih]

/-- `contains` of a one-character text is membership. -/
theorem containsText_single (s : List Char) (c : Char) : containsText s [c] = s.contains c := by
  have h := firstOcc_single_isSome c s
  have hne : ([c] : List Char) ≠ [] := by simp
  by_cases hm : c ∈ s
  · obtain ⟨i, hi⟩ := Option.isSome_iff_exists.mp (h.mpr hm)
    simp [containsText, indexOfText, hne, hi, hm]
    omega
  · have hn : firstOcc [c] s = none := by
      cases hf : firstOcc [c] s with
      | none => rfl
      | some i => exact absurd (h.mp (by simp [hf])) hm
    simp [containsText, indexOfText, hne, hn, hm]

/-- `String.indexOf(char)`: the index of the first occurrence, or -1. -/
def indexOfChar (s : List Char) (c : Char) : Int :=
  match s with
  | [] => -1
  | x :: xs =>
    if x = c then 0
    else if indexOfChar xs c < 0 then -1 else indexOfChar xs c + 1

theorem indexOfChar_nonneg_iff (s : List Char) (c : Char) : 0 ≤ indexOfChar s c ↔ c ∈ s := by
  induction s with
  | nil => simp [indexOfChar]
  | cons x xs ih =>
    simp only [indexOfChar]
    by_cases hx : x = c
    · simp [hx]
    · by_cases hr : indexOfChar xs c < 0
      · have : c ∉ xs := fun h => by have := ih.mpr h; omega
        simp [hx, hr, this, Ne.symm hx]
      · have : c ∈ xs := ih.mp (by omega)
        simp [hx, hr, this]
        omega

/-- `indexOf(c) >= 0` is membership. -/
theorem decide_indexOfChar (s : List Char) (c : Char) :
    decide (indexOfChar s c ≥ 0) = s.contains c := by
  by_cases h : c ∈ s
  · simp [(indexOfChar_nonneg_iff s c).mpr h, h]
  · simp [h, mt (indexOfChar_nonneg_iff s c).mp h]

/-- `String.substring(beginIndex)`, for `0 ≤ beginIndex ≤ length`. -/
def substring (s : List Char) (i : Int) : List Char := s.drop i.toNat

/-- `array(i)`: the element, or `none` out of bounds - where Java throws (the extracted code
only reads in bounds: the proofs show it). -/
def arrayGet {α : Type} (a : List α) (i : Int) : Option α :=
  if i < 0 then none else a[i.toNat]?

/-- `mkString(sep)` of a list of texts. -/
def mkString (sep : List Char) : List (List Char) → List Char
  | [] => []
  | [x] => x
  | x :: xs => x ++ sep ++ mkString sep xs

/-- The one regular expression the extracted code compiles: `Pattern.compile("\r\n|\r|\n")`,
the SSE line break. `ProductionExtract` rejects any other pattern. -/
inductive Pattern where
  | lineBreak
  deriving DecidableEq, Repr

/-- `Pattern.split(text, -1)` for the line break pattern: "If the limit is negative then the
pattern will be applied as many times as possible and the array can have any length" - every
line, the trailing empty one included, CRLF taken as ONE break (the alternation tries
`\r\n` first). -/
def splitLines : List Char → List (List Char)
  | [] => [[]]
  | '\r' :: '\n' :: rest => [] :: splitLines rest
  | '\r' :: rest => [] :: splitLines rest
  | '\n' :: rest => [] :: splitLines rest
  | c :: rest =>
    match splitLines rest with
    | l :: ls => (c :: l) :: ls
    | [] => [[c]]

def Pattern.split : Pattern → List Char → List (List Char)
  | .lineBreak, s => splitLines s

/-- The JSON value `Json.parse` returns: the parse itself is not modelled, a parsed value is
the text it came from (so what the extracted code hands to the parser is what a theorem
sees). -/
inductive JsValue where
  | parsed (text : List Char)
  deriving DecidableEq, Repr

end WsClientProofs.Scala
