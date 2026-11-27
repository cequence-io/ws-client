import WsClientProofs.WireFormat.Extracted
import WsClientProofs.WireFormat.Spec
import WsClientProofs.WireFormat.JdkUrlEncoder
import WsClientProofs.JavaString

/-!
# Query encoding: the results

1. `encodeQueryComponent_eq`: `WireFormat.encodeQueryComponent` is exactly RFC 3986's
   percent-encoding of the UTF-8 bytes - for every text: the three `replace` calls on
   `URLEncoder`'s output never touch the inside of an escape.
2. `percentDecode_rfc3986Encode`: percent-decoding gives back the UTF-8 bytes.
3. `readQuery_url`: a strict RFC 3986 reader takes from `WireFormat.url base params` exactly
   the parameters of `base`'s own query followed by the defined `params`, as UTF-8 bytes -
   whatever the keys and values contain (`&`, `=`, `#`, `+`, `%`, `?`, any Unicode) - for any
   `base` without a `#` (a `#` in `base` moves the appended query into the fragment).
-/

namespace WsClientProofs.WireFormat

open WsClientProofs.WireFormat.Extracted
open WsClientProofs.Scala (mkString)

open WsClientProofs.Java

/-! ## Characters and bytes -/

theorem char_le_iff (a c : Char) : a ≤ c ↔ a.toNat ≤ c.toNat := by
  rw [Char.le_def, UInt32.le_iff_toNat_le]; rfl

theorem toNat_ofNat_of_lt {n : Nat} (h : n < 0xD800) : (Char.ofNat n).toNat = n := by
  simp [Char.ofNat, Nat.isValidChar, Char.ofNatAux, Char.toNat]
  split
  · simp [UInt32.toNat_ofNatLT]
  · omega

theorem hex_facts : ∀ n, n < 16 →
    hexValue? (hexDigit n) = some n ∧ hexDigit n ≠ '%' ∧ hexDigit n ≠ '+' ∧
      hexDigit n ≠ '*' ∧ hexDigit n ≠ '&' ∧ hexDigit n ≠ '=' ∧ hexDigit n ≠ '#' ∧
      hexDigit n ≠ '?' := by
  decide

theorem toNat_uint8_ofNat (n : Nat) : (UInt8.ofNat n).toNat = n % 256 := by
  simp [UInt8.ofNat, UInt8.toNat, BitVec.toNat_ofNat]

theorem utf8_singleton (c : Char) : utf8 [c] = (String.utf8EncodeChar c).map (·.toNat) := by
  simp [utf8]

theorem utf8_ascii {c : Char} (h : c.toNat < 128) : utf8 [c] = [c.toNat] := by
  have h' : c.val.toNat < 128 := h
  rw [utf8_singleton]
  simp only [String.utf8EncodeChar]
  rw [if_pos (by omega)]
  simp only [List.map_cons, List.map_nil, toNat_uint8_ofNat, List.cons.injEq, and_true]
  simp only [Char.toNat]; omega

theorem utf8_high {c : Char} (h : 128 ≤ c.toNat) : ∀ b ∈ utf8 [c], 128 ≤ b ∧ b < 256 := by
  rw [utf8_singleton]
  simp only [String.utf8EncodeChar]
  simp only [Char.toNat] at h
  split
  · omega
  · split
    · simp only [List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil, or_false,
        toNat_uint8_ofNat]
      rintro b (rfl | rfl) <;> omega
    · split
      · simp only [List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil, or_false,
          toNat_uint8_ofNat]
        rintro b (rfl | rfl | rfl) <;> omega
      · simp only [List.map_cons, List.map_nil, List.mem_cons, List.not_mem_nil, or_false,
          toNat_uint8_ofNat]
        rintro b (rfl | rfl | rfl | rfl) <;> omega

theorem utf8_lt {s : List Char} : ∀ b ∈ utf8 s, b < 256 := by
  intro b hb
  simp only [utf8, List.mem_flatMap, List.mem_map] at hb
  obtain ⟨_, _, u, _, rfl⟩ := hb
  exact UInt8.toNat_lt u

/-- The tilde replacement on one escape: only `%7E` changes. -/
theorem replace_pct {b : Nat} (hb : b < 256) (rest : List Char) :
    replace ['%', '7', 'E'] ['~'] (pct b ++ rest) =
      (if b = 126 then ['~'] else pct b) ++ replace ['%', '7', 'E'] ['~'] rest := by
  have h1 := hex_facts (b / 16) (by omega)
  have h2 := hex_facts (b % 16) (by omega)
  simp only [pct, List.cons_append, List.nil_append]
  rw [replace_cons]
  by_cases h126 : b = 126
  · subst h126
    rw [if_pos (by simp [List.isPrefixOf, show hexDigit 7 = '7' by decide,
      show hexDigit 14 = 'E' by decide]), if_pos rfl]
    simp
  · rw [if_neg h126, if_neg]
    · rw [replace_cons_of_ne (Ne.symm h1.2.1), replace_cons_of_ne (Ne.symm h2.2.1)]
      simp
    · intro ⟨_, hpre⟩
      simp only [List.isPrefixOf, beq_self_eq_true, Bool.true_and, Bool.and_true,
        Bool.and_eq_true, beq_iff_eq] at hpre
      obtain ⟨e1, e2⟩ := hpre
      have v1 := h1.1
      have v2 := h2.1
      rw [← e1] at v1
      rw [← e2] at v2
      have : b / 16 = 7 := by simpa [hexValue?] using v1.symm
      have : b % 16 = 14 := by simpa [hexValue?] using v2.symm
      omega

theorem replace_pcts {bs : List Nat} (hbs : ∀ b ∈ bs, b < 256) (rest : List Char) :
    replace ['%', '7', 'E'] ['~'] (bs.flatMap pct ++ rest) =
      bs.flatMap (fun b => if b = 126 then ['~'] else pct b) ++
        replace ['%', '7', 'E'] ['~'] rest := by
  induction bs with
  | nil => simp
  | cons b bs ih =>
    simp only [List.flatMap_cons, List.append_assoc]
    rw [replace_pct (hbs b (by simp)), ih (fun b' hb' => hbs b' (by simp [hb']))]

/-! ## `encodeQueryComponent` is RFC 3986 -/

/-- The bytes of one character after RFC 3986. -/
def encByte (b : Nat) : List Char := if unreserved b then [Char.ofNat b] else pct b

theorem rfc3986Encode_eq (s : List Char) :
    rfc3986Encode s = s.flatMap fun c => (utf8 [c]).flatMap encByte := by
  simp only [rfc3986Encode, utf8, List.flatMap_assoc]
  simp only [List.flatMap_cons, List.flatMap_nil, List.append_nil]
  rfl

theorem javaSafe_ascii {c : Char} (h : javaSafe c = true) :
    c.toNat < 128 ∧ c ≠ '%' ∧ c ≠ '+' ∧ (c ≠ '*' → unreserved c.toNat = true) := by
  simp only [javaSafe, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq,
    beq_iff_eq] at h
  have hz : ∀ (x : Char), x.toNat = c.toNat → x = c := fun x hx =>
    Char.ext (UInt32.toNat_inj.mp hx)
  rcases h with ((((((⟨h1, h2⟩ | ⟨h1, h2⟩) | ⟨h1, h2⟩) | h) | h) | h) | h)
  all_goals first
    | (rw [char_le_iff] at h1 h2
       refine ⟨by simp at h2; omega, fun e => ?_, fun e => ?_, fun _ => ?_⟩
       · subst e; simp at h1 h2
       · subst e; simp at h1 h2
       · simp only [unreserved]; simp at h1 h2; simp; omega)
    | (subst h; decide)

/-- After the `+` and `*` replacements, a character is itself or its escapes. -/
def mid (c : Char) : List Char :=
  if javaSafe c && c != '*' then [c] else (utf8 [c]).flatMap pct

theorem pcts_flatMap_id {bs : List Nat} (hbs : ∀ b ∈ bs, b < 256) {x : Char} {r : List Char}
    (hx : x ≠ '%') (hhex : ∀ n, n < 16 → hexDigit n ≠ x) :
    (bs.flatMap pct).flatMap (fun y => if y = x then r else [y]) = bs.flatMap pct := by
  rw [List.flatMap_assoc]
  apply flatMap_congr'
  intro b hb
  have hb' := hbs b hb
  simp only [pct, List.flatMap_cons, List.flatMap_nil, List.append_nil]
  rw [if_neg (Ne.symm hx), if_neg (hhex _ (by omega)), if_neg (hhex _ (by omega))]
  rfl

theorem step_plus_star (s : List Char) :
    replace ['*'] ['%', '2', 'A'] (replace ['+'] ['%', '2', '0'] (urlEncoderEncode s)) =
      s.flatMap mid := by
  rw [replace_single, replace_single, urlEncoderEncode, List.flatMap_assoc,
    List.flatMap_assoc]
  apply flatMap_congr'
  intro c _
  simp only [urlEncoderChar, mid]
  by_cases hs : javaSafe c = true
  · obtain ⟨_, _, hplus, _⟩ := javaSafe_ascii hs
    rw [if_pos hs]
    by_cases hstar : c = '*'
    · subst hstar; decide
    · simp [hplus, hstar, hs]
  · rw [if_neg hs]
    by_cases hsp : c = ' '
    · subst hsp; decide
    · rw [if_neg hsp]
      have hlt := @utf8_lt [c]
      rw [List.flatMap_assoc.symm, pcts_flatMap_id hlt (by decide)
        (fun n hn => (hex_facts n hn).2.2.1)]
      rw [pcts_flatMap_id hlt (by decide) (fun n hn => (hex_facts n hn).2.2.2.1)]
      simp [hs]

theorem unreserved_ascii_char {c : Char} (h : c.toNat < 128) (hu : unreserved c.toNat = true)
    (htilde : c.toNat ≠ 126) : javaSafe c = true ∧ c ≠ '*' := by
  simp only [unreserved, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq,
    beq_iff_eq] at hu
  refine ⟨?_, fun e => by subst e; simp at hu⟩
  simp only [javaSafe, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq, beq_iff_eq,
    char_le_iff]
  have hz : ∀ (x : Char), x.toNat = c.toNat → x = c := fun x hx =>
    Char.ext (UInt32.toNat_inj.mp hx)
  rcases hu with ((((((⟨h1, h2⟩ | ⟨h1, h2⟩) | ⟨h1, h2⟩) | h) | h) | h) | h)
  · left; left; left; left; left; right; constructor <;> simp <;> omega
  · left; left; left; left; left; left; constructor <;> simp <;> omega
  · left; left; left; left; right; constructor <;> simp <;> omega
  · left; left; right; exact (hz '-' (by simp [h])).symm
  · left; left; left; right; exact (hz '.' (by simp [h])).symm
  · right; exact (hz '_' (by simp [h])).symm
  · exact absurd h htilde

theorem step_tilde_char (c : Char) :
    (if javaSafe c && c != '*' then [c] else
        (utf8 [c]).flatMap fun b => if b = 126 then ['~'] else pct b) =
      (utf8 [c]).flatMap encByte := by
  by_cases hm : (javaSafe c && c != '*') = true
  · rw [if_pos hm]
    simp only [Bool.and_eq_true, bne_iff_ne, ne_eq] at hm
    obtain ⟨hlt, _, _, hu⟩ := javaSafe_ascii hm.1
    rw [utf8_ascii hlt]
    simp [encByte, hu hm.2, Char.ofNat_toNat]
  · rw [if_neg hm]
    apply flatMap_congr'
    intro b hb
    simp only [encByte]
    by_cases hlt : c.toNat < 128
    · rw [utf8_ascii hlt] at hb
      simp only [List.mem_singleton] at hb
      subst hb
      by_cases h126 : c.toNat = 126
      · rw [h126]; decide
      · rw [if_neg h126, if_neg]
        intro hu
        obtain ⟨hs, hstar⟩ := unreserved_ascii_char hlt hu h126
        exact hm (by simp [hs, hstar])
    · have := utf8_high (by omega) b hb
      rw [if_neg (by omega), if_neg]
      simp only [unreserved]
      simp; omega

theorem step_tilde (s : List Char) :
    replace ['%', '7', 'E'] ['~'] (s.flatMap mid) = s.flatMap fun c => (utf8 [c]).flatMap encByte := by
  induction s with
  | nil => simp [replace_nil]
  | cons c cs ih =>
    simp only [List.flatMap_cons]
    rw [← step_tilde_char c]
    simp only [mid]
    by_cases hm : (javaSafe c && c != '*') = true
    · rw [if_pos hm, if_pos hm]
      simp only [Bool.and_eq_true] at hm
      obtain ⟨_, hpct, _, _⟩ := javaSafe_ascii hm.1
      simp only [List.singleton_append]
      rw [replace_cons_of_ne (Ne.symm hpct), ih]
    · rw [if_neg hm, if_neg hm, replace_pcts utf8_lt, ih]

/-- **`WireFormat.encodeQueryComponent` is RFC 3986's encoding**, for every text. -/
theorem encodeQueryComponent_eq (s : List Char) : encodeQueryComponent s = rfc3986Encode s := by
  rw [encodeQueryComponent, step_plus_star, step_tilde, rfc3986Encode_eq]

/-! ## Decoding -/

theorem percentDecode_cons_of_ne {c : Char} (hc : c ≠ '%') (rest : List Char) :
    percentDecode (c :: rest) = (percentDecode rest).bind fun tail => some (utf8 [c] ++ tail) := by
  conv => lhs; unfold percentDecode
  simp [hc]

theorem percentDecode_pct (h l : Char) (rest : List Char) :
    percentDecode ('%' :: h :: l :: rest) =
      (hexValue? h).bind fun a => (hexValue? l).bind fun b =>
        (percentDecode rest).bind fun tail => some ((16 * a + b) :: tail) := by
  conv => lhs; unfold percentDecode
  simp

theorem percentDecode_encBytes {bs : List Nat} (hbs : ∀ b ∈ bs, b < 256) :
    percentDecode (bs.flatMap encByte) = some bs := by
  induction bs with
  | nil => rfl
  | cons b bs ih =>
    have hb := hbs b (by simp)
    have ih := ih (fun b' hb' => hbs b' (by simp [hb']))
    simp only [List.flatMap_cons, encByte]
    by_cases hu : unreserved b = true
    · have hlt : b < 128 := by
        simp only [unreserved, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq,
          beq_iff_eq] at hu
        omega
      have hc : (Char.ofNat b).toNat = b := toNat_ofNat_of_lt (by omega)
      have hne : Char.ofNat b ≠ '%' := by
        intro e
        have := congrArg Char.toNat e
        rw [hc] at this
        subst this
        simp [unreserved] at hu
      rw [if_pos hu, List.singleton_append, percentDecode_cons_of_ne hne, ih,
        utf8_ascii (by omega), hc]
      rfl
    · have h1 := hex_facts (b / 16) (by omega)
      have h2 := hex_facts (b % 16) (by omega)
      rw [if_neg hu]
      simp only [pct, List.cons_append, List.nil_append]
      rw [percentDecode_pct, h1.1, h2.1, ih]
      simp only [Option.bind_some, Option.some.injEq, List.cons.injEq, and_true]
      omega

/-- **Percent-decoding inverts the encoding**: the UTF-8 bytes come back. -/
theorem percentDecode_rfc3986Encode (s : List Char) :
    percentDecode (rfc3986Encode s) = some (utf8 s) := by
  rw [rfc3986Encode]
  exact percentDecode_encBytes utf8_lt

/-! ## The query of `url` -/

theorem rfc3986Encode_chars {s : List Char} {c : Char} (h : c ∈ rfc3986Encode s) :
    c ≠ '&' ∧ c ≠ '=' ∧ c ≠ '#' ∧ c ≠ '?' := by
  simp only [rfc3986Encode, List.mem_flatMap] at h
  obtain ⟨b, hb, hc⟩ := h
  have hlt := utf8_lt b hb
  split at hc
  · rename_i hu
    simp only [List.mem_singleton] at hc
    subst hc
    have hlt' : b < 128 := by
      simp only [unreserved, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq,
        beq_iff_eq] at hu
      omega
    have hc := toNat_ofNat_of_lt (n := b) (by omega)
    have hb' : b ≠ 38 ∧ b ≠ 61 ∧ b ≠ 35 ∧ b ≠ 63 := by
      simp only [unreserved, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq,
        beq_iff_eq] at hu
      omega
    refine ⟨fun e => ?_, fun e => ?_, fun e => ?_, fun e => ?_⟩ <;>
      · have := congrArg Char.toNat e; rw [hc] at this; simp at this; omega
  · simp only [pct, List.mem_cons, List.not_mem_nil, or_false] at hc
    have h1 := hex_facts (b / 16) (by omega)
    have h2 := hex_facts (b % 16) (by omega)
    rcases hc with rfl | rfl | rfl
    · decide
    · exact ⟨h1.2.2.2.2.1, h1.2.2.2.2.2.1, h1.2.2.2.2.2.2.1, h1.2.2.2.2.2.2.2⟩
    · exact ⟨h2.2.2.2.2.1, h2.2.2.2.2.2.1, h2.2.2.2.2.2.2.1, h2.2.2.2.2.2.2.2⟩

theorem splitOnChar_ne_nil (sep : Char) (l : List Char) : splitOnChar sep l ≠ [] := by
  cases l with
  | nil => simp [splitOnChar]
  | cons c cs =>
    simp only [splitOnChar]
    split
    · simp
    · split <;> simp

theorem splitOnChar_of_not_mem {sep : Char} {l : List Char} (h : ∀ c ∈ l, c ≠ sep) :
    splitOnChar sep l = [l] := by
  induction l with
  | nil => rfl
  | cons c cs ih =>
    simp only [splitOnChar]
    rw [if_neg (h c (by simp)), ih (fun x hx => h x (by simp [hx]))]

theorem splitOnChar_append_sep (sep : Char) (a b : List Char) :
    splitOnChar sep (a ++ sep :: b) = splitOnChar sep a ++ splitOnChar sep b := by
  induction a with
  | nil => simp [splitOnChar]
  | cons c cs ih =>
    simp only [List.cons_append, splitOnChar]
    split
    · simp [ih]
    · rw [ih]
      cases hs : splitOnChar sep cs with
      | nil => exact absurd hs (splitOnChar_ne_nil sep cs)
      | cons f fs => simp

theorem splitOnChar_mkString {sep : Char} {fs : List (List Char)} (hne : fs ≠ [])
    (h : ∀ f ∈ fs, ∀ c ∈ f, c ≠ sep) : splitOnChar sep (mkString [sep] fs) = fs := by
  induction fs with
  | nil => exact absurd rfl hne
  | cons f fs ih =>
    cases fs with
    | nil => simp only [mkString]; exact splitOnChar_of_not_mem (h f (by simp))
    | cons g gs =>
      simp only [mkString, List.append_assoc, List.singleton_append]
      rw [splitOnChar_append_sep, splitOnChar_of_not_mem (h f (by simp)),
        ih (by simp) (fun f' hf' => h f' (by simp [hf']))]
      rfl

theorem mem_mkString {sep : List Char} {fs : List (List Char)} {c : Char}
    (h : c ∈ mkString sep fs) : c ∈ sep ∨ ∃ f ∈ fs, c ∈ f := by
  induction fs with
  | nil => simp [mkString] at h
  | cons f fs ih =>
    cases fs with
    | nil => simp only [mkString] at h; exact Or.inr ⟨f, by simp, h⟩
    | cons g gs =>
      simp only [mkString, List.mem_append] at h
      rcases h with (h | h) | h
      · exact Or.inr ⟨f, by simp, h⟩
      · exact Or.inl h
      · rcases ih h with h' | ⟨f', hf', hc⟩
        · exact Or.inl h'
        · exact Or.inr ⟨f', by simp [hf'], hc⟩

theorem mkString_eq_nil {sep : List Char} {fs : List (List Char)} (hne : fs ≠ [])
    (h : ∀ f ∈ fs, f ≠ []) : mkString sep fs ≠ [] := by
  cases fs with
  | nil => exact absurd rfl hne
  | cons f fs =>
    have hf := h f (by simp)
    cases fs with
    | nil => simpa [mkString] using hf
    | cons g gs => simp [mkString, hf]

theorem takeWhile_of_all {p : Char → Bool} {l : List Char} (h : ∀ c ∈ l, p c = true) :
    l.takeWhile p = l := by
  induction l with
  | nil => rfl
  | cons c cs ih => simp [List.takeWhile, h c (by simp), ih (fun x hx => h x (by simp [hx]))]

theorem dropWhile_append_of_all {p : Char → Bool} {a b : List Char} (h : ∀ c ∈ a, p c = true) :
    (a ++ b).dropWhile p = b.dropWhile p := by
  induction a with
  | nil => rfl
  | cons c cs ih =>
    rw [List.cons_append, List.dropWhile_cons, if_pos (h c (by simp)),
      ih (fun x hx => h x (by simp [hx]))]

theorem queryOf_of_not_mem {base : List Char} (h : ∀ c ∈ base, c ≠ '?') (rest : List Char) :
    queryOf (base ++ rest) = queryOf rest := by
  simp only [queryOf]
  rw [dropWhile_append_of_all (by simpa using h)]

theorem dropWhile_ne_nil_of_mem {p : Char → Bool} {l : List Char} {x : Char} (h : x ∈ l)
    (hp : p x = false) : l.dropWhile p ≠ [] := by
  induction l with
  | nil => simp at h
  | cons y ys ih =>
    rw [List.dropWhile_cons]
    split
    · rename_i hy
      rcases List.mem_cons.mp h with rfl | h'
      · rw [hp] at hy; exact absurd hy (by simp)
      · exact ih h'
    · simp

theorem takeWhile_append_of_all {p : Char → Bool} {a b : List Char}
    (h : ∀ c ∈ a, p c = true) : (a ++ b).takeWhile p = a ++ b.takeWhile p := by
  induction a with
  | nil => rfl
  | cons c cs ih =>
    rw [List.cons_append, List.takeWhile_cons, if_pos (h c (by simp)),
      ih (fun x hx => h x (by simp [hx]))]
    rfl

theorem queryOf_append {base : List Char} (hq : '?' ∈ base) (hh : ∀ c ∈ base, c ≠ '#')
    (rest : List Char) : queryOf (base ++ rest) = queryOf base ++ rest.takeWhile (· ≠ '#') := by
  have hd : base.dropWhile (fun x => decide (x ≠ '?')) ≠ [] :=
    dropWhile_ne_nil_of_mem hq (by simp)
  obtain ⟨x, xs, hx⟩ := List.exists_cons_of_ne_nil hd
  have hsuffix : x :: xs <:+ base := hx ▸ List.dropWhile_suffix _
  have hxs : ∀ c ∈ xs, decide (c ≠ '#') = true := fun c hc => by
    simpa using hh c (hsuffix.subset (List.mem_cons_of_mem _ hc))
  simp only [queryOf]
  rw [List.dropWhile_append, if_neg (by rw [hx]; simp), hx]
  simp only [List.cons_append, List.drop_succ_cons, List.drop_zero]
  rw [takeWhile_of_all (l := xs) hxs, takeWhile_append_of_all hxs]

theorem splitField_of {k v : List Char} (hk : ∀ c ∈ k, c ≠ '=') :
    splitField (k ++ '=' :: v) = (k, v) := by
  induction k with
  | nil => simp [splitField]
  | cons c cs ih =>
    have hc := hk c (by simp)
    have ih := ih (fun x hx => hk x (by simp [hx]))
    simp only [splitField, Prod.mk.injEq] at ih ⊢
    rw [List.cons_append, List.takeWhile_cons, List.dropWhile_cons, if_pos (by simpa using hc),
      if_pos (by simpa using hc), ih.1, ih.2]
    simp

theorem readField_enc (k v : List Char) :
    readField (rfc3986Encode k ++ '=' :: rfc3986Encode v) = some (utf8 k, utf8 v) := by
  simp only [readField]
  rw [splitField_of (fun c hc => (rfc3986Encode_chars hc).2.1)]
  simp [percentDecode_rfc3986Encode]

/-- The defined parameters, as the reader returns them. -/
def expected (params : List (List Char × Option (List Char))) : List (List Nat × List Nat) :=
  params.filterMap fun p => p.2.map fun v => (utf8 p.1, utf8 v)

/-- The encoded fields of `url`. -/
def fields (params : List (List Char × Option (List Char))) : List (List Char) :=
  params.filterMap fun p => p.2.map fun v => encodeQueryComponent p.1 ++ '=' :: encodeQueryComponent v

/-- Any function that defines the encoded field of a defined parameter and nothing for an
undefined one - what the extracted `collect` does - collects `fields`. -/
theorem filterMap_fields (f : List Char × Option (List Char) → Option (List Char))
    (hs : ∀ k v, f (k, some v) = some (encodeQueryComponent k ++ '=' :: encodeQueryComponent v))
    (hn : ∀ k, f (k, none) = none) (params : List (List Char × Option (List Char))) :
    params.filterMap f = fields params := by
  induction params with
  | nil => rfl
  | cons p ps ih =>
    obtain ⟨k, v?⟩ := p
    cases v? with
    | none => simp only [fields, List.filterMap_cons, hn, Option.map_none] at ih ⊢; exact ih
    | some v =>
      simp only [fields, List.filterMap_cons, hs, Option.map_some] at ih ⊢
      rw [ih]

/-- `url`, with the extracted `collect` read as `fields`. -/
theorem url_eq (base : List Char) (params : List (List Char × Option (List Char))) :
    url base params =
      if (mkString ['&'] (fields params)).isEmpty then base
      else if base.contains '?' then base ++ '&' :: mkString ['&'] (fields params)
      else base ++ '?' :: mkString ['&'] (fields params) := by
  unfold url
  rw [filterMap_fields]
  · simp only [Scala.containsText_single, List.append_assoc, List.singleton_append]
  · intro k v; simp
  · intro k; rfl

theorem fields_mapM (params : List (List Char × Option (List Char))) :
    (fields params).mapM readField = some (expected params) := by
  induction params with
  | nil => rfl
  | cons p ps ih =>
    obtain ⟨k, v?⟩ := p
    cases v? with
    | none => simpa [fields, expected] using ih
    | some v =>
      simp only [fields, expected, List.filterMap_cons, Option.map_some] at ih ⊢
      rw [List.mapM_cons, encodeQueryComponent_eq, encodeQueryComponent_eq, readField_enc, ih]
      rfl

theorem fields_chars {params : List (List Char × Option (List Char))} :
    ∀ f ∈ fields params, f ≠ [] ∧ ∀ c ∈ f, c ≠ '&' ∧ c ≠ '#' := by
  intro f hf
  simp only [fields, List.mem_filterMap, Option.map_eq_some_iff] at hf
  obtain ⟨⟨k, _⟩, _, v, _, rfl⟩ := hf
  refine ⟨by simp, fun c hc => ?_⟩
  simp only [encodeQueryComponent_eq, List.mem_append, List.mem_cons] at hc
  rcases hc with hc | rfl | hc
  · exact ⟨(rfc3986Encode_chars hc).1, (rfc3986Encode_chars hc).2.2.1⟩
  · decide
  · exact ⟨(rfc3986Encode_chars hc).1, (rfc3986Encode_chars hc).2.2.1⟩

theorem filter_nonempty_of {fs : List (List Char)} (h : ∀ f ∈ fs, f ≠ []) :
    fs.filter (fun f => !f.isEmpty) = fs := by
  rw [List.filter_eq_self]
  intro f hf
  simpa using h f hf

/-- **A strict reader takes back exactly the parameters** - `base`'s own, then the defined
`params` (as UTF-8 bytes) - from any `base` without a `#`. -/
theorem readQuery_url (base : List Char) (params : List (List Char × Option (List Char)))
    (hbase : ∀ c ∈ base, c ≠ '#') :
    readQuery (url base params) = (readQuery base).map (· ++ expected params) := by
  have hf := fields_chars (params := params)
  have hm := fields_mapM params
  by_cases hnil : fields params = []
  · have he : expected params = [] := by rw [hnil] at hm; simpa using hm.symm
    have : url base params = base := by
      rw [url_eq, hnil]
      rfl
    rw [this, he]
    cases readQuery base <;> simp
  · have hq : mkString ['&'] (fields params) ≠ [] :=
      mkString_eq_nil hnil (fun f h => (hf f h).1)
    have hsplit : splitOnChar '&' (mkString ['&'] (fields params)) = fields params :=
      splitOnChar_mkString hnil (fun f h c hc => ((hf f h).2 c hc).1)
    have hhash : ∀ c ∈ mkString ['&'] (fields params), c ≠ '#' := by
      intro c hc
      rcases mem_mkString hc with h | ⟨f, hf', hc'⟩
      · simp at h; subst h; decide
      · exact ((hf f hf').2 c hc').2
    have hfilter := filter_nonempty_of (fun f h => (hf f h).1)
    have hurl : url base params =
        if base.contains '?' then base ++ '&' :: mkString ['&'] (fields params)
        else base ++ '?' :: mkString ['&'] (fields params) := by
      rw [url_eq, if_neg (by simpa using hq)]
    rw [hurl]
    by_cases hc : base.contains '?' = true
    · rw [if_pos hc]
      have hmem : '?' ∈ base := by simpa using hc
      simp only [readQuery]
      rw [queryOf_append hmem hbase, List.takeWhile_cons, if_pos (by decide),
        takeWhile_of_all (by simpa using hhash), splitOnChar_append_sep, hsplit,
        List.filter_append, hfilter, List.mapM_append, hm]
      cases ((splitOnChar '&' (queryOf base)).filter fun f => !f.isEmpty).mapM readField <;> rfl
    · rw [if_neg hc]
      have hnot : ∀ c ∈ base, c ≠ '?' := by
        intro c hc' e; subst e; exact hc (by simpa using hc')
      have hq1 : queryOf ('?' :: mkString ['&'] (fields params)) =
          mkString ['&'] (fields params) := by
        simp only [queryOf]
        rw [List.dropWhile_cons, if_neg (by simp)]
        simp only [List.drop_succ_cons, List.drop_zero]
        exact takeWhile_of_all (by simpa using hhash)
      have hq0 : queryOf base = [] := by
        have := queryOf_of_not_mem hnot []
        simpa [queryOf] using this
      simp only [readQuery]
      rw [queryOf_of_not_mem hnot, hq1, hsplit, hfilter, hm, hq0]
      rfl

end WsClientProofs.WireFormat
