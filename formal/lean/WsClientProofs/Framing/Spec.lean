/-!
# Delimiter framing: the specification

The model of `ByteFraming.delimiter`, written from its scaladoc alone:

> Splits the bytes on `delimiter` (not included in the frames; consecutive delimiters yield
> empty frames) [...] A frame longer than `maxFrameLength` fails the stream with a
> `CequenceWSFrameTooLongException` - also one whose delimiter arrives, and an unterminated
> rest that outgrows the limit. At the end, an unterminated non-empty rest is emitted as the
> last frame (`allowTruncation`) or fails the stream.

`reference` is that sentence as a function of the whole input: it cannot depend on how the
input is cut into chunks. It is the list model of `FramingSmallScopeSpec`, there checked
for every input up to length 6, here proved about for every input.

`chunkSpec` is what the two framers compute chunk by chunk (`Theorems.lean` proves both
framers equal to it, and states exactly where it departs from `reference`).

Everything here is over an arbitrary element type with decidable equality; the framers work
on bytes.
-/

namespace WsClientProofs.Framing

variable {α : Type} [DecidableEq α]

/-- `d` occurs in `l` at position `p`. -/
def OccursAt (d l : List α) (p : Nat) : Prop := d <+: l.drop p

/-- The first position at which `d` occurs in `l` (`indexOf`), for a non-empty `d`; always
`none` for an empty `d` (the framers require a non-empty delimiter). -/
def firstOcc (d : List α) : List α → Option Nat
  | [] => none
  | l@(_ :: xs) => if !d.isEmpty && d.isPrefixOf l then some 0 else (firstOcc d xs).map (· + 1)

theorem firstOcc_some_bound {d l : List α} {p : Nat} (h : firstOcc d l = some p) :
    d ≠ [] ∧ p + d.length ≤ l.length := by
  induction l generalizing p with
  | nil => simp [firstOcc] at h
  | cons x xs ih =>
    simp only [firstOcc] at h
    split at h
    · rename_i hc
      simp only [Bool.and_eq_true, Bool.not_eq_true', List.isEmpty_eq_false_iff] at hc
      obtain ⟨hne, hpre⟩ := hc
      have := (List.isPrefixOf_iff_prefix.mp hpre).length_le
      simp only [Option.some.injEq] at h
      subst h
      exact ⟨hne, by simpa using this⟩
    · obtain ⟨q, hq, rfl⟩ := Option.map_eq_some_iff.mp h
      have := ih hq
      exact ⟨this.1, by simp; omega⟩

set_option linter.unusedVariables false in
/-- Splits `l` at every leftmost occurrence of `d`: the frames, and the unterminated rest. -/
def splitOn (d : List α) (l : List α) : List (List α) × List α :=
  match h : firstOcc d l with
  | none => ([], l)
  | some p =>
    let rest := splitOn d (l.drop (p + d.length))
    (l.take p :: rest.1, rest.2)
termination_by l.length
decreasing_by
  have hb := firstOcc_some_bound h
  have := List.length_pos_iff.mpr hb.1
  simp only [List.length_drop]
  omega

/-- How a framed stream ends. -/
inductive End where
  | completed
  /-- `CequenceWSFrameTooLongException` -/
  | tooLong
  /-- a truncated final frame with `allowTruncation = false` (`CequenceWSException`) -/
  | truncated
  deriving DecidableEq, Repr

/-- What a consumer observes: the frames emitted, then how the stream ended. -/
structure Result (α : Type) where
  frames : List (List α)
  ending : End
  deriving DecidableEq, Repr

/-- Frames emitted before the rest of a result. -/
def Result.prepend (fs : List (List α)) (r : Result α) : Result α :=
  ⟨fs ++ r.frames, r.ending⟩

/-- The end of the stream: an unterminated non-empty rest is the last frame
(`allowTruncation`) or fails the stream. -/
def finish (allowTruncation : Bool) (rest : List α) : Result α :=
  if rest.isEmpty then ⟨[], .completed⟩
  else if allowTruncation then ⟨[rest], .completed⟩
  else ⟨[], .truncated⟩

/-- The limit, applied to the frames in order and then to the rest: the first frame longer
than `maxFrameLength` fails the stream, as does a longer rest. -/
def applyLimits (maxFrameLength : Nat) (allowTruncation : Bool) :
    List (List α) → List α → Result α
  | f :: fs, rest =>
    if maxFrameLength < f.length then ⟨[], .tooLong⟩
    else (applyLimits maxFrameLength allowTruncation fs rest).prepend [f]
  | [], rest =>
    if maxFrameLength < rest.length then ⟨[], .tooLong⟩ else finish allowTruncation rest

/-- **The scaladoc model** of `ByteFraming.delimiter(delimiter, maxFrameLength,
allowTruncation)` on the whole input. -/
def reference (delimiter : List α) (maxFrameLength : Nat) (allowTruncation : Bool)
    (input : List α) : Result α :=
  let s := splitOn delimiter input
  applyLimits maxFrameLength allowTruncation s.1 s.2

/-- **What the framers compute**, chunk by chunk, from the unterminated `pending` bytes: each
chunk's completed frames are emitted together - unless one of them, or the new unterminated
rest, is longer than `maxFrameLength`: then the stream fails and none of them is emitted. -/
def chunkSpec (delimiter : List α) (maxFrameLength : Nat) (allowTruncation : Bool)
    (pending : List α) : List (List α) → Result α
  | [] => finish allowTruncation pending
  | c :: cs =>
    let s := splitOn delimiter (pending ++ c)
    if s.1.any (fun f => maxFrameLength < f.length) || maxFrameLength < s.2.length then
      ⟨[], .tooLong⟩
    else
      (chunkSpec delimiter maxFrameLength allowTruncation s.2 cs).prepend s.1

end WsClientProofs.Framing
