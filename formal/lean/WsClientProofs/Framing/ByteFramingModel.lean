import WsClientProofs.Java
import WsClientProofs.Framing.Scan

/-!
# Model: `ByteFraming.DelimiterFramer`

A line-by-line transliteration of the private `DelimiterFramer` in
`ws-client-core/src/main/scala/io/cequence/wsclient/stream/ByteFraming.scala` (the per-run
state of `ByteFraming.delimiter`), and of how its `stateful` stage drives it (`runFrom`).

Conventions (the same in every model):

* the mutable fields are a `State`; a method returns the new state, and `Except Failure` for
  the exceptions it throws;
* a `while` loop is a recursive function; Scala's `Int` arithmetic is natural-number
  arithmetic where every value is non-negative, and each place where the Scala expression can
  be negative (`end - delimiter.length`) says how it is translated;
* `Int` overflow is not modelled: `runFrom` is proved for inputs shorter than
  `Int.MaxValue - 8` bytes, where no index expression of the framer overflows;
* `require(delimiter.nonEmpty)` is the hypothesis `hd`, the precondition the loop's
  termination rests on.
-/

namespace WsClientProofs.Framing.ByteFramingModel

open WsClientProofs.Java

variable {α : Type} [DecidableEq α]

/-- The framer's mutable fields (`end` is a Lean keyword). -/
structure State (α : Type) where
  buffer : List α
  /-- first byte not yet framed -/
  start : Nat
  /-- end of the buffered bytes -/
  end_ : Nat
  /-- no delimiter starts before this index (in buffer coordinates) -/
  scanFrom : Nat

/-- The exceptions the framer throws. -/
inductive Failure where
  /-- `CequenceWSFrameTooLongException`: "A frame of $frameLength bytes exceeds ..." -/
  | frameTooLong
  /-- `CequenceWSFrameTooLongException`: "Read ${end - start} bytes which is more than ..." -/
  | restTooLong
  /-- `CequenceWSFrameTooLongException`: "Read $needed bytes without a frame end." -/
  | bufferTooLarge
  /-- `CequenceWSException`: "Stream finished but there was a truncated final frame ..." -/
  | truncated

def Failure.ending : Failure → End
  | .truncated => .truncated
  | _ => .tooLong

/-- `new Array[Byte](256)`, `start = 0`, `end = 0`, `scanFrom = 0`. -/
def init [Inhabited α] : State α := ⟨List.replicate 256 default, 0, 0, 0⟩

/-- `indexOfDelimiter()`: scans from `math.max(start, scanFrom)`; when no delimiter is found,
`scanFrom = math.max(start, end - delimiter.length + 1)` - the `Int` expression is negative
only where `max` picks `start` anyway, so natural subtraction gives the same value. -/
def indexOfDelimiter (delimiter : List α) (s : State α) : Option Nat × State α :=
  match scan delimiter s.buffer s.end_ (max s.start s.scanFrom) with
  | some i => (some i, s)
  | none => (none, { s with scanFrom := max s.start (s.end_ + 1 - delimiter.length) })

/-- The compaction in `append`: `if (start > 0 && end + length > buffer.length)
{ System.arraycopy(buffer, start, buffer, 0, buffered); scanFrom -= start; start = 0;
end = buffered }`. -/
def compact (s : State α) (length : Nat) : State α :=
  if s.start > 0 ∧ s.end_ + length > s.buffer.length then
    let buffered := s.end_ - s.start
    { buffer := arraycopy s.buffer s.start s.buffer 0 buffered,
      scanFrom := s.scanFrom - s.start, start := 0, end_ := buffered }
  else s

/-- The growth in `append`: `if (end + length > buffer.length) { val needed = end.toLong +
length; if (needed > Int.MaxValue - 8) throw ...; val grown = math.max(needed,
math.min(buffer.length.toLong * 2, Int.MaxValue - 8L)); buffer = Arrays.copyOf(buffer,
grown.toInt) }`. -/
def grow [Inhabited α] (s : State α) (length : Nat) : Except Failure (State α) :=
  if s.end_ + length > s.buffer.length then
    let needed := s.end_ + length
    if needed > intMax - 8 then .error .bufferTooLarge
    else
      let grown := max needed (min (s.buffer.length * 2) (intMax - 8))
      .ok { s with buffer := copyOf s.buffer grown }
  else .ok s

/-- `append(chunk)`: compact, grow, then `System.arraycopy(chunk.unsafeArray,
chunk.unsafeOffset, buffer, end, length); end += length` - all only for a non-empty chunk. -/
def append [Inhabited α] (s : State α) (chunk : List α) : Except Failure (State α) :=
  let length := chunk.length
  if length > 0 then
    (grow (compact s length) length).map fun s =>
      { s with buffer := arraycopy chunk 0 s.buffer s.end_ length, end_ := s.end_ + length }
  else .ok s

theorem indexOfDelimiter_some {delimiter : List α} {s s' : State α} {i : Nat}
    (h : indexOfDelimiter delimiter s = (some i, s')) :
    s' = s ∧ s.start ≤ i ∧ i + delimiter.length ≤ s.end_ := by
  unfold indexOfDelimiter at h
  split at h
  · rename_i j hj
    simp only [Prod.mk.injEq, Option.some.injEq] at h
    obtain ⟨rfl, rfl⟩ := h
    have := scan_some_bounds hj
    exact ⟨rfl, by omega, this.2⟩
  · simp at h

set_option linter.unusedVariables false in
/-- The `while (delimiterAt >= 0)` loop of `push`, with the `indexOfDelimiter()` call moved
from the end of the body (and before the loop) to its top - the same sequence of calls. -/
def frameLoop (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat)
    (s : State α) (frames : List (List α)) : Except Failure (State α × List (List α)) :=
  match h : indexOfDelimiter delimiter s with
  | (none, s') => .ok (s', frames)
  | (some delimiterAt, s') =>
    if delimiterAt - s'.start > maxFrameLength then .error .frameTooLong
    else
      frameLoop delimiter hd maxFrameLength
        { s' with start := delimiterAt + delimiter.length,
                  scanFrom := delimiterAt + delimiter.length }
        (copyOfRange s'.buffer s'.start delimiterAt :: frames)
termination_by s.end_ - s.start
decreasing_by
  obtain ⟨rfl, h1, h2⟩ := indexOfDelimiter_some h
  have := List.length_pos_iff.mpr hd
  omega

/-- `push(chunk)`. -/
def push [Inhabited α] (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat) (s : State α)
    (chunk : List α) : Except Failure (State α × List (List α)) := do
  let s ← append s chunk
  let (s, frames) ← frameLoop delimiter hd maxFrameLength s []
  if s.end_ - s.start > maxFrameLength then throw .restTooLong
  pure (s, frames.reverse)

/-- `finish(allowTruncation)`. -/
def finish (allowTruncation : Bool) (s : State α) : Except Failure (List (List α)) :=
  if s.end_ = s.start then .ok []
  else if allowTruncation then .ok [copyOfRange s.buffer s.start s.end_]
  else .error .truncated

/-- The `stateful` stage of `ByteFraming.delimiter`: `push` per chunk, emitting its frames,
then `finish` at the end; the first exception fails the stream. -/
def runFrom [Inhabited α] (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat)
    (allowTruncation : Bool) (s : State α) : List (List α) → Result α
  | [] =>
    match finish allowTruncation s with
    | .ok frames => ⟨frames, .completed⟩
    | .error e => ⟨[], e.ending⟩
  | c :: cs =>
    match push delimiter hd maxFrameLength s c with
    | .ok (s', frames) => (runFrom delimiter hd maxFrameLength allowTruncation s' cs).prepend frames
    | .error e => ⟨[], e.ending⟩

/-- `ByteFraming.delimiter(delimiter, maxFrameLength, allowTruncation)` on a chunked input. -/
def run [Inhabited α] (delimiter : List α) (hd : delimiter ≠ []) (maxFrameLength : Nat)
    (allowTruncation : Bool) (chunks : List (List α)) : Result α :=
  runFrom delimiter hd maxFrameLength allowTruncation init chunks

end WsClientProofs.Framing.ByteFramingModel
