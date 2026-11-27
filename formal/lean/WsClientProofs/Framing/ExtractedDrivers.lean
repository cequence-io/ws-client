import WsClientProofs.Framing.ByteFramingExtracted
import WsClientProofs.Framing.EngineFramerExtracted

/-!
# Driving the extracted framers

The two framers are classes: what runs them is a stage of the stream layer - `ByteFraming.
delimiter`'s `stateful` stage (`push` per chunk, then `finish`) and the jdk / zio-http engines'
`TransformPublisher` (`onInput` per chunk, then `onUpstreamComplete`); the first exception
fails the stream, with the frames of the earlier chunks already emitted. These two small
drivers are hand-written (the stages are not extractable) and pinned to the Scala that drives
the framers; what they run is the EXTRACTED code.

A loop of the extracted code takes its `fuel` from the caller: the drivers give each call
`fuelFor`, which the refinement theorems (`Framing/Refinement.lean`) show is enough - the
result is never `outOfFuel`. `outOfFuel` is its own ending so that a conformance run shows a
too small bound instead of hiding it.
-/

namespace WsClientProofs.Framing.ExtractedDrivers

/-- How a run ends (`outOfFuel`: a loop ran out of the fuel it was given). -/
inductive End where
  | completed
  | tooLong
  | truncated
  | outOfFuel
  deriving DecidableEq, Repr

structure Result where
  frames : List (List UInt8)
  ending : End
  deriving DecidableEq, Repr

def Result.prepend (fs : List (List UInt8)) (r : Result) : Result := ⟨fs ++ r.frames, r.ending⟩

/-- A result of the models (`Framing.Result`) as the drivers' (never `outOfFuel`). -/
def embedEnd : Framing.End → End
  | .completed => .completed
  | .tooLong => .tooLong
  | .truncated => .truncated

def embedResult (r : Framing.Result UInt8) : Result := ⟨r.frames, embedEnd r.ending⟩

/-- The fuel a call is given: more than every loop of the framers needs (they scan the
buffer, which a chunk makes at most `2 * buffer + chunk` long, and compare at most
`delimiter.length` bytes). -/
def fuelFor (delimiterLength bufferLength chunkLength : Nat) : Nat :=
  2 * (bufferLength + chunkLength) + delimiterLength + 4

/-! ## `ByteFraming.delimiter` -/

section ByteFraming

open ByteFramingExtracted

def byteEnd : Failure → End
  | .CequenceWSException => .truncated
  | .CequenceWSFrameTooLongException => .tooLong
  | .outOfFuel => .outOfFuel

def byteRunFrom (delimiter : List UInt8) (maxFrameLength : Int) (allowTruncation : Bool)
    (s : State) : List (List UInt8) → Result
  | [] =>
    match ByteFramingExtracted.finish delimiter maxFrameLength 0 s allowTruncation with
    | .ok frames => ⟨frames, .completed⟩
    | .error e => ⟨[], byteEnd e⟩
  | c :: cs =>
    match ByteFramingExtracted.push delimiter maxFrameLength
        (fuelFor delimiter.length s.buffer.length c.length) s c with
    | .ok (s', frames) =>
      (byteRunFrom delimiter maxFrameLength allowTruncation s' cs).prepend frames
    | .error e => ⟨[], byteEnd e⟩

/-- `ByteFraming.delimiter(delimiter, maxFrameLength, allowTruncation)` on a chunked input. -/
def byteRun (delimiter : List UInt8) (maxFrameLength : Int) (allowTruncation : Bool)
    (chunks : List (List UInt8)) : Result :=
  byteRunFrom delimiter maxFrameLength allowTruncation
    (ByteFramingExtracted.init delimiter maxFrameLength) chunks

end ByteFraming

/-! ## `StreamTransformers.DelimiterFramer` -/

section EngineFramer

open EngineFramerExtracted

def engineEnd : Failure → End
  | .CequenceWSFrameTooLongException => .tooLong
  | .outOfFuel => .outOfFuel

def engineRunFrom (delimiter : List UInt8) (maxFrameLength : Int) (s : State) :
    List (List UInt8) → Result
  | [] =>
    match EngineFramerExtracted.onUpstreamComplete delimiter maxFrameLength 0 s with
    | .ok frames => ⟨frames, .completed⟩
    | .error e => ⟨[], engineEnd e⟩
  | c :: cs =>
    match EngineFramerExtracted.onInput delimiter maxFrameLength
        (fuelFor delimiter.length s.buffer.length c.length) s c with
    | .ok (s', frames) => (engineRunFrom delimiter maxFrameLength s' cs).prepend frames
    | .error e => ⟨[], engineEnd e⟩

/-- A `TransformPublisher` running `new DelimiterFramer(delimiter, maxFrameLength)`. -/
def engineRun (delimiter : List UInt8) (maxFrameLength : Int) (chunks : List (List UInt8)) :
    Result :=
  engineRunFrom delimiter maxFrameLength (EngineFramerExtracted.init delimiter maxFrameLength)
    chunks

end EngineFramer

end WsClientProofs.Framing.ExtractedDrivers
