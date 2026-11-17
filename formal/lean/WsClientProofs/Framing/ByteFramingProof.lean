import WsClientProofs.Framing.ChunkSpecProofs
import WsClientProofs.Framing.ScanProofs
import WsClientProofs.Framing.ByteFramingModel

/-!
# `ByteFraming.DelimiterFramer` computes `chunkSpec`

The invariant (`Inv`): `start ≤ scanFrom ≤ end ≤ buffer.length`, and no delimiter starts in
the pending bytes `buffer[start, end)` before `scanFrom` - every offset skipped has room for
the whole delimiter inside the pending bytes. The lemmas follow the methods: the search
(`indexOfDelimiter`), the frame loop, the three blocks of `append` (compaction, growth, copy),
`push`, and the stage.
-/

namespace WsClientProofs.Framing.ByteFramingModel

open WsClientProofs.Java WsClientProofs.Framing

set_option linter.unusedSectionVars false

variable {α : Type} [DecidableEq α]

/-- The bytes not yet framed. -/
def pending (s : State α) : List α := copyOfRange s.buffer s.start s.end_

structure Inv (d : List α) (s : State α) : Prop where
  start_le : s.start ≤ s.scanFrom
  scan_le : s.scanFrom ≤ s.end_
  end_le : s.end_ ≤ s.buffer.length
  skip : ∀ q, q < s.scanFrom - s.start →
    q + d.length ≤ (pending s).length ∧ ¬ OccursAt d (pending s) q

theorem length_pending {s : State α} (h : s.end_ ≤ s.buffer.length) :
    (pending s).length = s.end_ - s.start := length_copyOfRange h

theorem indexOfDelimiter_some_spec {d : List α} (hd : d ≠ []) {s s' : State α} {i : Nat}
    (hinv : Inv d s) (h : indexOfDelimiter d s = (some i, s')) :
    s' = s ∧ s.start ≤ i ∧ i + d.length ≤ s.end_ ∧
      firstOcc d (pending s) = some (i - s.start) := by
  unfold indexOfDelimiter at h
  split at h
  · rename_i j hj
    simp only [Prod.mk.injEq, Option.some.injEq] at h
    obtain ⟨rfl, rfl⟩ := h
    obtain ⟨h1, h2, h3, h4⟩ := scan_eq_some hj
    have hlen := length_pending hinv.end_le
    refine ⟨rfl, by omega, h2, (firstOcc_eq_some_iff hd).mpr ⟨?_, ?_⟩⟩
    · rw [pending, occursAt_copyOfRange (by omega)]
      rwa [show s.start + (j - s.start) = j by omega]
    · intro q hq hocc
      by_cases hs : q < s.scanFrom - s.start
      · exact (hinv.skip q hs).2 hocc
      · rw [pending, occursAt_copyOfRange (by omega)] at hocc
        exact h4 (s.start + q) (by omega) (by omega) hocc
  · simp at h

theorem indexOfDelimiter_none_spec {d : List α} (hd : d ≠ []) {s s' : State α}
    (hinv : Inv d s) (h : indexOfDelimiter d s = (none, s')) :
    s' = { s with scanFrom := max s.start (s.end_ + 1 - d.length) } ∧
      firstOcc d (pending s) = none ∧ Inv d s' := by
  unfold indexOfDelimiter at h
  split at h
  · simp at h
  · rename_i hj
    simp only [Prod.mk.injEq, true_and] at h
    subst h
    have hlen := length_pending hinv.end_le
    have hdpos := List.length_pos_iff.mpr hd
    have hnone : firstOcc d (pending s) = none := by
      rw [firstOcc_eq_none_iff hd]
      intro q hocc
      have hq := occursAt_length hd hocc
      by_cases hs : q < s.scanFrom - s.start
      · exact (hinv.skip q hs).2 hocc
      · rw [pending, occursAt_copyOfRange (by omega)] at hocc
        exact scan_eq_none hj (s.start + q) (by omega) (by omega) hocc
    refine ⟨rfl, hnone, ?_⟩
    have hp : pending (⟨s.buffer, s.start, s.end_, max s.start (s.end_ + 1 - d.length)⟩ :
        State α) = pending s := rfl
    have h1 := hinv.start_le
    have h2 := hinv.scan_le
    exact {
      start_le := by simp only; omega
      scan_le := by simp only; omega
      end_le := hinv.end_le
      skip := by
        intro q hq
        rw [hp]
        simp only at hq
        exact ⟨by omega, (firstOcc_eq_none_iff hd).mp hnone q⟩ }

/-- The frame loop emits the frames of the pending bytes, in order, or fails at an oversized
one; it leaves the rest of the split pending. -/
theorem frameLoop_spec {d : List α} (hd : d ≠ []) {max : Nat} (s : State α)
    (acc : List (List α)) :
    Inv d s →
    if ∀ f ∈ (splitOn d (pending s)).1, f.length ≤ max then
      ∃ s', frameLoop d hd max s acc = .ok (s', (splitOn d (pending s)).1.reverse ++ acc) ∧
        Inv d s' ∧ pending s' = (splitOn d (pending s)).2 ∧ s'.buffer = s.buffer ∧
        s'.end_ = s.end_
    else frameLoop d hd max s acc = .error .frameTooLong := by
  fun_induction frameLoop d hd max s acc with
  | case1 s acc s' h =>
    intro hinv
    obtain ⟨rfl, hnone, hinv'⟩ := indexOfDelimiter_none_spec hd hinv h
    rw [splitOn_of_none hnone]
    simp only [List.not_mem_nil, false_implies, implies_true, if_true, List.reverse_nil,
      List.nil_append]
    exact ⟨_, rfl, hinv', rfl, rfl, rfl⟩
  | case2 s acc delimiterAt s' h hlong =>
    intro hinv
    obtain ⟨hs, h1, h2, hfirst⟩ := indexOfDelimiter_some_spec hd hinv h
    subst s'
    rw [splitOn_of_some hfirst, if_neg]
    intro hall
    have := hall _ List.mem_cons_self
    rw [List.length_take, length_pending hinv.end_le] at this
    omega
  | case3 s acc delimiterAt s' h hok ih =>
    intro hinv
    obtain ⟨hs, h1, h2, hfirst⟩ := indexOfDelimiter_some_spec hd hinv h
    subst s'
    have hlen := length_pending hinv.end_le
    have hinv1 : Inv d ⟨s.buffer, delimiterAt + d.length, s.end_, delimiterAt + d.length⟩ := {
      start_le := Nat.le_refl _
      scan_le := h2
      end_le := hinv.end_le
      skip := by intro q hq; simp at hq }
    have hp1 : pending (⟨s.buffer, delimiterAt + d.length, s.end_, delimiterAt + d.length⟩ :
        State α) = (pending s).drop (delimiterAt - s.start + d.length) := by
      simp only [pending, drop_copyOfRange]
      congr 1; omega
    have hframe : copyOfRange s.buffer s.start delimiterAt =
        (pending s).take (delimiterAt - s.start) := by
      rw [pending, take_copyOfRange (by omega)]
      congr 1; omega
    have hhead : ((pending s).take (delimiterAt - s.start)).length ≤ max := by
      rw [List.length_take]; omega
    have := ih hinv1
    rw [hp1] at this
    rw [splitOn_of_some hfirst]
    by_cases hall : ∀ f ∈ (splitOn d ((pending s).drop (delimiterAt - s.start + d.length))).1,
        f.length ≤ max
    · rw [if_pos hall] at this
      obtain ⟨s'', heq, hinv'', hp'', hb, he⟩ := this
      rw [if_pos (by
        intro f hf
        rcases List.mem_cons.mp hf with rfl | hf
        · exact hhead
        · exact hall f hf)]
      refine ⟨s'', ?_, hinv'', hp'', hb, he⟩
      rw [heq, hframe]
      simp
    · rw [if_neg hall] at this
      rw [if_neg (fun h' => hall (fun f hf => h' f (List.mem_cons_of_mem _ hf)))]
      exact this

theorem compact_spec {d : List α} {s : State α} {length : Nat} (hinv : Inv d s) :
    (compact s length).buffer.length = s.buffer.length ∧ Inv d (compact s length) ∧
      pending (compact s length) = pending s ∧ (compact s length).end_ ≤ s.end_ := by
  have h1 := hinv.start_le
  have h2 := hinv.scan_le
  have h3 := hinv.end_le
  unfold compact
  split
  · have hbuf : (arraycopy s.buffer s.start s.buffer 0 (s.end_ - s.start)).length =
        s.buffer.length := length_arraycopy (by omega) (by omega)
    have hp : pending (⟨arraycopy s.buffer s.start s.buffer 0 (s.end_ - s.start), 0,
        s.end_ - s.start, s.scanFrom - s.start⟩ : State α) = pending s := by
      simp only [pending, copyOfRange, arraycopy, List.take_zero, List.nil_append,
        List.drop_zero, Nat.sub_zero, Nat.zero_add]
      rw [List.take_append_of_le_length (by simp; omega), List.take_take]
      congr 1; omega
    refine ⟨hbuf, ⟨by simp, by simp only; omega, by simp only [hbuf]; omega, ?_⟩, hp, by simp⟩
    intro q hq
    rw [hp]
    exact hinv.skip q (by simpa using hq)
  · exact ⟨rfl, hinv, rfl, Nat.le_refl _⟩

theorem grow_spec [Inhabited α] {d : List α} {s : State α} {length : Nat} (hinv : Inv d s)
    (hbound : s.end_ + length ≤ intMax - 8) :
    ∃ s', grow s length = .ok s' ∧ Inv d s' ∧ pending s' = pending s ∧ s'.end_ = s.end_ ∧
      s'.end_ + length ≤ s'.buffer.length := by
  have h1 := hinv.start_le
  have h2 := hinv.scan_le
  have h3 := hinv.end_le
  unfold grow
  split
  · rw [if_neg (by omega)]
    refine ⟨_, rfl, ?_, ?_, rfl, ?_⟩
    · have hp : pending (⟨copyOf s.buffer (max (s.end_ + length)
          (min (s.buffer.length * 2) (intMax - 8))), s.start, s.end_, s.scanFrom⟩ : State α) =
          pending s := by
        simp only [pending]
        rw [copyOfRange_eq_drop_take (by omega), copyOfRange_eq_drop_take (by omega),
          take_copyOf (by omega) (by omega)]
      exact ⟨h1, h2, by simp only [length_copyOf]; omega,
        fun q hq => by rw [hp]; exact hinv.skip q hq⟩
    · simp only [pending]
      rw [copyOfRange_eq_drop_take (by omega), copyOfRange_eq_drop_take (by omega),
        take_copyOf (by omega) (by omega)]
    · simp only [length_copyOf]; omega
  · exact ⟨s, rfl, hinv, rfl, rfl, by omega⟩

theorem copyIn_spec {d : List α} {s : State α} {c : List α} (hinv : Inv d s)
    (hroom : s.end_ + c.length ≤ s.buffer.length) :
    Inv d ⟨arraycopy c 0 s.buffer s.end_ c.length, s.start, s.end_ + c.length, s.scanFrom⟩ ∧
    pending (⟨arraycopy c 0 s.buffer s.end_ c.length, s.start, s.end_ + c.length,
      s.scanFrom⟩ : State α) = pending s ++ c := by
  have h1 := hinv.start_le
  have h2 := hinv.scan_le
  have hbuf : (arraycopy c 0 s.buffer s.end_ c.length).length = s.buffer.length :=
    length_arraycopy (by omega) hroom
  have hp : pending (⟨arraycopy c 0 s.buffer s.end_ c.length, s.start, s.end_ + c.length,
      s.scanFrom⟩ : State α) = pending s ++ c := by
    simp only [pending]
    rw [copyOfRange_eq_drop_take (by omega), copyOfRange_eq_drop_take (by omega)]
    simp only [arraycopy, List.drop_zero, List.take_length]
    rw [List.append_assoc, ← List.append_assoc (List.take s.end_ s.buffer),
      List.take_left' (by simp; omega),
      List.drop_append_of_le_length (by simp; omega)]
  refine ⟨⟨h1, by simp only; omega, by simp only [hbuf]; omega, ?_⟩, hp⟩
  intro q hq
  rw [hp]
  obtain ⟨hq1, hq2⟩ := hinv.skip q (by simpa using hq)
  exact ⟨by simp; omega, fun h => hq2 ((occursAt_append_iff_of_le hq1).mp h)⟩

theorem append_spec [Inhabited α] {d : List α} {s : State α} {c : List α} (hinv : Inv d s)
    (hbound : s.end_ + c.length ≤ intMax - 8) :
    ∃ s', append s c = .ok s' ∧ Inv d s' ∧ pending s' = pending s ++ c ∧
      s'.end_ ≤ s.end_ + c.length := by
  simp only [append]
  split
  · obtain ⟨_, hinv1, hp1, he1⟩ := compact_spec (length := c.length) hinv
    obtain ⟨s2, hg, hinv2, hp2, he2, hroom⟩ :=
      grow_spec (length := c.length) hinv1 (by omega)
    obtain ⟨hinv3, hp3⟩ := copyIn_spec hinv2 hroom
    refine ⟨_, by rw [hg]; rfl, hinv3, by rw [hp3, hp2, hp1], by simp only; omega⟩
  · rename_i hlen
    have : c = [] := List.eq_nil_of_length_eq_zero (by omega)
    subst this
    exact ⟨s, rfl, hinv, by simp, by simp⟩

/-- One chunk: `push` emits the chunk's frames or fails exactly when `chunkSpec` does. -/
theorem push_spec [Inhabited α] {d : List α} (hd : d ≠ []) {max : Nat} {s : State α}
    {c : List α} (hinv : Inv d s) (hbound : s.end_ + c.length ≤ intMax - 8) :
    if (∀ f ∈ (splitOn d (pending s ++ c)).1, f.length ≤ max) ∧
        (splitOn d (pending s ++ c)).2.length ≤ max then
      ∃ s', push d hd max s c = .ok (s', (splitOn d (pending s ++ c)).1) ∧ Inv d s' ∧
        pending s' = (splitOn d (pending s ++ c)).2 ∧ s'.end_ ≤ s.end_ + c.length
    else ∃ e, push d hd max s c = .error e ∧ e.ending = .tooLong := by
  obtain ⟨s1, ha, hinv1, hp1, he1⟩ := append_spec hinv hbound
  have hloop := frameLoop_spec hd (max := max) s1 [] hinv1
  rw [hp1] at hloop
  by_cases hall : ∀ f ∈ (splitOn d (pending s ++ c)).1, f.length ≤ max
  · rw [if_pos hall] at hloop
    obtain ⟨s2, hl, hinv2, hp2, _, he2⟩ := hloop
    have hlen2 := length_pending hinv2.end_le
    by_cases hrest : (splitOn d (pending s ++ c)).2.length ≤ max
    · rw [if_pos ⟨hall, hrest⟩]
      refine ⟨s2, ?_, hinv2, hp2, by omega⟩
      simp only [push, ha, hl, bind, Except.bind, pure, Except.pure, List.append_nil,
        List.reverse_reverse]
      rw [if_neg (by rw [← hp2] at hrest; omega)]
    · rw [if_neg (fun h => hrest h.2)]
      refine ⟨.restTooLong, ?_, rfl⟩
      simp only [push, ha, hl, bind, Except.bind]
      rw [if_pos (by rw [← hp2] at hrest; omega)]
  · rw [if_neg hall] at hloop
    rw [if_neg (fun h => hall h.1)]
    refine ⟨.frameTooLong, ?_, rfl⟩
    simp only [push, ha, hloop, bind, Except.bind]

theorem runFrom_spec [Inhabited α] {d : List α} (hd : d ≠ []) {max : Nat} {t : Bool} :
    ∀ (cs : List (List α)) (s : State α), Inv d s →
      s.end_ + cs.flatten.length ≤ intMax - 8 →
      runFrom d hd max t s cs = chunkSpec d max t (pending s) cs
  | [], s, hinv, _ => by
    have h1 := hinv.start_le
    have h2 := hinv.scan_le
    have hlen := length_pending hinv.end_le
    by_cases he : s.end_ = s.start
    · have hp : (pending s).isEmpty = true := by
        rw [List.isEmpty_iff_length_eq_zero]; omega
      simp [runFrom, chunkSpec, finish, Framing.finish, he, hp]
    · have hne : ¬ pending s = [] := by
        intro h; rw [h] at hlen; simp at hlen; omega
      have hpend : copyOfRange s.buffer s.start s.end_ = pending s := rfl
      cases t <;> simp [runFrom, chunkSpec, finish, Framing.finish, he, hne, hpend,
        Failure.ending]
  | c :: cs, s, hinv, hbound => by
    simp only [List.flatten_cons, List.length_append] at hbound
    have hpush := push_spec hd (max := max) (c := c) hinv (by omega)
    by_cases hok : (∀ f ∈ (splitOn d (pending s ++ c)).1, f.length ≤ max) ∧
        (splitOn d (pending s ++ c)).2.length ≤ max
    · rw [if_pos hok] at hpush
      obtain ⟨s', hp, hinv', hpend, he⟩ := hpush
      rw [runFrom, hp, chunkSpec_cons_ok hok]
      simp only
      rw [runFrom_spec hd cs s' hinv' (by omega), hpend]
    · rw [if_neg hok] at hpush
      obtain ⟨e, hp, hend⟩ := hpush
      rw [runFrom, hp, chunkSpec_cons_fail hok]
      simp only [hend]

theorem inv_init [Inhabited α] (d : List α) : Inv d (init : State α) :=
  ⟨Nat.le_refl 0, Nat.le_refl 0, Nat.zero_le _, fun q hq => absurd hq (Nat.not_lt_zero q)⟩

theorem pending_init [Inhabited α] : pending (init : State α) = [] := rfl

/-- **`ByteFraming.delimiter` computes `chunkSpec`**, for any input shorter than
`Int.MaxValue - 8` bytes. -/
theorem run_eq_chunkSpec [Inhabited α] {d : List α} (hd : d ≠ []) (max : Nat) (t : Bool)
    (cs : List (List α)) (hbound : cs.flatten.length ≤ intMax - 8) :
    run d hd max t cs = chunkSpec d max t [] cs := by
  have := runFrom_spec hd (max := max) (t := t) cs init (inv_init d) (by show 0 + cs.flatten.length ≤ intMax - 8; omega)
  rwa [pending_init] at this

end WsClientProofs.Framing.ByteFramingModel
