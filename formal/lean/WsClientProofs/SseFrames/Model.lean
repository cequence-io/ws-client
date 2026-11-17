import WsClientProofs.Framing.Spec
import WsClientProofs.SseFrames.Spec

/-!
# Model: `JsonStreamFrames.parseFrame`

A transliteration of `parseFrame` in
`ws-client-core/src/main/scala/io/cequence/wsclient/service/JsonStreamFrames.scala` up to the
string it hands to `Json.parse` (the parse itself, and the mapping of its errors to
`CequenceWSException`, are not modelled):

```scala
val fieldPrefix = itemPrefix.stripSuffix(" ")
val needsLineScan = isComment(frame) || frame.contains("\n:") || frame.indexOf('\r') > -1
val allLines = if (needsLineScan) LineBreak.split(frame, -1) else Array(frame)
val lines = if (needsLineScan) allLines.filterNot(isComment) else allLines
if (lines.forall(_.trim.isEmpty)) Skip
else {
  val content =
    if (!needsLineScan) frame
    else if (lines.exists(_.startsWith(fieldPrefix))) lines.mkString("\n")
    else if (isComment(frame)) allLines.dropWhile(isComment).mkString("\n")
    else frame
  val dataStart =
    if (content.startsWith(fieldPrefix)) 0
    else { val i = content.indexOf("\n" + fieldPrefix); if (i > -1) i + 1 else -1 }
  val data =
    if (dataStart > -1) {
      val value = content.substring(dataStart + fieldPrefix.length)
      if (fieldPrefix.length < itemPrefix.length && value.startsWith(" ")) value.substring(1)
      else value
    } else content
  if (data.trim.equals(EndOfStreamToken)) EndOfStream
  else JsonFrame(Json.parse(
    data.stripPrefix(stripPrefix.getOrElse("")).stripSuffix(stripSuffix.getOrElse(""))))
}
```

`String.indexOf(String)` is `Framing.firstOcc` (`-1` is `none`).
-/

namespace WsClientProofs.SseFrames

open WsClientProofs.Framing (firstOcc)

/-- `JsonStreamFrames.parseFrame(frame, stripPrefix, stripSuffix, itemPrefix)`. -/
def parseFrame (frame : List Char) (prefixToStrip suffixToStrip : Option (List Char))
    (itemPrefix : List Char) : Outcome :=
  let fieldPrefix := stripSuffix itemPrefix [' ']
  let needsLineScan :=
    isComment frame || (firstOcc ['\n', ':'] frame).isSome || frame.contains '\r'
  let allLines := if needsLineScan then splitLines frame else [frame]
  let lines := if needsLineScan then allLines.filter (fun l => !isComment l) else allLines
  if lines.all (fun l => (trim l).isEmpty) then .skip
  else
    let content :=
      if !needsLineScan then frame
      else if lines.any (startsWith · fieldPrefix) then joinLines lines
      else if isComment frame then joinLines (allLines.dropWhile isComment)
      else frame
    let dataStart : Option Nat :=
      if startsWith content fieldPrefix then some 0
      else (firstOcc ('\n' :: fieldPrefix) content).map (· + 1)
    let data :=
      match dataStart with
      | some start =>
        let value := content.drop (start + fieldPrefix.length)
        if fieldPrefix.length < itemPrefix.length && startsWith value [' '] then value.drop 1
        else value
      | none => content
    if trim data = "[DONE]".toList then .done
    else .payload (stripSuffix (stripPrefix data (prefixToStrip.getD [])) (suffixToStrip.getD []))

end WsClientProofs.SseFrames
