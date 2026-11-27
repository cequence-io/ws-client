import WsClientProofs

/-!
# The conformance executable

Runs the Lean models on cases read from stdin, one case per line, and prints one result line
per case - the other half of `LeanConformance` (ws-client-verification), which runs the Scala
code on the same cases and compares. Byte strings are lower-case hex, `.` for an empty one.

* `framing <delimiter> <maxFrameLength> <allowTruncation 0|1> <n> <chunk_1> ... <chunk_n>`
  prints `<ByteFraming result> | <DelimiterFramer result>`, a result being
  `<ending> <frame count> <frame_1> ... <frame_k>`.
* `url <base> <n> <key_1> <value_1> ... <key_n> <value_n>` (texts as the hex of their UTF-8
  bytes, `-` for an undefined value) prints `WireFormat.url` as hex.
* `multipart <text>` prints `escape`, `quoted` and `dataPartName` of the text, as hex.
* `frame <frame> <itemPrefix> <stripPrefix> <stripSuffix>` (texts as hex, `-` for no
  stripping) prints `parseFrame`'s outcome: `skip`, `done` or `payload <hex>`.

A malformed line prints `error <reason>`.
-/

open WsClientProofs.Framing

def hexValue? (c : Char) : Option Nat :=
  if '0' ≤ c ∧ c ≤ '9' then some (c.toNat - '0'.toNat)
  else if 'a' ≤ c ∧ c ≤ 'f' then some (c.toNat - 'a'.toNat + 10)
  else none

def parseHex (s : String) : Except String (List UInt8) :=
  if s == "." then pure []
  else
    let rec go : List Char → Except String (List UInt8)
      | [] => pure []
      | hi :: lo :: rest => do
        let some h := hexValue? hi | throw s!"bad hex '{s}'"
        let some l := hexValue? lo | throw s!"bad hex '{s}'"
        return (h * 16 + l).toUInt8 :: (← go rest)
      | _ => throw s!"odd hex '{s}'"
    go s.toList

def hexChar (n : Nat) : Char :=
  if n < 10 then Char.ofNat ('0'.toNat + n) else Char.ofNat ('a'.toNat + n - 10)

def toHex (bytes : List UInt8) : String :=
  if bytes.isEmpty then "."
  else String.mk (bytes.flatMap fun b => [hexChar (b.toNat / 16), hexChar (b.toNat % 16)])

def endingName : WsClientProofs.Framing.ExtractedDrivers.End → String
  | .completed => "completed"
  | .tooLong => "tooLong"
  | .truncated => "truncated"
  | .outOfFuel => "outOfFuel"

def showResult (r : WsClientProofs.Framing.ExtractedDrivers.Result) : String :=
  " ".intercalate (endingName r.ending :: toString r.frames.length :: r.frames.map toHex)

def parseNat (s : String) : Except String Nat :=
  match s.toNat? with
  | some n => pure n
  | none => throw s!"bad number '{s}'"

def parseText (s : String) : Except String (List Char) := do
  let bytes ← parseHex s
  match String.fromUTF8? (ByteArray.mk bytes.toArray) with
  | some t => pure t.toList
  | none => throw s!"not UTF-8 '{s}'"

def textHex (t : List Char) : String := toHex (String.mk t).toUTF8.toList

def parseParams : List String → Except String (List (List Char × Option (List Char)))
  | [] => pure []
  | k :: v :: more => do
    let key ← parseText k
    let value ← if v == "-" then pure none else some <$> parseText v
    return (key, value) :: (← parseParams more)
  | _ => throw "url: a key without a value"

def urlCase : List String → Except String String
  | baseHex :: nS :: rest => do
    let base ← parseText baseHex
    let n ← parseNat nS
    let params ← parseParams rest
    if params.length != n then throw s!"expected {n} params, got {params.length}"
    return textHex (WsClientProofs.WireFormat.Extracted.url base params)
  | _ => throw "url: expected <base> <n> <params>"

def multipartCase : List String → Except String String
  | [textS] => do
    let t ← parseText textS
    let m := fun f => textHex (f t)
    return s!"{m WsClientProofs.Multipart.Extracted.escape} {m WsClientProofs.Multipart.Extracted.quoted} {m WsClientProofs.Multipart.Extracted.dataPartName}"
  | _ => throw "multipart: expected <text>"

def frameCase : List String → Except String String
  | [frameS, itemS, prefixS, suffixS] => do
    let frame ← parseText frameS
    let item ← parseText itemS
    let optional := fun (w : String) =>
      if w == "-" then pure none else some <$> parseText w
    let p ← optional prefixS
    let q ← optional suffixS
    return match WsClientProofs.SseFrames.Extracted.parseFrame frame p q item with
      | .Skip => "skip"
      | .EndOfStream => "done"
      | .JsonFrame (.parsed data) => s!"payload {textHex data}"
  | _ => throw "frame: expected <frame> <itemPrefix> <stripPrefix> <stripSuffix>"

def framing : List String → Except String String
  | dHex :: maxS :: tS :: nS :: rest => do
    let d ← parseHex dHex
    let max ← parseNat maxS
    let n ← parseNat nS
    let chunks ← rest.mapM parseHex
    if chunks.length != n then throw s!"expected {n} chunks, got {chunks.length}"
    if d = [] then throw "empty delimiter"
    else
      let byteFraming :=
        WsClientProofs.Framing.ExtractedDrivers.byteRun d (Int.ofNat max) (tS == "1") chunks
      let engine := WsClientProofs.Framing.ExtractedDrivers.engineRun d (Int.ofNat max) chunks
      return s!"{showResult byteFraming} | {showResult engine}"
  | _ => throw "framing: expected <delimiter> <max> <allowTruncation> <n> <chunks>"

def boolText (b : Bool) : String := if b then "true" else "false"

def parseInt (s : String) : Except String Int :=
  match s.toInt? with
  | some n => pure n
  | none => throw s!"bad integer '{s}'"

/-- A checked Java step: the value as the JVM prints it, or the failure's name. -/
def throwName : WsClientProofs.Java.Throw → String
  | .indexOutOfBounds => "indexOutOfBounds"
  | .illegalArgument => "illegalArgument"
  | .negativeSize => "negativeSize"

def showBytes (r : Except WsClientProofs.Java.Throw (List UInt8)) : String :=
  match r with
  | .ok a => toHex a
  | .error e => throwName e

/-- The JVM's exact `addExact` / `subtractExact` / `multiplyExact`: the checked extraction
(`i32` / `i64`) is `int32?` / `int64?` of the unbounded result. -/
def arith (op : String) (x y : Int) : Except String Int :=
  match op with
  | "add" => pure (x + y)
  | "sub" => pure (x - y)
  | "mul" => pure (x * y)
  | _ => throw s!"unknown operation '{op}'"

def showChecked (r : Option Int) : String :=
  match r with
  | some v => toString v
  | none => "overflow"

def libCase : List String → Except String String
  | ["trim", a] => do return textHex (WsClientProofs.Scala.trim (← parseText a))
  | ["startsWith", a, b] => do
    return boolText (WsClientProofs.Scala.startsWith (← parseText a) (← parseText b))
  | ["stripPrefix", a, b] => do
    return textHex (WsClientProofs.Scala.stripPrefix (← parseText a) (← parseText b))
  | ["stripSuffix", a, b] => do
    return textHex (WsClientProofs.Scala.stripSuffix (← parseText a) (← parseText b))
  | ["containsText", a, b] => do
    return boolText (WsClientProofs.Scala.containsText (← parseText a) (← parseText b))
  | ["indexOfText", a, b] => do
    return toString (WsClientProofs.Scala.indexOfText (← parseText a) (← parseText b))
  | ["indexOfChar", a, c] => do
    match (← parseText c) with
    | [ch] => return toString (WsClientProofs.Scala.indexOfChar (← parseText a) ch)
    | _ => throw "indexOfChar: expected one character"
  | ["substring", a, k] => do
    return textHex (WsClientProofs.Scala.substring (← parseText a) (Int.ofNat (← parseNat k)))
  | ["replace", a, t, r] => do
    return textHex (WsClientProofs.Java.replace (← parseText t) (← parseText r) (← parseText a))
  | ["splitLines", a] => do
    return " ".intercalate
      ((WsClientProofs.Scala.Pattern.split .lineBreak (← parseText a)).map textHex)
  | ["urlEncode", a] => do
    return textHex (WsClientProofs.WireFormat.urlEncoderEncode (← parseText a))
  | ["readChecked", a, i] => do
    let a ← parseHex a
    match WsClientProofs.Java.readChecked a (← parseInt i) with
    | .ok b => return toString b.toNat
    | .error e => return throwName e
  | ["copyOfRangeChecked", a, f, t] => do
    return showBytes (WsClientProofs.Java.copyOfRangeChecked (← parseHex a) (← parseInt f)
      (← parseInt t))
  | ["copyOfChecked", a, n] => do
    return showBytes (WsClientProofs.Java.copyOfChecked (← parseHex a) (← parseInt n))
  | ["newArrayChecked", n] => do
    return showBytes (WsClientProofs.Java.newArrayChecked (α := UInt8) (← parseInt n))
  | ["arraycopyChecked", src, sp, dst, dp, len] => do
    return showBytes (WsClientProofs.Java.arraycopyChecked (← parseHex src) (← parseInt sp)
      (← parseHex dst) (← parseInt dp) (← parseInt len))
  | ["concatChecked", a, b] => do
    return showBytes (WsClientProofs.Java.concatChecked (← parseHex a) (← parseHex b))
  | ["int32", op, x, y] => do
    return showChecked (WsClientProofs.Scala.int32? (← arith op (← parseInt x) (← parseInt y)))
  | ["int64", op, x, y] => do
    return showChecked (WsClientProofs.Scala.int64? (← arith op (← parseInt x) (← parseInt y)))
  | "mkString" :: sep :: n :: parts => do
    let parts ← parts.mapM parseText
    if parts.length != (← parseNat n) then throw "mkString: wrong part count"
    return textHex (WsClientProofs.Scala.mkString (← parseText sep) parts)
  | op :: _ => throw s!"lib: unknown function '{op}' or wrong arguments"
  | [] => throw "lib: expected a function"

def runCase (line : String) : String :=
  let words := (line.splitOn " ").filter (· ≠ "")
  let result := match words with
    | "framing" :: args => framing args
    | "url" :: args => urlCase args
    | "multipart" :: args => multipartCase args
    | "frame" :: args => frameCase args
    | "lib" :: args => libCase args
    | w :: _ => throw s!"unknown target '{w}'"
    | [] => throw "empty line"
  match result with
  | .ok out => out
  | .error e => s!"error {e}"

partial def loop (stdin : IO.FS.Stream) (stdout : IO.FS.Stream) : IO Unit := do
  let line ← stdin.getLine
  if line.isEmpty then return
  stdout.putStrLn (runCase line.trimRight)
  loop stdin stdout

def main : IO Unit := do
  let stdout ← IO.getStdout
  loop (← IO.getStdin) stdout
  stdout.flush
