# Lean proofs of ws-client's pure parts

Machine-checked proofs, in [Lean 4](https://lean-lang.org), about four pieces of ws-client whose
correctness is a statement about every input: delimiter framing, query encoding, multipart
parameter encoding and SSE frame parsing. Where the other verification layers (see
[`docs/verification.md`](../../docs/verification.md)) check every case of a small scope or a
sample of interleavings, a theorem here holds for every input - every byte string, every
chunking, every Unicode text.

## What is proved about what

Each piece has three parts:

1. a **spec**, written from the scaladoc / the RFC alone (`Spec.lean`);
2. a **model**: the Scala code transliterated line by line - its mutable fields a `State`, its
   `while` loops recursive functions, its exceptions `Except` (`*Model.lean`, `Model.lean`);
3. **theorems** about the model against the spec (`*Proof*.lean`, `Theorems.lean`).

The proofs are about the models. What ties a model to the Scala code is the transliteration
(each model quotes or names the code it mirrors, and says where `Int` arithmetic became natural
arithmetic) and the **conformance check**: `sbt leanConformance` runs every case through the
Scala code and through the model (compiled to a Lean executable) and requires identical output -
20,000 random cases per piece by default, far beyond the small-scope specs (long inputs, the
framers' buffer compaction and growth, supplementary characters). A bug planted in
`ByteFraming`'s compaction (`scanFrom -= start - 1`) made 276 of 5,000 conformance cases differ;
the small-scope framing spec passes with it even at its audit scope, its inputs never reaching
the 256-byte buffer.

Java's `URLEncoder` is JDK code: it is modelled from its javadoc, and the conformance check runs
it against the JDK.

| Piece | Scala | Spec | Headline theorems |
|---|---|---|---|
| Delimiter framing | `ByteFraming.delimiter` (its `DelimiterFramer`), `StreamTransformers.DelimiterFramer` | split at the leftmost delimiter, then the limit (`Framing/Spec.lean`) | `framers_equivalent`, `*_frames_prefix`, `*_tooLong_of_reference`, `*_eq_reference_of_ne_tooLong`, `*_eq_reference_of_margin`, `*_chunking_independent` (`Framing/Theorems.lean`) |
| Query encoding | `WireFormat.encodeQueryComponent`, `WireFormat.url` | RFC 3986 2.1 / 2.3 / 3.4: percent-encoding of the UTF-8 bytes, a strict query reader | `encodeQueryComponent_eq`, `percentDecode_rfc3986Encode`, `readQuery_url` (`WireFormat/Proofs.lean`) |
| Multipart parameters | `MultipartParams.escape` / `quoted` / `dataPartName` | WHATWG / RFC 7578 4.2 encoding, RFC 9110 tokens and quoted strings | `escape_eq`, `quoted_one_line`, `readValue_quoted`, `readValue_dataPartName`, `readQuotedRfc9110_of_no_backslash` (`Multipart/Proofs.lean`) |
| SSE frames | `JsonStreamFrames.parseFrame` (up to the string handed to `Json.parse`) | the scaladoc's rules on the frame's lines (CRLF / CR / LF, comments, the first `data:` line, raw payloads, `[DONE]`) | `parseFrame_eq_reference` (`SseFrames/Proofs.lean`) |

In words:

- **Framing.** Both framers compute the same function of the chunks (`chunkSpec`) - proved
  through `ByteFraming`'s array compaction, growth and `scanFrom` skip, for every input shorter
  than `Int.MaxValue - 8` bytes. Against the scaladoc model they never emit a frame the model
  does not, never let an oversized frame through, agree exactly whenever they do not fail, and
  fail where the model does not only when a frame comes within `delimiter.length - 1` bytes of
  the limit; away from that edge the result does not depend on the chunking. That makes the
  two edges `FramingSmallScopeSpec` allows (and pins) the only differences there are.
- **Query encoding.** `encodeQueryComponent` is exactly RFC 3986's encoding for every text: the
  three `replace` calls on `URLEncoder`'s output never touch the inside of an escape. A strict
  RFC 3986 reader takes from `url(base, params)` exactly `base`'s own parameters followed by the
  defined `params`, whatever the keys and values contain - for any `base` without a `#`.
- **Multipart.** The encoding is the WHATWG one for every text; an encoded value contains no
  `"`, CR or LF, so a quoted value cannot end early or break the header line, and a reader takes
  back exactly the encoded value with the rest of the header untouched (a data-part name that is
  a token: the name itself).
- **SSE frames.** `parseFrame` computes exactly what the line rules say, for every frame and
  every item prefix whose field prefix (the item prefix without its trailing space) has no line
  break and does not start with `:` (true for the default `data: `). That covers the two places
  where the code departs from the line structure: the fast path that does not split a frame
  without comments or CRs, and the field search on the joined content
  (`indexOf("\n" + fieldPrefix)`), which finds the first line starting with the field and, in a
  raw payload, nothing.

## What the proofs surfaced

- A backslash in a multipart name or file name goes out as it is - as the WHATWG encoding (and
  browsers) do. An RFC 9110 `quoted-string` reader takes it as an escape: `a\b` reads as `ab`,
  and a trailing backslash escapes the closing quote, so the value never ends
  (`rfc9110_backslash_dropped`, `rfc9110_trailing_backslash`). Readers that follow the WHATWG
  convention see the name unchanged.
- A field prefix starting with `:` would let a comment line inside a raw SSE payload be taken
  for the field - excluded by the theorem's hypothesis; the default prefix is unaffected.
- A `#` in `url`'s `base` moves the appended query into the fragment - excluded by the
  theorem's hypothesis.
- Texts are Unicode scalar values: a Java string with an unpaired surrogate is outside the
  query and multipart models (and not generated by the conformance check).

## Running

```
formal/lean/check.sh         # build every proof and the conformance executable; fails on any
                             # `sorry` or an axiom beyond Lean's standard three (Axioms.lean)
sbt leanConformance          # the models against the Scala code (after check.sh)
```

`check.sh` needs [elan](https://github.com/leanprover/elan); the toolchain is pinned in
`lean-toolchain` (Lean 4.24.0, installed by elan on first use) and the project has no
dependencies - no Mathlib. A build from scratch takes about 30 seconds on 20 cores. Build
output goes to `.lake/` (git-ignored).

Conformance knobs (system properties): `wsclient.verify.lean.cases` (per piece, default
20,000), `wsclient.verify.lean.seed` (default 1), `wsclient.verify.lean.only` (pieces whose name
contains the text: `framing`, `url`, `multipart`, `frame`), `wsclient.verify.lean.exe` (the
executable; found from the working directory up by default).

## Conventions

- A spec is written from the scaladoc / the RFC text alone, never from the code.
- A model follows its Scala original line by line; a change to a modelled method changes its
  model in the same commit (the conformance check fails otherwise). Java library calls go
  through the shared models in `Java.lean` (arrays) and `JavaString.lean` (`String.replace`).
- Every model has a conformance target, and a conformance target is first shown to fail on a
  bug planted in the Scala code.
- A divergence from the spec is a theorem: either proved to be the only one (framing's edge),
  or shown by a computed witness (multipart's backslash).
- `Axioms.lean` lists every headline theorem; nothing may rest on `sorry`.

## Layout

```
Java.lean, JavaString.lean     Java primitives: arrays, String.replace
Framing/                       Spec, Scan (the search loop both framers share), SplitLemmas,
                               ByteFramingModel / Proof, EngineFramerModel / Proof,
                               ChunkSpecProofs, Theorems
WireFormat/                    Spec, Model, Proofs
Multipart/                     Spec, Model, Proofs
SseFrames/                     Spec, Model, Proofs
Conformance.lean               the executable `sbt leanConformance` drives
Axioms.lean                    #print axioms of every headline theorem
```
