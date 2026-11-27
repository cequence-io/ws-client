import WsClientProofs.WireFormat.Spec
import WsClientProofs.JavaString

/-!
# `java.net.URLEncoder.encode(s, "UTF-8")`

The JDK's form encoder, modelled from its javadoc - the one library function
`WireFormat.encodeQueryComponent` builds on (the extraction maps the call to
`urlEncoderEncode`). `sbt leanConformance` runs it against the JDK (the `lib` and `url`
targets).
-/

namespace WsClientProofs.WireFormat

open WsClientProofs.Java

/-- The characters `URLEncoder` leaves as they are: "The alphanumeric characters "a" through
"z", "A" through "Z" and "0" through "9" remain the same. The special characters ".", "-",
"*", and "_" remain the same." -/
def javaSafe (c : Char) : Bool :=
  ('a' ≤ c && c ≤ 'z') || ('A' ≤ c && c ≤ 'Z') || ('0' ≤ c && c ≤ '9') ||
    c == '.' || c == '-' || c == '*' || c == '_'

/-- `URLEncoder.encode(s, "UTF-8")` per character: "The space character " " is converted into
a plus sign "+". All other characters are unsafe and are first converted into one or more
bytes using some encoding scheme. Then each byte is represented by the 3-character string
"%xy"" - the bytes of the character's UTF-8 form, with upper-case hex digits (as the JDK
writes them). The JDK encodes a run of unsafe characters at once; UTF-8 encodes characters
independently, so for a text that is the concatenation per character. -/
def urlEncoderChar (c : Char) : List Char :=
  if javaSafe c then [c] else if c = ' ' then ['+'] else (utf8 [c]).flatMap pct

def urlEncoderEncode (s : List Char) : List Char := s.flatMap urlEncoderChar

end WsClientProofs.WireFormat
