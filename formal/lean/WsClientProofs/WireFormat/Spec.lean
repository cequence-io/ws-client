/-!
# Query encoding: the specification

From RFC 3986 alone:

* the encoding of a query key or value (`rfc3986Encode`): the bytes of its UTF-8 form, each
  unreserved byte as its character, every other byte as `%XX` (section 2.1 - upper-case hex,
  which the RFC recommends - and section 2.3);
* a strict reader of the query of a URL (`readQuery`): the part after the first `?` up to a
  `#` (section 3.4), split into `&`-separated fields - empty ones skipped, as the WHATWG URL
  parser does - each split at its first `=`, both sides percent-decoded. A `+` stays a `+`
  (it means a space only in HTML form encoding).

Texts are `List Char` (Lean's `Char` is a Unicode scalar value: a Java string with an
unpaired surrogate is not a text here). Bytes are naturals.
-/

namespace WsClientProofs.WireFormat

/-- The UTF-8 bytes of a text (Lean's `String.utf8EncodeChar`). -/
def utf8 (s : List Char) : List Nat :=
  s.flatMap fun c => (String.utf8EncodeChar c).map (·.toNat)

/-- RFC 3986, 2.3: `unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"`, as bytes. -/
def unreserved (b : Nat) : Bool :=
  (0x41 ≤ b && b ≤ 0x5A) || (0x61 ≤ b && b ≤ 0x7A) || (0x30 ≤ b && b ≤ 0x39) ||
    b == 0x2D || b == 0x2E || b == 0x5F || b == 0x7E

/-- An upper-case hex digit, for `n < 16`. -/
def hexDigit (n : Nat) : Char :=
  if n < 10 then Char.ofNat (0x30 + n) else Char.ofNat (0x41 + n - 10)

/-- RFC 3986, 2.1: `pct-encoded = "%" HEXDIG HEXDIG`, for a byte `b < 256`. -/
def pct (b : Nat) : List Char := ['%', hexDigit (b / 16), hexDigit (b % 16)]

/-- **The scaladoc model** of `WireFormat.encodeQueryComponent`: "RFC 3986 percent-encoding
of a query key or value: every byte of its UTF-8 form but the unreserved characters". -/
def rfc3986Encode (s : List Char) : List Char :=
  (utf8 s).flatMap fun b => if unreserved b then [Char.ofNat b] else pct b

/-- The value of a hex digit, either case. -/
def hexValue? (c : Char) : Option Nat :=
  if '0' ≤ c ∧ c ≤ '9' then some (c.toNat - 0x30)
  else if 'A' ≤ c ∧ c ≤ 'F' then some (c.toNat - 0x41 + 10)
  else if 'a' ≤ c ∧ c ≤ 'f' then some (c.toNat - 0x61 + 10)
  else none

/-- Percent-decoding (RFC 3986, 2.1): `%XX` is the byte `XX`, any other character its UTF-8
bytes; a `%` without two hex digits after it is malformed. -/
def percentDecode : List Char → Option (List Nat)
  | [] => some []
  | c :: rest =>
    if c = '%' then
      match rest with
      | h :: l :: rest' => do
        let a ← hexValue? h
        let b ← hexValue? l
        let tail ← percentDecode rest'
        pure ((16 * a + b) :: tail)
      | _ => none
    else do
      let tail ← percentDecode rest
      pure (utf8 [c] ++ tail)

/-- The query of a URL (RFC 3986, 3.4): after the first `?`, up to a `#`. -/
def queryOf (url : List Char) : List Char :=
  ((url.dropWhile (· ≠ '?')).drop 1).takeWhile (· ≠ '#')

/-- Splits at every `sep`. -/
def splitOnChar (sep : Char) : List Char → List (List Char)
  | [] => [[]]
  | c :: cs =>
    if c = sep then [] :: splitOnChar sep cs
    else
      match splitOnChar sep cs with
      | f :: fs => (c :: f) :: fs
      | [] => [[c]]

/-- A field split at its first `=` (no `=`: a key with an empty value). -/
def splitField (field : List Char) : List Char × List Char :=
  (field.takeWhile (· ≠ '='), (field.dropWhile (· ≠ '=')).drop 1)

/-- One field, percent-decoded. -/
def readField (field : List Char) : Option (List Nat × List Nat) := do
  let k ← percentDecode (splitField field).1
  let v ← percentDecode (splitField field).2
  pure (k, v)

/-- **The strict reader**: the parameters of a URL's query, as bytes. -/
def readQuery (url : List Char) : Option (List (List Nat × List Nat)) :=
  ((splitOnChar '&' (queryOf url)).filter (fun f => !f.isEmpty)).mapM readField

end WsClientProofs.WireFormat
