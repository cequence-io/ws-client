import WsClientProofs.Framing.ChunkSpecProofs
import WsClientProofs.Framing.ScanProofs
import WsClientProofs.Framing.EngineFramerModel

/-!
# `StreamTransformers.DelimiterFramer` computes `chunkSpec`

Between chunks the buffer is exactly the pending bytes, and no delimiter starts before
`scanFrom` (`EInv`). Within `onInput` the frame loop moves `start` through the buffer; the
skip property then holds from `start` on (`LoopInv`) - `scanFrom` stays in buffer coordinates
and may lag behind `start`.
-/

namespace WsClientProofs.Framing.EngineFramerModel

open WsClientProofs.Java WsClientProofs.Framing

set_option linter.unusedSectionVars false

variable {α : Type} [DecidableEq α]

/-- Between chunks. -/
def EInv (d : List α) (s : State α) : Prop :=
  ∀ k < s.scanFrom, k + d.length ≤ s.buffer.length ∧ ¬ OccursAt d s.buffer k

/-- Within the frame loop, from `start` on. -/
def LoopInv (d : List α) (s : State α) (start : Nat) : Prop :=
  ∀ k, start ≤ k → k < s.scanFrom → k + d.length ≤ s.buffer.length ∧ ¬ OccursAt d s.buffer k

theorem indexOfDelimiter_some_spec {d : List α} (hd : d ≠ []) {s s' : State α} {start i : Nat}
    (hinv : LoopInv d s start) (h : indexOfDelimiter d s start = (some i, s')) :
    s' = s ∧ start ≤ i ∧ i + d.length ≤ s.buffer.length ∧
      firstOcc d (s.buffer.drop start) = some (i - start) := by
  unfold indexOfDelimiter at h
  split at h
  · rename_i j hj
    simp only [Prod.mk.injEq, Option.some.injEq] at h
    obtain ⟨rfl, rfl⟩ := h
    obtain ⟨h1, h2, h3, h4⟩ := scan_eq_some hj
    refine ⟨rfl, by omega, h2, (firstOcc_eq_some_iff hd).mpr ⟨?_, ?_⟩⟩
    · rw [occursAt_drop, show start + (j - start) = j by omega]; exact h3
    · intro q hq hocc
      rw [occursAt_drop] at hocc
      by_cases hs : start + q < s.scanFrom
      · exact (hinv (start + q) (by omega) hs).2 hocc
      · exact h4 (start + q) (by omega) (by omega) hocc
  · simp at h

theorem indexOfDelimiter_none_spec {d : List α} (hd : d ≠ []) {s s' : State α} {start : Nat}
    (hinv : LoopInv d s start) (h : indexOfDelimiter d s start = (none, s')) :
    s' = ⟨s.buffer, s.buffer.length + 1 - d.length⟩ ∧
      firstOcc d (s.buffer.drop start) = none := by
  unfold indexOfDelimiter at h
  split at h
  · simp at h
  · rename_i hj
    simp only [Prod.mk.injEq, true_and] at h
    subst h
    refine ⟨rfl, (firstOcc_eq_none_iff hd).mpr fun q hocc => ?_⟩
    have hq := occursAt_length hd hocc
    have hdpos := List.length_pos_iff.mpr hd
    simp only [List.length_drop] at hq
    rw [occursAt_drop] at hocc
    by_cases hs : start + q < s.scanFrom
    · exact (hinv (start + q) (by omega) hs).2 hocc
    · exact scan_eq_none hj (start + q) (by omega) (by omega) hocc

/-- The frame loop emits the frames of `buffer[start, ..)`, in order, or fails at an
oversized one; it ends where the rest of the split begins, with `scanFrom` past the
ruled-out bytes. -/
theorem frameLoop_spec {d : List α} (hd : d ≠ []) {max : Nat} (s : State α) (start : Nat)
    (acc : List (List α)) :
    LoopInv d s start → start ≤ s.buffer.length →
    if ∀ f ∈ (splitOn d (s.buffer.drop start)).1, f.length ≤ max then
      ∃ start', frameLoop d hd max s start acc =
          .ok (⟨s.buffer, s.buffer.length + 1 - d.length⟩, start',
            acc ++ (splitOn d (s.buffer.drop start)).1) ∧
        s.buffer.drop start' = (splitOn d (s.buffer.drop start)).2 ∧
        start' ≤ s.buffer.length
    else frameLoop d hd max s start acc = .error .frameTooLong := by
  fun_induction frameLoop d hd max s start acc with
  | case1 s start acc s' h =>
    intro hinv hstart
    obtain ⟨rfl, hnone⟩ := indexOfDelimiter_none_spec hd hinv h
    rw [splitOn_of_none hnone]
    simp only [List.not_mem_nil, false_implies, implies_true, if_true, List.append_nil]
    exact ⟨start, rfl, rfl, hstart⟩
  | case2 s start acc i s' h hlong =>
    intro hinv hstart
    obtain ⟨hs, h1, h2, hfirst⟩ := indexOfDelimiter_some_spec hd hinv h
    subst s'
    rw [splitOn_of_some hfirst, if_neg]
    intro hall
    have := hall _ List.mem_cons_self
    rw [List.length_take, List.length_drop] at this
    omega
  | case3 s start acc i s' h hok ih =>
    intro hinv hstart
    obtain ⟨hs, h1, h2, hfirst⟩ := indexOfDelimiter_some_spec hd hinv h
    subst s'
    have hinv1 : LoopInv d s (i + d.length) := fun k hk1 hk2 => hinv k (by omega) hk2
    have hdrop : (s.buffer.drop start).drop (i - start + d.length) =
        s.buffer.drop (i + d.length) := by
      rw [List.drop_drop]; congr 1; omega
    have hhead : ((s.buffer.drop start).take (i - start)).length ≤ max := by
      rw [List.length_take]; omega
    have := ih hinv1 h2
    rw [splitOn_of_some hfirst, hdrop]
    by_cases hall : ∀ f ∈ (splitOn d (s.buffer.drop (i + d.length))).1, f.length ≤ max
    · rw [if_pos hall] at this
      obtain ⟨start', heq, hrest, hle⟩ := this
      rw [if_pos (by
        intro f hf
        rcases List.mem_cons.mp hf with rfl | hf
        · exact hhead
        · exact hall f hf)]
      refine ⟨start', ?_, hrest, hle⟩
      rw [heq]
      simp [copyOfRange]
    · rw [if_neg hall] at this
      rw [if_neg (fun h' => hall (fun f hf => h' f (List.mem_cons_of_mem _ hf)))]
      exact this

/-- The compaction at the end of `onInput`, either way. -/
theorem compacted {b : List α} {start' sf : Nat} (h : start' ≤ b.length) :
    (if start' > 0 then
        ({ buffer := copyOfRange b start' b.length, scanFrom := sf - start' } : State α)
      else ⟨b, sf⟩) = ⟨b.drop start', sf - start'⟩ := by
  split
  · simp only [copyOfRange, State.mk.injEq, and_true]
    exact List.take_of_length_le (by simp)
  · rename_i h0
    have : start' = 0 := by omega
    subst this
    simp

/-- One chunk: `onInput` emits the chunk's frames or fails exactly when `chunkSpec` does. -/
theorem onInput_spec {d : List α} (hd : d ≠ []) {max : Nat} {s : State α} {c : List α}
    (hinv : EInv d s) :
    if (∀ f ∈ (splitOn d (s.buffer ++ c)).1, f.length ≤ max) ∧
        (splitOn d (s.buffer ++ c)).2.length ≤ max then
      ∃ s', onInput d hd max s c = .ok (s', (splitOn d (s.buffer ++ c)).1) ∧ EInv d s' ∧
        s'.buffer = (splitOn d (s.buffer ++ c)).2
    else ∃ e, onInput d hd max s c = .error e := by
  have hinv1 : LoopInv d ⟨s.buffer ++ c, s.scanFrom⟩ 0 := fun k _ hk => by
    obtain ⟨h1, h2⟩ := hinv k hk
    exact ⟨by simp; omega, fun h => h2 ((occursAt_append_iff_of_le h1).mp h)⟩
  have hloop := frameLoop_spec hd (max := max) ⟨s.buffer ++ c, s.scanFrom⟩ 0 [] hinv1
    (Nat.zero_le _)
  simp only [List.drop_zero] at hloop
  by_cases hall : ∀ f ∈ (splitOn d (s.buffer ++ c)).1, f.length ≤ max
  · rw [if_pos hall] at hloop
    obtain ⟨start', hl, hrest, hle⟩ := hloop
    have hcomp := compacted (sf := (s.buffer ++ c).length + 1 - d.length) hle
    have hnone := firstOcc_splitOn_rest d (s.buffer ++ c)
    by_cases hr : (splitOn d (s.buffer ++ c)).2.length ≤ max
    · rw [if_pos ⟨hall, hr⟩]
      refine ⟨⟨(s.buffer ++ c).drop start', (s.buffer ++ c).length + 1 - d.length - start'⟩,
        ?_, ?_, hrest⟩
      · simp only [onInput, hl, bind, Except.bind, pure, Except.pure, List.nil_append]
        rw [hcomp, if_neg (by simp only; rw [hrest]; omega)]
      · intro k hk
        simp only at hk ⊢
        rw [hrest]
        have := congrArg List.length hrest
        simp only [List.length_drop] at this
        exact ⟨by omega, (firstOcc_eq_none_iff hd).mp hnone k⟩
    · rw [if_neg (fun h => hr h.2)]
      refine ⟨.restTooLong, ?_⟩
      simp only [onInput, hl, bind, Except.bind, List.nil_append]
      rw [hcomp, if_pos (by simp only; rw [hrest]; omega)]
  · rw [if_neg hall] at hloop
    rw [if_neg (fun h => hall h.1)]
    refine ⟨.frameTooLong, ?_⟩
    simp only [onInput, hloop, bind, Except.bind]

theorem runFrom_spec {d : List α} (hd : d ≠ []) {max : Nat} :
    ∀ (cs : List (List α)) (s : State α), EInv d s →
      runFrom d hd max s cs = chunkSpec d max true s.buffer cs
  | [], s, _ => by
    simp only [runFrom, chunkSpec, onUpstreamComplete, Framing.finish]
    split <;> simp
  | c :: cs, s, hinv => by
    have hstep := onInput_spec hd (max := max) (c := c) hinv
    by_cases hok : (∀ f ∈ (splitOn d (s.buffer ++ c)).1, f.length ≤ max) ∧
        (splitOn d (s.buffer ++ c)).2.length ≤ max
    · rw [if_pos hok] at hstep
      obtain ⟨s', hs, hinv', hbuf⟩ := hstep
      rw [runFrom, hs, chunkSpec_cons_ok hok]
      simp only
      rw [runFrom_spec hd cs s' hinv', hbuf]
    · rw [if_neg hok] at hstep
      obtain ⟨e, hs⟩ := hstep
      rw [runFrom, hs, chunkSpec_cons_fail hok]

/-- **The engines' `DelimiterFramer` computes `chunkSpec`** (with `allowTruncation`). -/
theorem run_eq_chunkSpec {d : List α} (hd : d ≠ []) (max : Nat) (cs : List (List α)) :
    run d hd max cs = chunkSpec d max true [] cs :=
  runFrom_spec hd cs init (fun k hk => absurd hk (Nat.not_lt_zero k))

end WsClientProofs.Framing.EngineFramerModel
