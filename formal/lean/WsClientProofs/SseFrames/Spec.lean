import WsClientProofs.Scala

/-!
# SSE frames: the specification

The rules of `JsonStreamFrames.parseFrame`'s scaladoc, on the LINES of a frame (`reference`):

* lines are separated by CRLF, CR or LF (SSE); a line whose first character is `:` is a
  comment and is dropped;
* a frame left with blank lines only (or none) is `skip`;
* an SSE event is a frame with a line STARTING with the field prefix (`data:`; the item prefix
  `data: ` without its one trailing space): its data is the rest of the FIRST such line - one
  space after the colon dropped when the item prefix has it - followed by the lines after it
  (comment lines dropped, joined with LF); the lines before it (`event:`, `id:`) are dropped;
* any other frame is a raw payload: the frame as it is - or, when it starts with comment
  lines, the lines after them joined with LF;
* data that trims to `[DONE]` is the end of the stream (`done`); otherwise the payload is the
  data with the optional prefix and suffix stripped - handed to the JSON parser, which is not
  modelled.

Texts are `List Char`; `trim`, `stripPrefix` and `stripSuffix` are Java's / Scala's.
-/

namespace WsClientProofs.SseFrames

open WsClientProofs.Scala (startsWith stripPrefix stripSuffix trim splitLines)

/-- What `parseFrame` does with a frame (the JSON parse of a payload is not modelled). -/
inductive Outcome where
  | skip
  | done
  | payload (data : List Char)
  deriving DecidableEq, Repr

def isComment (line : List Char) : Bool := startsWith line [':']

def blank (line : List Char) : Bool := (trim line).isEmpty

/-- `mkString("\n")`. -/
def joinLines : List (List Char) → List Char
  | [] => []
  | [l] => l
  | l :: ls => l ++ '\n' :: joinLines ls

/-- The first line starting with the field prefix: the rest of it, and the lines after it. -/
def firstField (fieldPrefix : List Char) :
    List (List Char) → Option (List Char × List (List Char))
  | [] => none
  | l :: ls => if startsWith l fieldPrefix then some (l.drop fieldPrefix.length, ls)
    else firstField fieldPrefix ls

/-- The data or the end of the stream, after the optional prefix and suffix stripping. -/
def classify (data : List Char) (prefixToStrip suffixToStrip : Option (List Char)) : Outcome :=
  if trim data = "[DONE]".toList then .done
  else .payload (stripSuffix (stripPrefix data (prefixToStrip.getD [])) (suffixToStrip.getD []))

/-- **The rules, line by line.** -/
def reference (frame : List Char) (prefixToStrip suffixToStrip : Option (List Char))
    (itemPrefix : List Char) : Outcome :=
  let fieldPrefix := stripSuffix itemPrefix [' ']
  let lines := splitLines frame
  let kept := lines.filter (fun l => !isComment l)
  if kept.all blank then .skip
  else
    let data :=
      match firstField fieldPrefix kept with
      | some (value, after) =>
        let value :=
          if fieldPrefix.length < itemPrefix.length && startsWith value [' '] then value.drop 1
          else value
        joinLines (value :: after)
      | none => if isComment frame then joinLines (lines.dropWhile isComment) else frame
    classify data prefixToStrip suffixToStrip

end WsClientProofs.SseFrames
