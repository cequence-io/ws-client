import WsClientProofs.Java
import WsClientProofs.Framing.Scan

/-!
# Model: `StreamTransformers.DelimiterFramer`

A line-by-line transliteration of `DelimiterFramer` in
`ws-client-core/src/main/scala/io/cequence/wsclient/stream/StreamTransformers.scala` - the
SSE framer of the jdk and zio-http engines - and of how a `TransformPublisher` drives it
(`runFrom`): `onInput` per chunk, emitting its frames, then `onUpstreamComplete`.

The conventions of `ByteFramingModel` apply. This framer has no `maxFrameLength > 0`
requirement and always emits an unterminated rest (`allowTruncation = true`).
-/

namespace WsClientProofs.Framing.EngineFramerModel

open WsClientProofs.Java

variable {α : Type} [DecidableEq α]

/-- The framer's mutable fields: the bytes not yet framed, and how far they were searched. -/
structure State (α : Type) where
  buffer : List α
  scanFrom : Nat

/-- The exceptions the framer throws, both `CequenceWSFrameTooLongException`. -/
inductive Failure where
  /-- "A frame of $frameLength bytes exceeds the maximum frame length of ..." -/
  | frameTooLong
  /-- "Read ${buffer.length} bytes which is more than ... without seeing a line terminator." -/
  | restTooLong

/-- `buffer = Array.emptyByteArray`, `scanFrom = 0`. -/
def init : State α := ⟨[], 0⟩

/-- `indexOfDelimiter(from)`: scans from `math.max(from, scanFrom)` with
`limit = buffer.length - delimiter.length`; when no delimiter is found,
`scanFrom = math.max(0, buffer.length - delimiter.length + 1)` - natural subtraction. -/
def indexOfDelimiter (delimiter : List α) (s : State α) (fromIndex : Nat) :
    Option Nat × State α :=
  match scan delimiter s.buffer s.buffer.length (max fromIndex s.scanFrom) with
  | some i => (some i, s)
  | none => (none, { s with scanFrom := s.buffer.length + 1 - delimiter.length })

theorem indexOfDelimiter_some {delimiter : List α} {s s' : State α} {fromIndex i : Nat}
    (h : indexOfDelimiter delimiter s fromIndex = (some i, s')) :
    s' = s ∧ fromIndex ≤ i ∧ i + delimiter.length ≤ s.buffer.length := by
  unfold indexOfDelimiter at h
  split at h
  · rename_i j hj
    simp only [Prod.mk.injEq, Option.some.injEq] at h
    obtain ⟨rfl, rfl⟩ := h
    have := scan_some_bounds hj
    exact ⟨rfl, by omega, this.2⟩
  · simp at h

set_option linter.unusedVariables false in
/-- The `while (delimiterIndex >= 0)` loop of `onInput`, with the `indexOfDelimiter(start)`
call moved from the end of the body (and before the loop) to its top. -/
def frameLoop (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat)
    (s : State α) (start : Nat) (frames : List (List α)) :
    Except Failure (State α × Nat × List (List α)) :=
  match h : indexOfDelimiter delimiter s start with
  | (none, s') => .ok (s', start, frames)
  | (some delimiterIndex, s') =>
    if delimiterIndex - start > maxFrameLength then .error .frameTooLong
    else
      frameLoop delimiter hd maxFrameLength s' (delimiterIndex + delimiter.length)
        (frames ++ [copyOfRange s'.buffer start delimiterIndex])
termination_by s.buffer.length - start
decreasing_by
  obtain ⟨rfl, h1, h2⟩ := indexOfDelimiter_some h
  have := List.length_pos_iff.mpr hd
  omega

/-- `onInput(input)`. -/
def onInput (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat) (s : State α)
    (input : List α) : Except Failure (State α × List (List α)) := do
  let s := { s with buffer := s.buffer ++ input }
  let (s, start, frames) ← frameLoop delimiter hd maxFrameLength s 0 []
  let s := if start > 0 then
      { buffer := copyOfRange s.buffer start s.buffer.length, scanFrom := s.scanFrom - start }
    else s
  if s.buffer.length > maxFrameLength then throw .restTooLong
  pure (s, frames)

/-- `onUpstreamComplete()`: `if (buffer.nonEmpty) Seq(ByteBuffer.wrap(buffer)) else Nil`. -/
def onUpstreamComplete (s : State α) : List (List α) :=
  if s.buffer.isEmpty then [] else [s.buffer]

/-- A `TransformPublisher` running the framer: `onInput` per chunk, emitting its frames, then
`onUpstreamComplete`; the first exception fails the stream. -/
def runFrom (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat) (s : State α) :
    List (List α) → Result α
  | [] => ⟨onUpstreamComplete s, .completed⟩
  | c :: cs =>
    match onInput delimiter hd maxFrameLength s c with
    | .ok (s', frames) => (runFrom delimiter hd maxFrameLength s' cs).prepend frames
    | .error _ => ⟨[], .tooLong⟩

/-- `new DelimiterFramer(delimiter, maxFrameLength)` on a chunked input. -/
def run (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat)
    (chunks : List (List α)) : Result α :=
  runFrom delimiter hd maxFrameLength init chunks

end WsClientProofs.Framing.EngineFramerModel
