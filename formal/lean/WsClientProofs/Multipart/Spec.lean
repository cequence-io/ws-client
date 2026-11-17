/-!
# Multipart parameter values: the specification

From the scaladoc of `MultipartParams` and the standards it names:

* the encoding (`escapeSpec`): the WHATWG "multipart/form-data encoding algorithm" / RFC 7578
  4.2 - `"`, CR and LF become `%22`, `%0D` and `%0A`, every other character stays;
* a token (RFC 9110 5.6.2, `tchar`);
* two readers of a `Content-Disposition` parameter value - a token, or a quoted value:
  - `readValue`: as the WHATWG encoding is read - a quoted value runs to the next `"` (the
    encoding has no escapes: it leaves no `"` inside);
  - `readValueRfc9110`: an RFC 9110 5.6.4 `quoted-string`, where `\` escapes the next
    character.

Both readers return the value and the rest of the header after it.
-/

namespace WsClientProofs.Multipart

/-- WHATWG / RFC 7578 4.2: `"`, CR, LF become `%22`, `%0D`, `%0A`. -/
def escapeSpec (s : List Char) : List Char :=
  s.flatMap fun c =>
    if c = '"' then ['%', '2', '2']
    else if c = '\r' then ['%', '0', 'D']
    else if c = '\n' then ['%', '0', 'A']
    else [c]

/-- RFC 9110 5.6.2: `tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." / "^" /
"_" / "`" / "|" / "~" / DIGIT / ALPHA`. -/
def tchar (c : Char) : Bool :=
  ('a' ≤ c && c ≤ 'z') || ('A' ≤ c && c ≤ 'Z') || ('0' ≤ c && c ≤ '9') ||
    "!#$%&'*+-.^_`|~".toList.contains c

/-- A quoted value up to the next `"` (after the opening one). -/
def readQuoted : List Char → Option (List Char × List Char)
  | [] => none
  | c :: rest =>
    if c = '"' then some ([], rest)
    else (readQuoted rest).map fun r => (c :: r.1, r.2)

/-- A parameter value as the WHATWG encoding is read: quoted, or a non-empty token. -/
def readValue (header : List Char) : Option (List Char × List Char) :=
  match header with
  | c :: rest => if c = '"' then readQuoted rest else
      let v := header.takeWhile tchar
      if v.isEmpty then none else some (v, header.drop v.length)
  | [] => none

/-- An RFC 9110 `quoted-string` body: `\` escapes the next character, `"` ends it. -/
def readQuotedRfc9110 : List Char → Option (List Char × List Char)
  | [] => none
  | c :: rest =>
    if c = '"' then some ([], rest)
    else if c = '\\' then
      match rest with
      | d :: rest' => (readQuotedRfc9110 rest').map fun r => (d :: r.1, r.2)
      | [] => none
    else (readQuotedRfc9110 rest).map fun r => (c :: r.1, r.2)

end WsClientProofs.Multipart
