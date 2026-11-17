import WsClientProofs.Framing.SplitLemmas

/-!
# `chunkSpec` against `reference`

Per chunk, `chunkSpec` splits the pending bytes plus the chunk, and fails as soon as a frame
completed in the chunk, or the new rest, is longer than the limit. Against the scaladoc model
that differs in exactly two ways (the two edges `FramingSmallScopeSpec.classify` allows):

* frames completed in the same chunk as an oversized frame are not emitted before the
  failure (the model emits them) - `chunkSpec_frames_prefix`;
* a rest that ends in part of the delimiter counts those bytes, so a frame within
  `delimiter.length - 1` bytes of the limit fails when a chunk boundary splits its
  delimiter - `chunkSpec_eq_reference_of_margin` says that is the only way to fail where the
  model does not.

Everything is proved from any pending rest the framers can hold: no occurrence of the
delimiter in it, and not longer than the limit.
-/

namespace WsClientProofs.Framing

set_option linter.unusedSectionVars false

variable {α : Type} [DecidableEq α]

@[simp] theorem prepend_frames (fs : List (List α)) (r : Result α) :
    (r.prepend fs).frames = fs ++ r.frames := rfl

@[simp] theorem prepend_ending (fs : List (List α)) (r : Result α) :
    (r.prepend fs).ending = r.ending := rfl

theorem finish_ending_ne_tooLong (t : Bool) (r : List α) : (finish t r).ending ≠ .tooLong := by
  unfold finish; split
  · simp
  · split <;> simp

theorem applyLimits_nil_of_le {max : Nat} {t : Bool} {r : List α} (h : r.length ≤ max) :
    applyLimits max t [] r = finish t r := by
  simp [applyLimits]; omega

theorem applyLimits_append {max : Nat} {t : Bool} {fs gs : List (List α)} {r : List α}
    (h : ∀ f ∈ fs, f.length ≤ max) :
    applyLimits max t (fs ++ gs) r = (applyLimits max t gs r).prepend fs := by
  induction fs with
  | nil => simp [Result.prepend]
  | cons f fs ih =>
    have hf := h f (by simp)
    simp only [List.cons_append, applyLimits]
    rw [if_neg (by omega), ih (fun g hg => h g (by simp [hg]))]
    simp [Result.prepend]

/-- When the frames of `x` are within the limit, the model on `x ++ rest` emits them, then
continues from the rest of `x`. -/
theorem reference_append_step {d : List α} {max : Nat} {t : Bool} {x rest : List α}
    (h : ∀ f ∈ (splitOn d x).1, f.length ≤ max) :
    reference d max t (x ++ rest) =
      (reference d max t ((splitOn d x).2 ++ rest)).prepend (splitOn d x).1 := by
  simp only [reference]
  rw [splitOn_append, applyLimits_append h]

theorem reference_of_pending {d : List α} {max : Nat} {t : Bool} {p : List α}
    (hp : firstOcc d p = none) (hlen : p.length ≤ max) :
    reference d max t p = finish t p := by
  simp only [reference, splitOn_of_none hp]
  exact applyLimits_nil_of_le hlen

/-- The condition under which `chunkSpec` fails on a chunk, unfolded. -/
theorem chunk_ok_iff {max : Nat} {s : List (List α) × List α} :
    ¬ ((s.1.any fun f => decide (max < f.length)) || decide (max < s.2.length)) = true ↔
      (∀ f ∈ s.1, f.length ≤ max) ∧ s.2.length ≤ max := by
  simp only [Bool.or_eq_true, List.any_eq_true, decide_eq_true_eq, not_or, not_exists,
    not_and, Nat.not_lt]

theorem chunkSpec_cons_ok {d : List α} {max : Nat} {t : Bool} {p c : List α}
    {cs : List (List α)}
    (h : (∀ f ∈ (splitOn d (p ++ c)).1, f.length ≤ max) ∧
      (splitOn d (p ++ c)).2.length ≤ max) :
    chunkSpec d max t p (c :: cs) =
      (chunkSpec d max t (splitOn d (p ++ c)).2 cs).prepend (splitOn d (p ++ c)).1 := by
  simp only [chunkSpec]
  rw [if_neg (chunk_ok_iff.mpr h)]

theorem chunkSpec_cons_fail {d : List α} {max : Nat} {t : Bool} {p c : List α}
    {cs : List (List α)}
    (h : ¬ ((∀ f ∈ (splitOn d (p ++ c)).1, f.length ≤ max) ∧
      (splitOn d (p ++ c)).2.length ≤ max)) :
    chunkSpec d max t p (c :: cs) = ⟨[], .tooLong⟩ := by
  simp only [chunkSpec]
  rw [if_pos (Classical.byContradiction fun hn => h (chunk_ok_iff.mp hn))]

/-- **Agreement**: whenever `chunkSpec` does not fail, it is the model. -/
theorem chunkSpec_eq_reference_of_ne_tooLong {d : List α} {max : Nat} {t : Bool} :
    ∀ (cs : List (List α)) (p : List α), firstOcc d p = none → p.length ≤ max →
      (chunkSpec d max t p cs).ending ≠ .tooLong →
      chunkSpec d max t p cs = reference d max t (p ++ cs.flatten)
  | [], p, hp, hlen, _ => by
    simp only [chunkSpec, List.flatten_nil, List.append_nil]
    exact (reference_of_pending hp hlen).symm
  | c :: cs, p, hp, hlen, hok => by
    by_cases h : (∀ f ∈ (splitOn d (p ++ c)).1, f.length ≤ max) ∧
        (splitOn d (p ++ c)).2.length ≤ max
    · rw [chunkSpec_cons_ok h] at hok ⊢
      rw [List.flatten_cons, ← List.append_assoc, reference_append_step h.1,
        chunkSpec_eq_reference_of_ne_tooLong cs _ (firstOcc_splitOn_rest d _) h.2 hok]
    · rw [chunkSpec_cons_fail h] at hok
      exact absurd rfl hok

/-- **No oversized frame gets through**: when the model fails, so does `chunkSpec`. -/
theorem chunkSpec_tooLong_of_reference {d : List α} {max : Nat} {t : Bool} :
    ∀ (cs : List (List α)) (p : List α), firstOcc d p = none → p.length ≤ max →
      (reference d max t (p ++ cs.flatten)).ending = .tooLong →
      (chunkSpec d max t p cs).ending = .tooLong
  | [], p, hp, hlen, hfail => by
    simp only [List.flatten_nil, List.append_nil, reference_of_pending hp hlen] at hfail
    exact absurd hfail (finish_ending_ne_tooLong t p)
  | c :: cs, p, hp, hlen, hfail => by
    by_cases h : (∀ f ∈ (splitOn d (p ++ c)).1, f.length ≤ max) ∧
        (splitOn d (p ++ c)).2.length ≤ max
    · rw [chunkSpec_cons_ok h, prepend_ending]
      rw [List.flatten_cons, ← List.append_assoc, reference_append_step h.1,
        prepend_ending] at hfail
      exact chunkSpec_tooLong_of_reference cs _ (firstOcc_splitOn_rest d _) h.2 hfail
    · rw [chunkSpec_cons_fail h]

/-- **No wrong frame**: what `chunkSpec` emits is always a prefix of what the model emits. -/
theorem chunkSpec_frames_prefix {d : List α} {max : Nat} {t : Bool} :
    ∀ (cs : List (List α)) (p : List α), firstOcc d p = none → p.length ≤ max →
      (chunkSpec d max t p cs).frames <+: (reference d max t (p ++ cs.flatten)).frames
  | [], p, hp, hlen => by
    simp only [chunkSpec, List.flatten_nil, List.append_nil, reference_of_pending hp hlen]
    exact List.prefix_refl _
  | c :: cs, p, hp, hlen => by
    by_cases h : (∀ f ∈ (splitOn d (p ++ c)).1, f.length ≤ max) ∧
        (splitOn d (p ++ c)).2.length ≤ max
    · rw [chunkSpec_cons_ok h, List.flatten_cons, ← List.append_assoc,
        reference_append_step h.1, prepend_frames, prepend_frames,
        List.prefix_append_right_inj]
      exact chunkSpec_frames_prefix cs _ (firstOcc_splitOn_rest d _) h.2
    · rw [chunkSpec_cons_fail h]
      exact List.nil_prefix

/-- With every frame at least `delimiter.length - 1` bytes under the limit (and the rest
within it), `chunkSpec` never fails. -/
theorem chunkSpec_ne_tooLong_of_margin {d : List α} {max : Nat} {t : Bool} (hd : d ≠ []) :
    ∀ (cs : List (List α)) (p : List α), firstOcc d p = none → p.length ≤ max →
      (∀ f ∈ (splitOn d (p ++ cs.flatten)).1, f.length + d.length ≤ max + 1) →
      (splitOn d (p ++ cs.flatten)).2.length ≤ max →
      (chunkSpec d max t p cs).ending ≠ .tooLong
  | [], p, _, _, _, _ => by
    simp only [chunkSpec]; exact finish_ending_ne_tooLong t p
  | c :: cs, p, hp, hlen, hmargin, hrest => by
    have hdlen := List.length_pos_iff.mpr hd
    rw [List.flatten_cons, ← List.append_assoc, splitOn_append] at hmargin hrest
    -- the frames of this chunk are frames of the whole input
    have hframes : ∀ f ∈ (splitOn d (p ++ c)).1, f.length ≤ max := fun f hf => by
      have := hmargin f (List.mem_append_left _ hf); omega
    -- the new rest is either a prefix of the whole rest, or ends before the next frame's
    -- delimiter does
    have hnew : (splitOn d (p ++ c)).2.length ≤ max := by
      cases hq : firstOcc d ((splitOn d (p ++ c)).2 ++ cs.flatten) with
      | none =>
        rw [splitOn_of_none hq] at hrest
        simp only [List.length_append] at hrest
        omega
      | some q =>
        have hb := firstOcc_some_bound hq
        have hlt := length_lt_of_firstOcc_append (firstOcc_splitOn_rest d (p ++ c)) hq
        rw [splitOn_of_some hq] at hmargin
        have := hmargin _ (List.mem_append_right _ (List.mem_cons_self))
        simp only [List.length_take] at this
        omega
    rw [chunkSpec_cons_ok ⟨hframes, hnew⟩, prepend_ending]
    exact chunkSpec_ne_tooLong_of_margin hd cs _ (firstOcc_splitOn_rest d _) hnew
      (fun f hf => hmargin f (List.mem_append_right _ hf)) hrest

/-- **The edge is the only difference**: with that margin, `chunkSpec` is the model. -/
theorem chunkSpec_eq_reference_of_margin {d : List α} {max : Nat} {t : Bool} (hd : d ≠ [])
    (cs : List (List α))
    (hmargin : ∀ f ∈ (splitOn d cs.flatten).1, f.length + d.length ≤ max + 1)
    (hrest : (splitOn d cs.flatten).2.length ≤ max) :
    chunkSpec d max t [] cs = reference d max t cs.flatten := by
  have hp : firstOcc d ([] : List α) = none := by simp [firstOcc]
  have := chunkSpec_eq_reference_of_ne_tooLong (t := t) cs [] hp (Nat.zero_le _)
    (chunkSpec_ne_tooLong_of_margin hd cs [] hp (Nat.zero_le _) (by simpa using hmargin)
      (by simpa using hrest))
  simpa using this

end WsClientProofs.Framing
