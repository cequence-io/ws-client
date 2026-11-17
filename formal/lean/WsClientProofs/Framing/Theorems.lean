import WsClientProofs.Framing.ByteFramingProof
import WsClientProofs.Framing.EngineFramerProof

/-!
# Delimiter framing: the results

For every delimiter, limit, input and cut of the input into chunks:

1. `ByteFraming.delimiter` and the engines' `DelimiterFramer` compute the same thing
   (`framers_equivalent`) - `chunkSpec`.
2. Neither emits a frame the scaladoc model does not: what they emit is a prefix of the
   model's frames (`*_frames_prefix`).
3. Neither lets an oversized frame through: when the model fails, they fail
   (`*_tooLong_of_reference`).
4. When they do not fail, they are the model (`*_eq_reference_of_ne_tooLong`).
5. They fail where the model does not only when a frame comes within `delimiter.length - 1`
   bytes of the limit (`*_eq_reference_of_margin`); away from that edge the result does not
   depend on the chunking at all (`*_chunking_independent`).

`ByteFraming`'s statements hold for inputs shorter than `Int.MaxValue - 8` bytes (its buffer
guard); the engines' framer has no such guard.
-/

namespace WsClientProofs.Framing

open WsClientProofs.Java

set_option linter.unusedSectionVars false

variable {α : Type} [DecidableEq α] [Inhabited α]

theorem pending_nil (d : List α) : firstOcc d ([] : List α) = none := by simp [firstOcc]

theorem framers_equivalent {d : List α} (hd : d ≠ []) (max : Nat) (cs : List (List α))
    (hbound : cs.flatten.length ≤ intMax - 8) :
    EngineFramerModel.run d hd max cs = ByteFramingModel.run d hd max true cs := by
  rw [EngineFramerModel.run_eq_chunkSpec, ByteFramingModel.run_eq_chunkSpec hd max true cs hbound]

section ByteFraming

variable {d : List α} (hd : d ≠ []) (max : Nat) (t : Bool) (cs : List (List α))
  (hbound : cs.flatten.length ≤ intMax - 8)
include hbound

theorem byteFraming_frames_prefix :
    (ByteFramingModel.run d hd max t cs).frames <+: (reference d max t cs.flatten).frames := by
  rw [ByteFramingModel.run_eq_chunkSpec hd max t cs hbound]
  simpa using chunkSpec_frames_prefix cs [] (pending_nil d) (Nat.zero_le _)

theorem byteFraming_tooLong_of_reference
    (h : (reference d max t cs.flatten).ending = .tooLong) :
    (ByteFramingModel.run d hd max t cs).ending = .tooLong := by
  rw [ByteFramingModel.run_eq_chunkSpec hd max t cs hbound]
  exact chunkSpec_tooLong_of_reference cs [] (pending_nil d) (Nat.zero_le _) (by simpa using h)

theorem byteFraming_eq_reference_of_ne_tooLong
    (h : (ByteFramingModel.run d hd max t cs).ending ≠ .tooLong) :
    ByteFramingModel.run d hd max t cs = reference d max t cs.flatten := by
  rw [ByteFramingModel.run_eq_chunkSpec hd max t cs hbound] at h ⊢
  simpa using chunkSpec_eq_reference_of_ne_tooLong cs [] (pending_nil d) (Nat.zero_le _) h

theorem byteFraming_eq_reference_of_margin
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    ByteFramingModel.run d hd max t cs = reference d max t cs.flatten := by
  rw [ByteFramingModel.run_eq_chunkSpec hd max t cs hbound]
  exact chunkSpec_eq_reference_of_margin hd cs hmargin hrest

end ByteFraming

theorem byteFraming_chunking_independent {d : List α} (hd : d ≠ []) (max : Nat) (t : Bool)
    (cs cs' : List (List α)) (hsame : cs.flatten = cs'.flatten)
    (hbound : cs.flatten.length ≤ intMax - 8)
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    ByteFramingModel.run d hd max t cs = ByteFramingModel.run d hd max t cs' := by
  rw [byteFraming_eq_reference_of_margin hd max t cs hbound hmargin hrest,
    byteFraming_eq_reference_of_margin hd max t cs' (hsame ▸ hbound) (hsame ▸ hmargin)
      (hsame ▸ hrest), hsame]

section EngineFramer

variable {d : List α} (hd : d ≠ []) (max : Nat) (cs : List (List α))

theorem engineFramer_frames_prefix :
    (EngineFramerModel.run d hd max cs).frames <+: (reference d max true cs.flatten).frames := by
  rw [EngineFramerModel.run_eq_chunkSpec]
  simpa using chunkSpec_frames_prefix cs [] (pending_nil d) (Nat.zero_le _)

theorem engineFramer_tooLong_of_reference
    (h : (reference d max true cs.flatten).ending = .tooLong) :
    (EngineFramerModel.run d hd max cs).ending = .tooLong := by
  rw [EngineFramerModel.run_eq_chunkSpec]
  exact chunkSpec_tooLong_of_reference cs [] (pending_nil d) (Nat.zero_le _) (by simpa using h)

theorem engineFramer_eq_reference_of_ne_tooLong
    (h : (EngineFramerModel.run d hd max cs).ending ≠ .tooLong) :
    EngineFramerModel.run d hd max cs = reference d max true cs.flatten := by
  rw [EngineFramerModel.run_eq_chunkSpec] at h ⊢
  simpa using chunkSpec_eq_reference_of_ne_tooLong cs [] (pending_nil d) (Nat.zero_le _) h

theorem engineFramer_eq_reference_of_margin
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    EngineFramerModel.run d hd max cs = reference d max true cs.flatten := by
  rw [EngineFramerModel.run_eq_chunkSpec]
  exact chunkSpec_eq_reference_of_margin hd cs hmargin hrest

end EngineFramer

theorem engineFramer_chunking_independent {d : List α} (hd : d ≠ []) (max : Nat)
    (cs cs' : List (List α)) (hsame : cs.flatten = cs'.flatten)
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    EngineFramerModel.run d hd max cs = EngineFramerModel.run d hd max cs' := by
  rw [engineFramer_eq_reference_of_margin hd max cs hmargin hrest,
    engineFramer_eq_reference_of_margin hd max cs' (hsame ▸ hmargin) (hsame ▸ hrest), hsame]

end WsClientProofs.Framing
