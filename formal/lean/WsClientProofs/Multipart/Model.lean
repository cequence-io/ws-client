import WsClientProofs.JavaString
import WsClientProofs.Multipart.Spec

/-!
# Model: `MultipartParams`

`ws-client-core/src/main/scala/io/cequence/wsclient/service/ws/MultipartFormData.scala`:

```scala
def escape(value: String): String =
  value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

def quoted(value: String): String = "\"" + escape(value) + "\""

def dataPartName(name: String): String =
  if (name.nonEmpty && name.forall(isTokenChar)) name else quoted(name)

private def isTokenChar(c: Char): Boolean =
  (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
    "!#$%&'*+-.^_`|~".indexOf(c) >= 0
```

`FilePart.name` / `filenamePart` are `quoted`; the hand-built bodies write
`form-data; name=<dataPartName or quoted>` and `; filename=<quoted>`.
-/

namespace WsClientProofs.Multipart

open WsClientProofs.Java

def escape (value : List Char) : List Char :=
  replace ['\n'] ['%', '0', 'A'] (replace ['\r'] ['%', '0', 'D'] (replace ['"'] ['%', '2', '2'] value))

def quoted (value : List Char) : List Char := '"' :: escape value ++ ['"']

def isTokenChar (c : Char) : Bool :=
  (c ≥ 'a' && c ≤ 'z') || (c ≥ 'A' && c ≤ 'Z') || (c ≥ '0' && c ≤ '9') ||
    "!#$%&'*+-.^_`|~".toList.contains c

def dataPartName (name : List Char) : List Char :=
  if !name.isEmpty && name.all isTokenChar then name else quoted name

end WsClientProofs.Multipart
