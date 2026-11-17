import WsClientProofs.Multipart.Model

/-!
# Multipart parameter values: the results

1. `escape_eq`: `MultipartParams.escape` is the WHATWG / RFC 7578 encoding, for every text
   (the order of the three `replace` calls does not matter: no replacement contains a
   replaced character).
2. `escape_safe`, `quoted_one_line`: an encoded value contains no `"`, CR or LF - a quoted
   value can never end early nor break the header line.
3. `readValue_quoted`, `readValue_dataPartName`: a reader takes back exactly the encoded value
   and leaves the rest of the header untouched - "a value can never leave its parameter".
4. `readQuotedRfc9110_of_no_backslash`: an RFC 9110 `quoted-string` reader agrees whenever the
   value has no backslash. With one, it does not: the encoding (like browsers') leaves `\` as
   it is, which RFC 9110 reads as an escape - `rfc9110_backslash_dropped`,
   `rfc9110_trailing_backslash`.
-/

namespace WsClientProofs.Multipart

open WsClientProofs.Java

theorem escape_eq (s : List Char) : escape s = escapeSpec s := by
  simp only [escape, escapeSpec, replace_single, List.flatMap_assoc]
  apply flatMap_congr'
  intro c _
  by_cases h1 : c = '"'
  · subst h1; decide
  · by_cases h2 : c = '\r'
    · subst h2; decide
    · by_cases h3 : c = '\n'
      · subst h3; decide
      · simp [h1, h2, h3]

theorem escape_safe {s : List Char} {c : Char} (h : c ∈ escape s) :
    c ≠ '"' ∧ c ≠ '\r' ∧ c ≠ '\n' := by
  rw [escape_eq, escapeSpec, List.mem_flatMap] at h
  obtain ⟨x, _, hc⟩ := h
  split at hc
  · simp only [List.mem_cons, List.not_mem_nil, or_false] at hc
    rcases hc with rfl | rfl | rfl <;> decide
  · split at hc
    · simp only [List.mem_cons, List.not_mem_nil, or_false] at hc
      rcases hc with rfl | rfl | rfl <;> decide
    · split at hc
      · simp only [List.mem_cons, List.not_mem_nil, or_false] at hc
        rcases hc with rfl | rfl | rfl <;> decide
      · simp only [List.mem_singleton] at hc
        subst hc
        exact ⟨by assumption, by assumption, by assumption⟩

theorem quoted_one_line {s : List Char} {c : Char} (h : c ∈ quoted s) : c ≠ '\r' ∧ c ≠ '\n' := by
  simp only [quoted, List.cons_append, List.mem_cons, List.mem_append, List.not_mem_nil,
    or_false] at h
  rcases h with rfl | h | rfl
  · decide
  · exact (escape_safe h).2
  · decide

theorem readQuoted_append {v rest : List Char} (h : ∀ c ∈ v, c ≠ '"') :
    readQuoted (v ++ '"' :: rest) = some (v, rest) := by
  induction v with
  | nil => simp [readQuoted]
  | cons c cs ih =>
    rw [List.cons_append, readQuoted, if_neg (h c (by simp)),
      ih (fun x hx => h x (by simp [hx]))]
    rfl

/-- **A quoted value reads back as the encoded value**, the rest of the header untouched. -/
theorem readValue_quoted (s rest : List Char) :
    readValue (quoted s ++ rest) = some (escape s, rest) := by
  simp only [quoted, List.cons_append, readValue, List.append_assoc]
  exact readQuoted_append (fun c hc => (escape_safe hc).1)

theorem isTokenChar_eq : isTokenChar = tchar := by
  funext c; rfl

theorem takeWhile_append_stop {p : Char → Bool} {a b : List Char} (ha : ∀ c ∈ a, p c = true)
    (hb : b = [] ∨ ∃ c r, b = c :: r ∧ p c = false) : (a ++ b).takeWhile p = a := by
  induction a with
  | nil =>
    rcases hb with rfl | ⟨c, r, rfl, hc⟩
    · rfl
    · simp [hc]
  | cons c cs ih =>
    rw [List.cons_append, List.takeWhile_cons, if_pos (ha c (by simp)),
      ih (fun x hx => ha x (by simp [hx]))]

/-- **A data-part name reads back** - as is when it is a token, else as the encoded value -
followed by the rest of the header (which continues with `;`, CR or the end). -/
theorem readValue_dataPartName (s rest : List Char)
    (hrest : rest = [] ∨ ∃ c r, rest = c :: r ∧ tchar c = false) :
    readValue (dataPartName s ++ rest) =
      some (if !s.isEmpty && s.all tchar then s else escape s, rest) := by
  simp only [dataPartName, isTokenChar_eq]
  by_cases ht : (!s.isEmpty && s.all tchar) = true
  · rw [if_pos ht, if_pos ht]
    simp only [Bool.and_eq_true, Bool.not_eq_true', List.isEmpty_eq_false_iff,
      List.all_eq_true] at ht
    obtain ⟨hne, hall⟩ := ht
    obtain ⟨c0, s', rfl⟩ := List.exists_cons_of_ne_nil hne
    have hc0 : c0 ≠ '"' := by
      intro e; have := hall c0 (by simp); subst e; simp [tchar] at this
    have htw := takeWhile_append_stop hall hrest
    simp only [readValue, List.cons_append, if_neg hc0]
    rw [← List.cons_append, htw]
    simp
  · rw [if_neg ht, if_neg ht]
    exact readValue_quoted s rest

theorem escape_no_backslash {s : List Char} (h : ∀ c ∈ s, c ≠ '\\') : ∀ c ∈ escape s, c ≠ '\\' := by
  intro c hc
  rw [escape_eq, escapeSpec, List.mem_flatMap] at hc
  obtain ⟨x, hx, hc⟩ := hc
  split at hc
  · simp only [List.mem_cons, List.not_mem_nil, or_false] at hc
    rcases hc with rfl | rfl | rfl <;> decide
  · split at hc
    · simp only [List.mem_cons, List.not_mem_nil, or_false] at hc
      rcases hc with rfl | rfl | rfl <;> decide
    · split at hc
      · simp only [List.mem_cons, List.not_mem_nil, or_false] at hc
        rcases hc with rfl | rfl | rfl <;> decide
      · simp only [List.mem_singleton] at hc
        subst hc
        exact h _ hx

theorem readQuotedRfc9110_append {v rest : List Char} (h : ∀ c ∈ v, c ≠ '"' ∧ c ≠ '\\') :
    readQuotedRfc9110 (v ++ '"' :: rest) = some (v, rest) := by
  induction v with
  | nil => rw [List.nil_append, readQuotedRfc9110.eq_def]; simp
  | cons c cs ih =>
    rw [List.cons_append, readQuotedRfc9110.eq_def]
    simp only [if_neg (h c (by simp)).1, if_neg (h c (by simp)).2]
    rw [ih (fun x hx => h x (by simp [hx]))]
    rfl

/-- **An RFC 9110 reader agrees on every value without a backslash.** -/
theorem readQuotedRfc9110_of_no_backslash (s rest : List Char) (h : ∀ c ∈ s, c ≠ '\\') :
    readQuotedRfc9110 (escape s ++ '"' :: rest) = some (escape s, rest) :=
  readQuotedRfc9110_append fun c hc => ⟨(escape_safe hc).1, escape_no_backslash h c hc⟩

/-- A backslash goes out as it is (`a\b` is sent as `"a\b"`) ... -/
theorem escape_backslash : escape ['a', '\\', 'b'] = ['a', '\\', 'b'] := by
  rw [escape_eq]; decide

/-- ... which an RFC 9110 reader takes as an escape: it reads `ab`. -/
theorem rfc9110_backslash_dropped :
    readQuotedRfc9110 (escape ['a', '\\', 'b'] ++ ['"']) = some (['a', 'b'], []) := by
  rw [escape_backslash]; decide

/-- A trailing backslash escapes the closing quote: the value never ends. -/
theorem rfc9110_trailing_backslash :
    readQuotedRfc9110 (escape ['a', '\\'] ++ ['"']) = none := by
  rw [escape_eq]; decide

end WsClientProofs.Multipart
