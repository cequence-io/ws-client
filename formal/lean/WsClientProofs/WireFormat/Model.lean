import WsClientProofs.WireFormat.Spec
import WsClientProofs.JavaString

/-!
# Model: `WireFormat.encodeQueryComponent` and `WireFormat.url`

`ws-client-core/src/main/scala/io/cequence/wsclient/service/ws/WireFormat.scala`:

```scala
def encodeQueryComponent(value: String): String =
  URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

def url(base: String, params: Seq[(String, Option[Any])]): String = {
  val query = params.collect { case (key, Some(value)) =>
    s"${encodeQueryComponent(key)}=${encodeQueryComponent(value.toString)}"
  }.mkString("&")
  if (query.isEmpty) base else if (base.contains("?")) s"$base&$query" else s"$base?$query"
}
```

`URLEncoder.encode` is JDK code, modelled from its javadoc (`urlEncoderEncode`); the
conformance check runs it against the JDK. The values are texts (`value.toString`).
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

/-- `WireFormat.encodeQueryComponent`. -/
def encodeQueryComponent (value : List Char) : List Char :=
  replace ['%', '7', 'E'] ['~']
    (replace ['*'] ['%', '2', 'A'] (replace ['+'] ['%', '2', '0'] (urlEncoderEncode value)))

/-- `mkString(sep)`. -/
def mkString (sep : List Char) : List (List Char) → List Char
  | [] => []
  | [x] => x
  | x :: xs => x ++ sep ++ mkString sep xs

/-- `WireFormat.url`. -/
def url (base : List Char) (params : List (List Char × Option (List Char))) : List Char :=
  let query := mkString ['&'] (params.filterMap fun p =>
    p.2.map fun value => encodeQueryComponent p.1 ++ '=' :: encodeQueryComponent value)
  if query.isEmpty then base
  else if base.contains '?' then base ++ '&' :: query
  else base ++ '?' :: query

end WsClientProofs.WireFormat
