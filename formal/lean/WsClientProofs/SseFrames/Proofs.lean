import WsClientProofs.Framing.SplitLemmas
import WsClientProofs.SseFrames.Model

/-!
# SSE frames: the code is the line rules

`parseFrame_eq_reference`: for every frame, item prefix and prefix / suffix to strip,
`parseFrame` computes exactly what `reference` - the scaladoc's rules on the frame's lines -
does, provided the field prefix (the item prefix without its trailing space) contains no line
break and does not start with `:` (true for the default `data: `). That covers the two places
where the code departs from the line structure:

* the fast path: a frame without a comment line or a CR is not split at all - its content is
  the frame, and the field is found in it directly;
* the field search runs on the joined content - `startsWith(fieldPrefix)` or
  `indexOf("\n" + fieldPrefix)` - not on the lines; it finds the first line starting with
  the field, and in a raw payload it finds nothing.
-/

namespace WsClientProofs.SseFrames

open WsClientProofs.Framing (firstOcc firstOcc_eq_some_iff firstOcc_eq_none_iff OccursAt)

/-! ## Text helpers -/

theorem startsWith_iff {s p : List Char} : startsWith s p = true ↔ p <+: s :=
  List.isPrefixOf_iff_prefix

theorem dropWhile_eq_nil_iff {p : Char → Bool} {l : List Char} :
    l.dropWhile p = [] ↔ ∀ c ∈ l, p c = true := by
  induction l with
  | nil => simp
  | cons c cs ih =>
    rw [List.dropWhile_cons]
    split
    · rename_i h; rw [ih]; simp [h]
    · rename_i h; simp [h]

theorem dropWhile_head {p : Char → Bool} {l xs : List Char} {x : Char}
    (h : l.dropWhile p = x :: xs) : p x = false := by
  induction l with
  | nil => simp at h
  | cons c cs ih =>
    rw [List.dropWhile_cons] at h
    split at h
    · exact ih h
    · rename_i hc; simp only [List.cons.injEq] at h; rw [← h.1]; simpa using hc

theorem trim_isEmpty_iff {s : List Char} : (trim s).isEmpty = true ↔ ∀ c ∈ s, c ≤ ' ' := by
  simp only [trim, List.isEmpty_iff, List.reverse_eq_nil_iff, dropWhile_eq_nil_iff,
    List.mem_reverse, decide_eq_true_eq]
  constructor
  · intro h
    cases hD : s.dropWhile (fun c => decide (c ≤ ' ')) with
    | nil => exact fun c hc => by simpa using dropWhile_eq_nil_iff.mp hD c hc
    | cons x xs =>
      exfalso
      have hx := dropWhile_head hD
      simp only [decide_eq_false_iff_not] at hx
      exact hx (h x (by rw [hD]; simp))
  · intro h c hc
    exact h c ((List.dropWhile_suffix _).subset hc)

/-! ## Lines -/

theorem splitLines_ne_nil (s : List Char) : splitLines s ≠ [] := by
  fun_induction splitLines s <;> simp

/-- The first line of a text. -/
def headLine (s : List Char) : List Char := (splitLines s).headD []

theorem splitLines_lines (s : List Char) :
    ∀ l ∈ splitLines s, ∀ c ∈ l, c ≠ '\r' ∧ c ≠ '\n' := by
  fun_induction splitLines s with
  | case1 => simp
  | case2 rest ih => simpa using ih
  | case3 rest _ ih => simpa using ih
  | case4 rest ih => simpa using ih
  | case5 c rest _ h1 h2 l ls hs ih =>
    rw [hs] at ih
    intro l' hl' x hx
    simp only [List.mem_cons] at hl'
    rcases hl' with rfl | hl'
    · simp only [List.mem_cons] at hx
      rcases hx with rfl | hx
      · exact ⟨h1, h2⟩
      · exact ih l (by simp) x hx
    · exact ih l' (by simp [hl']) x hx
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

theorem joinLines_cons_cons (l m : List Char) (ms : List (List Char)) :
    joinLines (l :: m :: ms) = l ++ '\n' :: joinLines (m :: ms) := rfl

theorem joinLines_cons {l : List Char} {ls : List (List Char)} (h : ls ≠ []) :
    joinLines (l :: ls) = l ++ '\n' :: joinLines ls := by
  cases ls with
  | nil => exact absurd rfl h
  | cons m ms => rfl

theorem joinLines_consHead (c : Char) (l : List Char) (ls : List (List Char)) :
    joinLines ((c :: l) :: ls) = c :: joinLines (l :: ls) := by
  cases ls <;> rfl

theorem joinLines_splitLines (s : List Char) :
    (∀ c ∈ s, c ≠ '\r') → joinLines (splitLines s) = s := by
  fun_induction splitLines s with
  | case1 => intro _; rfl
  | case2 rest _ => intro h; exact absurd rfl (h '\r' (by simp))
  | case3 rest _ _ => intro h; exact absurd rfl (h '\r' (by simp))
  | case4 rest ih =>
    intro h
    rw [joinLines_cons (splitLines_ne_nil rest), ih (fun c hc => h c (by simp [hc]))]
    rfl
  | case5 c rest _ _ _ l ls hs ih =>
    intro h
    rw [joinLines_consHead, ← hs, ih (fun c hc => h c (by simp [hc]))]
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

theorem blank_splitLines (s : List Char) :
    (∀ c ∈ s, c ≤ ' ') ↔ ∀ l ∈ splitLines s, ∀ c ∈ l, c ≤ ' ' := by
  fun_induction splitLines s with
  | case1 => simp
  | case2 rest ih =>
    simp only [List.forall_mem_cons]
    rw [ih]
    simp (config := { decide := true })
  | case3 rest _ ih =>
    simp only [List.forall_mem_cons]
    rw [ih]
    simp (config := { decide := true })
  | case4 rest ih =>
    simp only [List.forall_mem_cons]
    rw [ih]
    simp (config := { decide := true })
  | case5 c rest _ _ _ l ls hs ih =>
    rw [hs] at ih
    simp only [List.forall_mem_cons] at ih ⊢
    rw [ih]
    exact and_assoc.symm
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

theorem headLine_prefix (s : List Char) : headLine s <+: s := by
  unfold headLine
  fun_induction splitLines s with
  | case1 => simp
  | case2 rest _ => simp
  | case3 rest _ _ => simp
  | case4 rest _ => simp
  | case5 c rest _ _ _ l ls hs ih =>
    rw [hs] at ih
    simpa using ih
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

/-- A prefix without a line break is a prefix of the first line. -/
theorem prefix_headLine (s : List Char) :
    ∀ p : List Char, (∀ c ∈ p, c ≠ '\r' ∧ c ≠ '\n') → p <+: s → p <+: headLine s := by
  unfold headLine
  fun_induction splitLines s with
  | case1 => intro p _ hp; simpa using hp
  | case2 rest _ =>
    intro p hb hp
    cases p with
    | nil => simp
    | cons x xs =>
      have := (List.cons_prefix_cons.mp hp).1
      exact absurd this (hb x (by simp)).1
  | case3 rest _ _ =>
    intro p hb hp
    cases p with
    | nil => simp
    | cons x xs =>
      have := (List.cons_prefix_cons.mp hp).1
      exact absurd this (hb x (by simp)).1
  | case4 rest _ =>
    intro p hb hp
    cases p with
    | nil => simp
    | cons x xs =>
      have := (List.cons_prefix_cons.mp hp).1
      exact absurd this (hb x (by simp)).2
  | case5 c rest _ _ _ l ls hs ih =>
    rw [hs] at ih
    intro p hb hp
    cases p with
    | nil => simp
    | cons x xs =>
      obtain ⟨rfl, hxs⟩ := List.cons_prefix_cons.mp hp
      simpa using ih xs (fun y hy => hb y (by simp [hy])) hxs
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

theorem headLine_mem (s : List Char) : headLine s ∈ splitLines s := by
  unfold headLine
  cases h : splitLines s with
  | nil => exact absurd h (splitLines_ne_nil s)
  | cons l ls => simp

theorem isComment_headLine (s : List Char) : isComment (headLine s) = isComment s := by
  simp only [isComment]
  apply Bool.eq_iff_iff.mpr
  rw [startsWith_iff, startsWith_iff]
  constructor
  · intro h; exact h.trans (headLine_prefix s)
  · intro h; exact prefix_headLine s [':'] (by decide) h

theorem firstOcc_cons_lf (fp : List Char) (x : Char) (xs : List Char) :
    firstOcc ('\n' :: fp) (x :: xs) =
      if x = '\n' ∧ startsWith xs fp = true then some 0
      else (firstOcc ('\n' :: fp) xs).map (· + 1) := by
  simp only [Framing.firstOcc, List.isEmpty_cons, Bool.not_false, Bool.true_and,
    List.isPrefixOf, startsWith, Bool.and_eq_true, beq_iff_eq]
  by_cases hx : x = '\n'
  · subst hx; simp
  · simp [Ne.symm hx, hx]

/-- No line after the first starts with the field: `"\n" + fieldPrefix` does not occur. -/
theorem firstOcc_lf_none (fp : List Char) (hfp : ∀ c ∈ fp, c ≠ '\r' ∧ c ≠ '\n')
    (s : List Char) :
    (∀ l ∈ (splitLines s).tail, startsWith l fp = false) →
      firstOcc ('\n' :: fp) s = none := by
  fun_induction splitLines s with
  | case1 => intro _; rfl
  | case2 rest ih =>
    intro h
    simp only [List.tail_cons] at h
    rw [firstOcc_cons_lf, if_neg (by simp), firstOcc_cons_lf, if_neg]
    · rw [ih (fun l hl => h l (List.mem_of_mem_tail hl))]; rfl
    · intro ⟨_, hs⟩
      have := h (headLine rest) (headLine_mem rest)
      rw [Bool.eq_false_iff] at this
      exact this (startsWith_iff.mpr (prefix_headLine rest fp hfp (startsWith_iff.mp hs)))
  | case3 rest _ ih =>
    intro h
    simp only [List.tail_cons] at h
    rw [firstOcc_cons_lf, if_neg (by simp), ih (fun l hl => h l (List.mem_of_mem_tail hl))]
    rfl
  | case4 rest ih =>
    intro h
    simp only [List.tail_cons] at h
    rw [firstOcc_cons_lf, if_neg]
    · rw [ih (fun l hl => h l (List.mem_of_mem_tail hl))]; rfl
    · intro ⟨_, hs⟩
      have := h (headLine rest) (headLine_mem rest)
      rw [Bool.eq_false_iff] at this
      exact this (startsWith_iff.mpr (prefix_headLine rest fp hfp (startsWith_iff.mp hs)))
  | case5 c rest _ _ h2 l ls hs ih =>
    intro h
    simp only [List.tail_cons] at h
    rw [hs] at ih
    rw [firstOcc_cons_lf, if_neg (fun hc => h2 hc.1), ih (fun l hl => h l hl)]
    rfl
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

/-- Without a CR and without `"\n:"`, no line after the first is a comment. -/
theorem tail_not_comment (s : List Char) :
    (∀ c ∈ s, c ≠ '\r') → firstOcc ['\n', ':'] s = none →
      ∀ l ∈ (splitLines s).tail, isComment l = false := by
  fun_induction splitLines s with
  | case1 => intro _ _; simp
  | case2 rest _ => intro h; exact absurd rfl (h '\r' (by simp))
  | case3 rest _ _ => intro h; exact absurd rfl (h '\r' (by simp))
  | case4 rest ih =>
    intro hcr hocc
    rw [firstOcc_cons_lf] at hocc
    split at hocc
    · simp at hocc
    · rename_i hn
      have hrest : firstOcc ['\n', ':'] rest = none := by
        cases h : firstOcc ['\n', ':'] rest with
        | none => rfl
        | some _ => rw [h] at hocc; simp at hocc
      simp only [List.tail_cons]
      intro l hl
      have hcr' : ∀ c ∈ rest, c ≠ '\r' := fun c hc => hcr c (by simp [hc])
      cases hsplit : splitLines rest with
      | nil => exact absurd hsplit (splitLines_ne_nil rest)
      | cons l0 ls =>
        rw [hsplit] at hl
        rcases List.mem_cons.mp hl with rfl | hl
        · have h0 : l = headLine rest := by simp [headLine, hsplit]
          rw [h0, isComment_headLine]
          simp only [isComment]
          rw [Bool.eq_false_iff]
          intro hc
          exact hn ⟨rfl, hc⟩
        · exact ih hcr' hrest l (by rw [hsplit]; exact hl)
  | case5 c rest _ _ h2 l ls hs ih =>
    intro hcr hocc
    rw [firstOcc_cons_lf, if_neg (fun hc => h2 hc.1)] at hocc
    have hrest : firstOcc ['\n', ':'] rest = none := by
      cases h : firstOcc ['\n', ':'] rest with
      | none => rfl
      | some _ => rw [h] at hocc; simp at hocc
    rw [hs] at ih
    simp only [List.tail_cons]
    exact ih (fun c hc => hcr c (by simp [hc])) hrest
  | case6 c rest _ _ _ hs _ => exact absurd hs (splitLines_ne_nil rest)

/-! ## The field search on joined lines -/

/-- The code's field search on a content: the value after the field, if found. -/
def fieldValue (content fp : List Char) : Option (List Char) :=
  (if startsWith content fp then some 0 else (firstOcc ('\n' :: fp) content).map (· + 1)).map
    fun start => content.drop (start + fp.length)

theorem firstOcc_append_no_lf {fp l : List Char} (r : List Char) (hl : ∀ c ∈ l, c ≠ '\n') :
    firstOcc ('\n' :: fp) (l ++ r) = (firstOcc ('\n' :: fp) r).map (· + l.length) := by
  induction l with
  | nil => simp
  | cons c cs ih =>
    rw [List.cons_append, firstOcc_cons_lf, if_neg (fun h => hl c (by simp) h.1),
      ih (fun x hx => hl x (by simp [hx])), Option.map_map]
    cases firstOcc ('\n' :: fp) r with
    | none => rfl
    | some i => simp only [Option.map_some, Function.comp, List.length_cons]; congr 1

theorem drop_append_cons (l J : List Char) (x : Char) (k : Nat) :
    (l ++ x :: J).drop (l.length + 1 + k) = J.drop k := by
  induction l with
  | nil => simp [Nat.add_comm]
  | cons c cs ih =>
    simp only [List.cons_append, List.length_cons]
    rw [show cs.length + 1 + 1 + k = (cs.length + 1 + k) + 1 by omega, List.drop_succ_cons, ih]

/-- A field prefix without LF that is no prefix of `l` is no prefix of `l ++ "\n" ++ J`. -/
theorem not_prefix_append_lf {fp l J : List Char} (hfp : ∀ c ∈ fp, c ≠ '\n')
    (hl : ¬ fp <+: l) : ¬ fp <+: l ++ '\n' :: J := by
  intro h
  by_cases hlen : fp.length ≤ l.length
  · exact hl ((Framing.prefix_append_iff_of_length_le hlen).mp h)
  · rw [List.prefix_iff_eq_take] at h
    have : '\n' ∈ fp := by
      rw [h, List.take_append, List.take_of_length_le (by omega)]
      apply List.mem_append_right
      rw [show fp.length - l.length = (fp.length - l.length - 1) + 1 by omega]
      simp
    exact hfp '\n' this rfl

/-- **The field search on joined lines finds the first line starting with the field.** -/
theorem fieldValue_joinLines {fp : List Char} (hfp : ∀ c ∈ fp, c ≠ '\n') :
    ∀ (ls : List (List Char)), (∀ l ∈ ls, ∀ c ∈ l, c ≠ '\n') → ls ≠ [] →
      fieldValue (joinLines ls) fp = (firstField fp ls).map fun r => joinLines (r.1 :: r.2)
  | [], _, hne => absurd rfl hne
  | [l], hl, _ => by
    by_cases hs : startsWith l fp = true
    · simp [fieldValue, firstField, joinLines, hs]
    · have hnone : firstOcc ('\n' :: fp) l = none := by
        have := firstOcc_append_no_lf (fp := fp) [] (hl l (by simp))
        simpa [Framing.firstOcc] using this
      simp [fieldValue, firstField, joinLines, hs, hnone]
  | l :: m :: ms, hl, _ => by
    have hl0 : ∀ c ∈ l, c ≠ '\n' := hl l (by simp)
    have ih := fieldValue_joinLines hfp (m :: ms) (fun l' h => hl l' (by simp [h])) (by simp)
    rw [joinLines_cons_cons] at *
    by_cases hs : startsWith l fp = true
    · have hlen := (startsWith_iff.mp hs).length_le
      have hs' : startsWith (l ++ '\n' :: joinLines (m :: ms)) fp = true :=
        startsWith_iff.mpr ((startsWith_iff.mp hs).trans (List.prefix_append _ _))
      simp only [fieldValue, hs', if_true, Option.map_some, Nat.zero_add, firstField, hs]
      rw [List.drop_append_of_le_length hlen]
      rfl
    · have hs' : startsWith (l ++ '\n' :: joinLines (m :: ms)) fp = false := by
        rw [Bool.eq_false_iff]
        intro h
        exact not_prefix_append_lf hfp (fun h' => hs (startsWith_iff.mpr h'))
          (startsWith_iff.mp h)
      have hff : firstField fp (l :: m :: ms) = firstField fp (m :: ms) := by
        simp [firstField, hs]
      rw [hff, ← ih]
      simp only [fieldValue, hs', Bool.false_eq_true, if_false]
      rw [firstOcc_append_no_lf _ hl0, firstOcc_cons_lf]
      by_cases hJ : startsWith (joinLines (m :: ms)) fp = true
      · rw [if_pos ⟨rfl, hJ⟩, if_pos hJ]
        simp only [Option.map_some, Nat.zero_add]
        rw [drop_append_cons]
      · rw [if_neg (fun h => hJ h.2), if_neg hJ]
        cases firstOcc ('\n' :: fp) (joinLines (m :: ms)) with
        | none => rfl
        | some j =>
          simp only [Option.map_some]
          rw [show j + 1 + l.length + 1 + fp.length = l.length + 1 + (j + 1 + fp.length) by omega,
            drop_append_cons]

/-! ## Both functions, in named steps -/

/-- The one optional space after the field's colon. -/
def optSpace (fp itemPrefix value : List Char) : List Char :=
  if fp.length < itemPrefix.length && startsWith value [' '] then value.drop 1 else value

/-- The code's data from a content. -/
def dataOf (content fp itemPrefix : List Char) : List Char :=
  match (if startsWith content fp then some 0
      else (firstOcc ('\n' :: fp) content).map (· + 1)) with
  | some start => optSpace fp itemPrefix (content.drop (start + fp.length))
  | none => content

/-- The code's content. -/
def contentOf (frame fp : List Char) (needsLineScan : Bool) (lines allLines : List (List Char)) :
    List Char :=
  if !needsLineScan then frame
  else if lines.any (startsWith · fp) then joinLines lines
  else if isComment frame then joinLines (allLines.dropWhile isComment)
  else frame

/-- The rules' data. -/
def refData (frame fp itemPrefix : List Char) (lines kept : List (List Char)) : List Char :=
  match firstField fp kept with
  | some (value, after) => joinLines (optSpace fp itemPrefix value :: after)
  | none => if isComment frame then joinLines (lines.dropWhile isComment) else frame

theorem parseFrame_unfold (frame : List Char) (p q : Option (List Char)) (itemPrefix : List Char) :
    parseFrame frame p q itemPrefix =
      let fp := stripSuffix itemPrefix [' ']
      let nls := isComment frame || (firstOcc ['\n', ':'] frame).isSome || frame.contains '\r'
      let allLines := if nls then splitLines frame else [frame]
      let lines := if nls then allLines.filter (fun l => !isComment l) else allLines
      if lines.all (fun l => (trim l).isEmpty) then .skip
      else classify (dataOf (contentOf frame fp nls lines allLines) fp itemPrefix) p q := by
  rfl

theorem reference_unfold (frame : List Char) (p q : Option (List Char)) (itemPrefix : List Char) :
    reference frame p q itemPrefix =
      let fp := stripSuffix itemPrefix [' ']
      let lines := splitLines frame
      let kept := lines.filter (fun l => !isComment l)
      if kept.all blank then .skip
      else classify (refData frame fp itemPrefix lines kept) p q := by
  rfl

theorem dataOf_eq (content fp itemPrefix : List Char) :
    dataOf content fp itemPrefix =
      match fieldValue content fp with
      | some value => optSpace fp itemPrefix value
      | none => content := by
  unfold dataOf fieldValue
  split <;> rename_i h <;> simp [h]

theorem optSpace_joinLines (fp itemPrefix v : List Char) (rest : List (List Char)) :
    optSpace fp itemPrefix (joinLines (v :: rest)) = joinLines (optSpace fp itemPrefix v :: rest) := by
  cases rest with
  | nil => rfl
  | cons m ms =>
    simp only [optSpace, joinLines_cons_cons]
    cases v with
    | nil => simp [startsWith, List.isPrefixOf]
    | cons x xs =>
      by_cases hx : x = ' '
      · subst hx
        by_cases hl : fp.length < itemPrefix.length
        · simp [startsWith, List.isPrefixOf, hl]
        · simp [startsWith, List.isPrefixOf, hl]
      · have : (' ' = x) = False := propext ⟨fun h => hx h.symm, False.elim⟩
        simp [startsWith, List.isPrefixOf, this]

theorem firstField_eq_none_iff {fp : List Char} {ls : List (List Char)} :
    firstField fp ls = none ↔ ∀ l ∈ ls, startsWith l fp = false := by
  induction ls with
  | nil => simp [firstField]
  | cons l ls ih =>
    simp only [firstField]
    split
    · rename_i h; simp [h]
    · rename_i h; rw [ih]; simp [h]

/-! ## The theorem -/

theorem not_startsWith_of_comment {fp l : List Char} (hne : fp ≠ [])
    (hcolon : startsWith fp [':'] = false) (hc : isComment l = true) : startsWith l fp = false := by
  rw [Bool.eq_false_iff]
  intro h
  obtain ⟨x, xs, rfl⟩ := List.exists_cons_of_ne_nil hne
  simp only [isComment, startsWith_iff] at hc
  obtain ⟨l', rfl⟩ := hc
  have hp := startsWith_iff.mp h
  rw [List.cons_append, List.cons_prefix_cons] at hp
  rw [hp.1] at hcolon
  simp [startsWith, List.isPrefixOf] at hcolon

/-- **`parseFrame` is the line rules**, for every frame, every prefix / suffix to strip and
every item prefix whose field prefix has no line break and does not start with `:`. -/
theorem parseFrame_eq_reference (frame : List Char) (p q : Option (List Char))
    (itemPrefix : List Char)
    (hbreak : ∀ c ∈ stripSuffix itemPrefix [' '], c ≠ '\r' ∧ c ≠ '\n')
    (hcolon : startsWith (stripSuffix itemPrefix [' ']) [':'] = false) :
    parseFrame frame p q itemPrefix = reference frame p q itemPrefix := by
  rw [parseFrame_unfold, reference_unfold]
  simp only []
  generalize stripSuffix itemPrefix [' '] = fp at hbreak hcolon ⊢
  have hlf : ∀ c ∈ fp, c ≠ '\n' := fun c hc => (hbreak c hc).2
  have hLlf : ∀ l ∈ splitLines frame, ∀ c ∈ l, c ≠ '\n' :=
    fun l hl c hc => (splitLines_lines frame l hl c hc).2
  by_cases hn : (isComment frame || (firstOcc ['\n', ':'] frame).isSome ||
      frame.contains '\r') = true
  · rw [if_pos hn, if_pos hn]
    generalize hK : (splitLines frame).filter (fun l => !isComment l) = K
    by_cases hskip : (K.all fun l => (trim l).isEmpty) = true
    · rw [if_pos hskip, if_pos (by simpa [blank] using hskip)]
    · rw [if_neg hskip, if_neg (by simpa [blank] using hskip)]
      congr 1
      have hKne : K ≠ [] := by intro e; rw [e] at hskip; simp at hskip
      have hKmem : ∀ l ∈ K, l ∈ splitLines frame := fun l hl => by
        rw [← hK] at hl; exact (List.mem_filter.mp hl).1
      simp only [contentOf, hn, Bool.not_true, Bool.false_eq_true, if_false]
      by_cases hany : (K.any fun x => startsWith x fp) = true
      · rw [if_pos hany, dataOf_eq,
          fieldValue_joinLines hlf K (fun l hl => hLlf l (hKmem l hl)) hKne]
        cases hff : firstField fp K with
        | none =>
          rw [firstField_eq_none_iff] at hff
          obtain ⟨l, hl, hs⟩ := List.any_eq_true.mp hany
          rw [hff l hl] at hs
          exact absurd hs (by simp)
        | some r =>
          obtain ⟨v, after⟩ := r
          simp only [refData, hff, Option.map_some, optSpace_joinLines]
      · rw [if_neg hany]
        have hffn : firstField fp K = none := firstField_eq_none_iff.mpr fun l hl => by
          rw [Bool.eq_false_iff]; intro h; exact hany (List.any_eq_true.mpr ⟨l, hl, h⟩)
        have hfpne : fp ≠ [] := by
          intro e; subst e
          obtain ⟨l, hl⟩ := List.exists_mem_of_ne_nil K hKne
          exact hany (List.any_eq_true.mpr ⟨l, hl, by simp [startsWith]⟩)
        have hnot : ∀ l ∈ splitLines frame, startsWith l fp = false := by
          intro l hl
          by_cases hc : isComment l = true
          · exact not_startsWith_of_comment hfpne hcolon hc
          · have : l ∈ K := by rw [← hK]; exact List.mem_filter.mpr ⟨hl, by simpa using hc⟩
            exact firstField_eq_none_iff.mp hffn l this
        simp only [refData, hffn]
        by_cases hc : isComment frame = true
        · try simp only [hc, if_true]
          rw [dataOf_eq]
          have : fieldValue (joinLines ((splitLines frame).dropWhile isComment)) fp = none := by
            cases hD : (splitLines frame).dropWhile isComment with
            | nil =>
              have : startsWith [] fp = false := by
                obtain ⟨x, xs, rfl⟩ := List.exists_cons_of_ne_nil hfpne
                rfl
              simp [joinLines, fieldValue, this, Framing.firstOcc]
            | cons d ds =>
              have hsub : ∀ l ∈ (splitLines frame).dropWhile isComment, l ∈ splitLines frame :=
                fun l hl => (List.dropWhile_suffix _).subset hl
              rw [← hD, fieldValue_joinLines hlf _ (fun l hl => hLlf l (hsub l hl))
                (by rw [hD]; simp),
                firstField_eq_none_iff.mpr (fun l hl => hnot l (hsub l hl))]
              rfl
          rw [this]
        · try simp only [hc, Bool.false_eq_true, if_false]
          rw [dataOf_eq]
          have : fieldValue frame fp = none := by
            have hs : startsWith frame fp = false := by
              rw [Bool.eq_false_iff]; intro h
              have h1 := prefix_headLine frame fp hbreak (startsWith_iff.mp h)
              have h2 := hnot (headLine frame) (headLine_mem frame)
              rw [startsWith_iff.mpr h1] at h2
              exact absurd h2 (by simp)
            simp only [fieldValue, hs, Bool.false_eq_true, if_false]
            rw [firstOcc_lf_none fp hbreak frame (fun l hl => hnot l (List.mem_of_mem_tail hl))]
            rfl
          rw [this]
  · rw [if_neg hn, if_neg hn]
    have hn0 : (isComment frame || (firstOcc ['\n', ':'] frame).isSome ||
        frame.contains '\r') = false := Bool.eq_false_iff.mpr hn
    simp only [Bool.or_eq_true, not_or, Bool.not_eq_true] at hn
    obtain ⟨⟨hc, hocc⟩, hcr⟩ := hn
    have hocc : firstOcc ['\n', ':'] frame = none := by simpa using hocc
    have hcr : ∀ c ∈ frame, c ≠ '\r' := by
      intro c hc' e; subst e; simp at hcr; exact hcr hc'
    have hkept : (splitLines frame).filter (fun l => !isComment l) = splitLines frame := by
      rw [List.filter_eq_self]
      intro l hl
      have htail := tail_not_comment frame hcr hocc
      cases hs : splitLines frame with
      | nil => exact absurd hs (splitLines_ne_nil frame)
      | cons l0 ls =>
        rw [hs] at hl htail
        rcases List.mem_cons.mp hl with rfl | hl
        · have : l = headLine frame := by simp [headLine, hs]
          rw [this, isComment_headLine, hc]; rfl
        · simp [htail l hl]
    rw [hkept]
    have hjoin := joinLines_splitLines frame hcr
    have hblank : (trim frame).isEmpty = true ↔ ((splitLines frame).all blank) = true := by
      rw [trim_isEmpty_iff, blank_splitLines]
      simp only [List.all_eq_true, blank, trim_isEmpty_iff]
    by_cases hskip : (trim frame).isEmpty = true
    · rw [if_pos (by simpa using hskip), if_pos (hblank.mp hskip)]
    · rw [if_neg (by simpa using hskip), if_neg (fun h => hskip (hblank.mpr h))]
      congr 1
      simp only [contentOf, hn0, Bool.not_false, if_true]
      rw [dataOf_eq]
      have hfv := fieldValue_joinLines hlf (splitLines frame) hLlf (splitLines_ne_nil frame)
      rw [hjoin] at hfv
      rw [hfv]
      simp only [refData]
      cases hff : firstField fp (splitLines frame) with
      | none => simp [hc]
      | some r =>
        obtain ⟨v, after⟩ := r
        simp [optSpace_joinLines]

end WsClientProofs.SseFrames
