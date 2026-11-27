import WsClientProofs.Framing.ByteFramingRefinement
import WsClientProofs.Framing.EngineFramerRefinement
import WsClientProofs.Framing.Theorems

/-!
# The framers' theorems, about the shipped code

`Theorems.lean` proves the delimiter framers' properties about hand-written models; the
refinement files prove those models equal to the code `sbt leanExtract` translates from the
Scala (`byteRun_eq`, `engineRun_eq`). Here the two meet: the same statements about the EXTRACTED
code - `ByteFramingExtracted` / `EngineFramerExtracted`, driven by `ExtractedDrivers` - with
the results mapped (`embedResult`: the drivers' result, which also has an `outOfFuel` ending
that these theorems show is never reached).

The drivers (`ByteFraming.delimiter`'s stage, a `TransformPublisher`) are hand-written and
pinned; what they run is the extracted code, in `fuelFor` steps per call.
-/

namespace WsClientProofs.Framing.ExtractedTheorems

open WsClientProofs.Java WsClientProofs.Framing
open WsClientProofs.Framing.ExtractedDrivers (byteRun engineRun embedResult embedEnd)
open WsClientProofs.Framing.ByteFramingRefinement (byteRun_eq)
open WsClientProofs.Framing.EngineFramerRefinement (engineRun_eq)

theorem embedEnd_ne_outOfFuel (e : Framing.End) : embedEnd e ≠ .outOfFuel := by
  cases e <;> simp [embedEnd]

theorem embedEnd_eq_tooLong (e : Framing.End) : embedEnd e = .tooLong ↔ e = .tooLong := by
  cases e <;> simp [embedEnd]

theorem embedResult_injective {r r' : Framing.Result UInt8}
    (h : embedResult r = embedResult r') : r = r' := by
  cases r; cases r'
  simp only [embedResult, ExtractedDrivers.Result.mk.injEq] at h
  obtain ⟨h1, h2⟩ := h
  have : ∀ a b : Framing.End, embedEnd a = embedEnd b → a = b := by
    intro a b; cases a <;> cases b <;> simp [embedEnd]
  simp only [Framing.Result.mk.injEq]
  exact ⟨h1, this _ _ h2⟩

/-! ## `ByteFraming.delimiter` -/

section ByteFraming

variable {d : List UInt8} (hd : d ≠ []) (max : Nat) (t : Bool) (cs : List (List UInt8))
  (hbound : cs.flatten.length ≤ intMax - 8)
include hd hbound

/-- The loops never run out of the fuel the stage gives them. -/
theorem byteFraming_never_outOfFuel :
    (byteRun d (max : Int) t cs).ending ≠ .outOfFuel := by
  rw [byteRun_eq d hd max t cs hbound]
  exact embedEnd_ne_outOfFuel _

/-- **`ByteFraming.delimiter` computes `chunkSpec`** - the shipped code, for any input shorter
than `Int.MaxValue - 8` bytes. -/
theorem byteFraming_eq_chunkSpec :
    byteRun d (max : Int) t cs = embedResult (chunkSpec d max t [] cs) := by
  rw [byteRun_eq d hd max t cs hbound, ByteFramingModel.run_eq_chunkSpec hd max t cs hbound]

theorem byteFraming_frames_prefix :
    (byteRun d (max : Int) t cs).frames <+: (reference d max t cs.flatten).frames := by
  rw [byteRun_eq d hd max t cs hbound]
  exact Framing.byteFraming_frames_prefix hd max t cs hbound

theorem byteFraming_tooLong_of_reference
    (h : (reference d max t cs.flatten).ending = .tooLong) :
    (byteRun d (max : Int) t cs).ending = .tooLong := by
  rw [byteRun_eq d hd max t cs hbound]
  exact (embedEnd_eq_tooLong _).mpr (Framing.byteFraming_tooLong_of_reference hd max t cs hbound h)

theorem byteFraming_eq_reference_of_ne_tooLong
    (h : (byteRun d (max : Int) t cs).ending ≠ .tooLong) :
    byteRun d (max : Int) t cs = embedResult (reference d max t cs.flatten) := by
  rw [byteRun_eq d hd max t cs hbound] at h ⊢
  have h' : (ByteFramingModel.run d hd max t cs).ending ≠ .tooLong :=
    fun e => h ((embedEnd_eq_tooLong _).mpr e)
  rw [Framing.byteFraming_eq_reference_of_ne_tooLong hd max t cs hbound h']

theorem byteFraming_eq_reference_of_margin
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    byteRun d (max : Int) t cs = embedResult (reference d max t cs.flatten) := by
  rw [byteRun_eq d hd max t cs hbound,
    Framing.byteFraming_eq_reference_of_margin hd max t cs hbound hmargin hrest]

end ByteFraming

theorem byteFraming_chunking_independent {d : List UInt8} (hd : d ≠ []) (max : Nat) (t : Bool)
    (cs cs' : List (List UInt8)) (hsame : cs.flatten = cs'.flatten)
    (hbound : cs.flatten.length ≤ intMax - 8)
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    byteRun d (max : Int) t cs = byteRun d (max : Int) t cs' := by
  rw [byteRun_eq d hd max t cs hbound, byteRun_eq d hd max t cs' (hsame ▸ hbound),
    Framing.byteFraming_chunking_independent hd max t cs cs' hsame hbound hmargin hrest]

/-! ## `StreamTransformers.DelimiterFramer` -/

section EngineFramer

variable {d : List UInt8} (hd : d ≠ []) (max : Nat) (cs : List (List UInt8))
include hd

theorem engineFramer_never_outOfFuel :
    (engineRun d (max : Int) cs).ending ≠ .outOfFuel := by
  rw [engineRun_eq d hd max cs]
  exact embedEnd_ne_outOfFuel _

/-- **The engines' `DelimiterFramer` computes `chunkSpec`** - the shipped code, for every
input. -/
theorem engineFramer_eq_chunkSpec :
    engineRun d (max : Int) cs = embedResult (chunkSpec d max true [] cs) := by
  rw [engineRun_eq d hd max cs, EngineFramerModel.run_eq_chunkSpec]

theorem engineFramer_frames_prefix :
    (engineRun d (max : Int) cs).frames <+: (reference d max true cs.flatten).frames := by
  rw [engineRun_eq d hd max cs]
  exact Framing.engineFramer_frames_prefix hd max cs

theorem engineFramer_tooLong_of_reference
    (h : (reference d max true cs.flatten).ending = .tooLong) :
    (engineRun d (max : Int) cs).ending = .tooLong := by
  rw [engineRun_eq d hd max cs]
  exact (embedEnd_eq_tooLong _).mpr (Framing.engineFramer_tooLong_of_reference hd max cs h)

theorem engineFramer_eq_reference_of_ne_tooLong
    (h : (engineRun d (max : Int) cs).ending ≠ .tooLong) :
    engineRun d (max : Int) cs = embedResult (reference d max true cs.flatten) := by
  rw [engineRun_eq d hd max cs] at h ⊢
  have h' : (EngineFramerModel.run d hd max cs).ending ≠ .tooLong :=
    fun e => h ((embedEnd_eq_tooLong _).mpr e)
  rw [Framing.engineFramer_eq_reference_of_ne_tooLong hd max cs h']

theorem engineFramer_eq_reference_of_margin
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    engineRun d (max : Int) cs = embedResult (reference d max true cs.flatten) := by
  rw [engineRun_eq d hd max cs,
    Framing.engineFramer_eq_reference_of_margin hd max cs hmargin hrest]

end EngineFramer

theorem engineFramer_chunking_independent {d : List UInt8} (hd : d ≠ []) (max : Nat)
    (cs cs' : List (List UInt8)) (hsame : cs.flatten = cs'.flatten)
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    engineRun d (max : Int) cs = engineRun d (max : Int) cs' := by
  rw [engineRun_eq d hd max cs, engineRun_eq d hd max cs',
    Framing.engineFramer_chunking_independent hd max cs cs' hsame hmargin hrest]

/-! ## Both framers -/

/-- **The two framers compute the same function of the chunks** - the shipped code of both. -/
theorem framers_equivalent {d : List UInt8} (hd : d ≠ []) (max : Nat) (cs : List (List UInt8))
    (hbound : cs.flatten.length ≤ intMax - 8) :
    engineRun d (max : Int) cs = byteRun d (max : Int) true cs := by
  rw [engineFramer_eq_chunkSpec hd max cs, byteFraming_eq_chunkSpec hd max true cs hbound]

end WsClientProofs.Framing.ExtractedTheorems
