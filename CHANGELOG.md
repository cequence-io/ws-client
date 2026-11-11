# Changelog

Release notes of ws-client, newest first. **Behavior change** marks what existing code may notice.
Use one version for all ws-client artifacts on a classpath (see
[Compatibility and upgrading](docs/compatibility.md)).

## 1.2.0 (2026-11-11) - backend-neutral streaming

**New**

- `AsyncStream`, a stream type every streaming engine returns and every consumer runs: plain
  `Future`s, Akka, Pekko, fs2 (`ws-client-fs2`) or ZIO (`ws-client-zio`) - see
  [Streaming](docs/streaming.md). An engine's own stream comes back as itself: an Akka / Pekko
  engine's `Source`, the `zio-http` engine's `ZStream` (no bridge).
- Binary-safe `Response.bytes` on every engine, and download helpers on the service bases
  (`execGETBytes`, `execGETStream`, ...).
- `TransportSettings.httpVersion`: pins HTTP/1.1 or HTTP/2 on `jdk`; unset, the default, it
  stays `java.net.http`'s own choice as in 1.1.x (HTTP/2 where the server offers it).
- A neutral `AsyncScheduler` (timers without an actor system), `PollingHelperCore` and
  `ParallelServiceAdapters.parallelTakeFirst` in the Akka-free core.
- Scala 3 (3.3+) artifacts of the Pekko modules and `pekko-http`.
- The stream layer is verified beyond its tests: bounded-exhaustive specs, model checks, TLA+
  specs - see [Verification](docs/verification.md).

**Behavior changes**

- **JSON request bodies** on `jdk`, `sttp`, `pekko-http` and `zio-http` are rendered exactly as
  the Play engines render them (Play's `Json.toBytes`: characters outside the Basic Multilingual
  Plane, such as emoji, go out as `\uXXXX` escapes). The body bytes change for request signers;
  `engine.renderJsonBody` returns them.
- **`jdk` query parameters** are RFC 3986-encoded, like the Play engines': a space is sent as
  `%20` (was `+`).
- **Restricted headers:** `jdk` - and `sttp` on its own JDK backend - drop a header
  `java.net.http` sets itself (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`) with
  a one-time warning instead of failing the request.
- **Streams:** an oversized frame fails with the typed `CequenceWSFrameTooLongException` (a
  `CequenceWSException`; message unchanged); a stream's non-2xx failure is logged at DEBUG
  instead of ERROR (it still reaches the caller as `CequenceWSHttpStatusException`).

- **Play WS 2.2.18 on the Akka flavor, Scala 2.13** (`play-akka`, `play-akka-stream`; was
  2.1.11): the shaded AsyncHttpClient / Netty move from 2.10.5 / 4.1.45 to 2.16.1 / 4.1.138 -
  the line the Pekko flavor is on - and Akka to 2.6.21. **These two artifacts now need Java
  11+ on Scala 2.13.** Netty 4.1.138 parses responses more strictly (bare-LF line ends,
  `Content-Length` together with `Transfer-Encoding`, control characters in headers are
  rejected). The decompression limit is now AsyncHttpClient's own - same property
  (`play.shaded.ahc.org.asynchttpclient.maxDecompressedResponseSize`), same default. Scala 2.12
  stays on Play WS 2.1.11 and Scala 3 on 2.2.0-M2, the last releases for them.
- **Play WS no longer brings its own play-json:** the Akka-flavor Play modules excluded it under
  a name that matched nothing; they now exclude `play-json_<scala version>`, so the play-json on
  the classpath is the one `ws-client-core` declares (2.8.2 on Scala 2.12 / 2.13), unless your
  build asks for a newer one.

**Fixes**

- A deadlock of `jdk` / `zio-http` streams - a downstream cancel or request racing the next
  element or the end of the response.

**Compatibility**

- `TransportSettings` gained a field: code compiled against 1.1.x keeps working (construction,
  `copy`, accessors and pattern matches), except, on Scala 2, an explicit call of its synthetic
  `unapply`, whose result is now a three-element tuple.
- Use one version for all ws-client artifacts on a classpath: the interop modules call core
  internals.
- The Scala 2.13 artifacts are built with Scala 2.13.18 (was 2.13.11) and depend on that
  scala-library; sbt 1.10+ asks for a `scalaVersion` of at least 2.13.18 then.

## 1.1.5 (2026-11-05) - security fixes

- **Query parameters on the Play engines** (`play-akka`, `play-pekko` and their `-stream`
  variants) are now percent-encoded, as on every other engine. Before, `params` and
  `extraParams` went into the URL verbatim: a `#` cut the query off (with every parameter after
  it, extra params included), `&` and `=` split a value, and `+` read as a space.
  **Behavior change:** if you pre-encoded values for the Play engines, stop - a pre-encoded
  `a%20b` is now sent as the literal text `a%20b`. A query written into the endpoint string
  itself is still sent byte for byte.
- **Proxy `CONNECT` on the Play engines:** a proxy's non-200 reply to `CONNECT` now fails the
  request. Before, the Play engines took such a reply as the https site's own response, so the
  proxy - or anyone on the plain-HTTP hop to it - could make up an "HTTPS" response (e.g. a 202
  with any body).
- **Response-body timeouts on `jdk` and `sttp`:** `requestTimeout` now covers the response body.
  `java.net.http`'s request timeout stops at the response headers, so a server that sent the
  headers and then stalled hung the call forever (on `sttp` also holding a thread of the global
  `ExecutionContext`); it now fails with an `HttpTimeoutException` and the connection is closed.
  **Behavior change:** `jdk` streams (SSE / JSON) now fail after `readTimeout` without data
  while the consumer waits for it (default: `requestTimeout`; before, `jdk` ignored
  `readTimeout`). Time the consumer is busy does not count, so long healthy streams are
  unaffected.
- **`pekko-http`:** a response that arrives after `requestTimeout` is aborted (connection
  closed) instead of being read to its end. The per-host pool now allows 1024 connections / 2048
  open requests (pekko-http's default: 4 / 32, which queued every call to a host behind its slow
  ones), unless your configuration sets its own values. A copy of the engine on the same actor
  system shares its per-host pools.
- **`asSafe` error messages** (`JsonUtil.JsonOps`) show a compact excerpt of the mismatching
  JSON, at most 500 characters, instead of pretty-printing the whole value twice (a 643 KB
  response could exhaust a 512 MiB heap). **Behavior change:** the message is no longer wrapped
  in "Error thrown while processing a JSON".
- **json-repair:** a text that is exactly one valid JSON value is now decoded as such
  (`Json.parse`, plus the `"true"` / `"false"` / `"null"` and `convertNumbers` coercions where
  they applied before). Before, valid nested JSON went through the repair parser, where string
  content could add, replace or drop sibling members. **Behavior change:** such documents now
  decode exactly, and objects with more than 4 keys keep their key order. Malformed input
  repairs as before.

## 1.1.4 (2026-10-25) - security hardening and json-repair fixes

**Security**

- **No cookie jar on the Play engines** (`play-akka`, `play-pekko` and their `-stream`
  variants). They ran on the shaded AsyncHttpClient's builder default - ONE cookie store for the
  whole engine, shared by every `SiteBinding` - so a `Set-Cookie` that one site or tenant
  received was sent on later requests of every other `SiteBinding` with a matching host name
  (any port; on the Akka flavor also a host that a response named in its `Domain` attribute).
  Play's own client configuration disables that store; ws-client builds the AHC configuration
  itself and kept it. Now no engine stores or replays cookies (the other engines never did):
  `Set-Cookie` headers still show up in `RichResponse.headers`, and a site that needs a cookie
  sends it itself - e.g. a `Cookie` header in its `WsRequestContext`. **Behavior change** only
  for code that relied on the Play engines' implicit cookie continuity (e.g. sticky-session
  cookies): pass the cookie explicitly.
- **Redirect following is off explicitly on the Play engines.** They relied on the shaded
  AsyncHttpClient's default, which `-Dplay.shaded.ahc.org.asynchttpclient.followRedirect=true`
  flips JVM-wide - and then re-sent bodies and custom secret headers such as `X-Api-Key` (on the
  Akka flavor `Authorization` and `Cookie` too: CVE-2026-40490, CVE-2026-45300) to whatever
  origin a redirect named.
- **Bounded decompression on the Akka flavor** (`play-akka`, `play-akka-stream`: play-ws 2.1.11
  / 2.2.0-M2 with a shaded AsyncHttpClient 2.10.5 / 2.12.3, which inflates gzip / deflate
  response bodies without any limit - CVE-2026-85721; a 510 KB response exhausted a 768 MB
  heap): a response whose decompressed body exceeds 256 MiB now fails with a
  `DecompressionException` - the Pekko flavor's AsyncHttpClient 2.16.1 limit, set by the same
  property, `play.shaded.ahc.org.asynchttpclient.maxDecompressedResponseSize` (`0` or less: no
  limit). The limit counts the whole response, so a long-lived gzip / deflate-encoded SSE or
  JSON stream on `play-akka-stream` now fails once it has decompressed 256 MiB, as it already
  does on `play-pekko-stream`; raise the limit or set it to `0` for such streams.
- The Akka flavor's shaded Netty (4.1.45 / 4.1.60) still has known CVEs that a client does not
  reach (HTTP/2, SPDY, SNI, CORS and server-side decoders); the Pekko flavor's (4.1.138) has
  none.
- **Dependency updates** that clear scanner findings with no reachable vulnerability: the Scala
  2.12 artifacts move from Akka 2.6.1 to 2.6.20 (2.6.1 pulled protobuf-java 3.10.0),
  `play-akka-stream` to akka-http 10.5.3, and `zio-http` declares Bouncy Castle 1.86 directly
  (zio-http pulls 1.84 through netty-pkitesting).
- **Multipart:** a quote, CR or LF in a caller-supplied data-part name, file key or display
  file name can no longer add parameters, part headers or a content prefix to its part (on
  `pekko-http` such a name in a data part or file key dropped the part's
  `Content-Disposition`). It is now encoded as `%22`, `%0D`, `%0A` (the browser / RFC 7578
  encoding Play 2.8+ uses) on every engine, except in a display file name on `pekko-http`,
  which pekko-http renders itself, as before: `?` in place of each such character, plus the
  name RFC 5987-encoded in a `filename*` parameter. Other names go out byte-for-byte as before,
  except a data-part name that is not an HTTP token (e.g. `tags[]`), which the Play and
  in-memory bodies now quote like every other engine does.
- **`sttp`:** a failed call's exception no longer carries the request URI - sttp's own messages
  included it in full, query-parameter API keys (`extraParams`) included; refused / reset
  connections and read failures now arrive as a `CequenceWSException` (with the transport
  exception as its cause) instead of the raw `SttpClientException` - a site `recoverErrors`
  matching sttp types needs to match the Cequence type or the cause.

**Fixes**

- **File names:** a file whose display name is only dots (e.g. `...`) no longer fails the
  request with a `NoSuchElementException` (the default content-type lookup by extension), and a
  file name without a dot is no longer taken as its own extension: a file named e.g. `pdf` or
  `json` went out as `application/pdf` / `application/json`, and now gets no content type, like
  any other name without an extension (an explicit `contentType` still applies).
- **SSE framing on `jdk` / `zio-http`** costs linear time per received chunk (a chunk of many
  tiny or blank frames was quadratic: a 1 MiB chunk took about 35 s of CPU).
- **`play-akka-stream` on Scala 3 streams incrementally.** Play WS 2.2.0-M2, the last Play WS
  built for Scala 3.2, implements `stream()` as its buffered `execute()`, so an SSE / JSON
  stream delivered all of its events only once the response had ended, and cancelling the
  stream could not abort the request. The engine now takes the client's own streaming path, as
  Play WS 2.1.x and 3.x do.

**json-repair**

- Input nested deeper than the calling thread's stack (a few KB of `[`) now fails with an
  ordinary `IllegalArgumentException` instead of a `StackOverflowError`, which escaped `Try` /
  `NonFatal` recovery and left a `Future` running the repair incomplete forever (repair results
  unchanged).
- Large arrays or many concatenated top-level values repair in linear time (200,000 elements:
  minutes before, well under a second now; repair results unchanged).
- `fromFile` threw a `StringIndexOutOfBoundsException` on a file with any multibyte (non-ASCII)
  character (and with a small `chunkLength` it could garble such characters instead) - the file
  is now decoded at once, in the platform charset as before, and repaired exactly like the same
  string (`chunkLength` no longer has an effect).
- A top-level `null` after the first value (e.g. `[1] null` with `handleLiterals = true`) threw
  a `NullPointerException` - it is now kept as a value, like `true` / `false`.
- A repair no longer loops forever, allocating until the heap is exhausted, on an array value
  that consumes no input (e.g. `[{"a": {""}}, b"c": 1]`).
- Trimming trailing whitespace is linear (a run of 200,000 spaces inside a broken string took
  over a minute).
- **Escape sequences** in strings on the repair path - which valid nested JSON takes too - are
  decoded the way a JSON parser decodes them. Before, `\n`, `\t`, `\r`, `\b` and `\\` kept
  their backslash next to the decoded character (`"x\ny"` came back as `x\`, a line break, `y`;
  a trailing `\n` as a stray backslash), `\uXXXX`, `\/` and `\f` were not decoded at all, and an
  escaped quote in a single-quoted string (`'it\'s'`) kept its backslash. **Behavior change:**
  repaired strings that contain escape sequences come out different - correct now - and, because
  some repair heuristics look at the decoded text (duplicate keys inside arrays, the `"true"` /
  `"null"` coercion, empty strings), so does the shape of some broken inputs' repairs.
- **Numbers** no longer change on the repair path, which valid nested JSON takes too: an
  exponent's `+` sign ended the number there (`2.50E+3` came back as `2.5`, the rest of it as
  debris), and every number became a double or a long, so digits beyond a double's precision
  were rounded (`0.255621758985604992` came back as `0.255621758985605`), a tiny number became
  `0`, a huge one (`1e400`) failed the repair with a `NumberFormatException`, and an integer
  beyond `Long` came back as a string. Numbers now repair the way Jackson / play-json parse
  them (to play-json's 34 significant digits). **Behavior change** for `repairJsonAsValue`:
  such a number is now a `BigDecimal` - a number a `Double` / `Long` holds exactly keeps that
  type.

## 1.1.3 (2026-10-16) - SSE comment frames no longer fail a JSON stream

- Every streaming engine (`play-akka-stream`, `play-pekko-stream`, `pekko-http`, `jdk`,
  `zio-http`) now ignores SSE comment lines - lines starting with `:`, which servers send as
  keep-alives while a slow model works (OpenRouter's `: OPENROUTER PROCESSING`, OpenAI's bare
  `:`) - and blank frames. Before, a comment-only frame was parsed as JSON and failed the whole
  stream with `JSON marshaller problem - response is not a JSON`.
- Comment lines inside a `data:` event are dropped as well; the `data:` field is recognized at
  a line start only, with or without the space after the colon (a payload mentioning `data: `
  inside a string value is no longer cut there); comment lines before a raw non-`data:` payload
  are dropped; CR and CRLF line breaks inside a frame are understood; and `[DONE]` is
  recognized with surrounding whitespace.
- The frame delimiter itself is unchanged (`\n\n` by default): servers that terminate events
  with `\r\n\r\n` still need the `framingDelimiter` argument of `execJsonStream`.
- Additive API change: `JsonStreamFrames.FrameResult` gains the `Skip` case, so an exhaustive
  match on `parseFrame` results needs a `Skip` arm.

## 1.1.2 (2026-10-14) - TLS 1.3 on the Play engines; Pekko flavor on play-ws 3.0.14

- **TLS:** the Play WS engines (`play-akka`, `play-pekko` and their `-stream` variants) now
  offer the JDK's default TLS client protocols - `TLSv1.3, TLSv1.2` on JDK 11+, the same list
  the `jdk` / `sttp` engines use (`jdk.tls.client.protocols` and `jdk.tls.disabledAlgorithms`
  apply). Before, they offered the shaded AsyncHttpClient's shipped default, `TLSv1.2, TLSv1.1,
  TLSv1` (current JDKs enable only 1.2 of those), so hosts that accept only TLS 1.3 failed the
  handshake (e.g. "close_notify during handshake"). The other engines already offered TLS 1.3
  via the JDK / Netty defaults.
  - An explicitly configured AHC list stays in force:
    `-Dplay.shaded.ahc.org.asynchttpclient.enabledProtocols=...` (also the no-code fix on 1.1.1:
    `...=TLSv1.3,TLSv1.2`), or that key in an `ahc.properties` resource under
    `play/shaded/ahc/org/asynchttpclient/config/` - the only place the shaded AHC reads it from
    (a classpath-root `ahc.properties` is ignored).
  - TLS 1.3 needs JDK 11+: the Scala 2.12 / 2.13 Akka artifacts (play-ws 2.1.11, shaded Netty
    4.1.45) enable TLS 1.3 cipher suites only on Java 11+, so on Java 8 they keep negotiating
    TLS 1.2 whatever the JDK offers; on a JSSE without TLS 1.3 the engines keep working with
    what it offers, and TLS 1.0 / 1.1 are offered only where the JDK itself enables them.
- **Pekko flavor on play-ws 3.0.14**, whose shaded AsyncHttpClient 2.16.1 fixes CVE-2026-40490
  and CVE-2026-45300 (credentials / cookies re-sent on a cross-origin redirect). Neither was
  exploitable through ws-client, as no engine follows redirects, but dependency scanners flag
  the old shaded jar. Its shaded Netty (4.1.138, from 4.1.118) is also stricter: responses with
  bare-LF header lines, `Content-Length` together with `Transfer-Encoding: chunked`, or control
  characters in header values now fail instead of being tolerated. The Akka flavor stays on
  play-ws 2.1.11, the last release for Scala 2.12.
- **Security fix:** the `zio-http` engine now verifies server certificates and host names
  against the JVM's default trust store - zio-http's own client default is Netty's insecure
  trust manager, which accepted any certificate. A ZIO application running the engine on its
  own `Client` (`ZioHttpWSClientEngine(client)` / `.layer()`) must configure that client's
  `.ssl(...)` itself.

## 1.1.1 (2026-09-28) - classifying HTTP errors of streamed calls

- A streamed call's non-2xx failure is now a structured `CequenceWSHttpStatusException`
  carrying `statusCode` and `body` (its message is unchanged), and so is the default
  `handleErrorCodes` failure of non-streamed calls.
- To get your service's own classification (`handleErrorCodes`) for stream errors too - as the
  non-streamed calls always had it - either extend `WSClientWithEngineOutputStreamingBase` and
  use its service-level `execJsonStream` / `execRawStream`, or append
  `.mapError(mapHttpStatusErrors)` to engine-level `engine.execJsonStream(site, ...)` calls.

## 1.1.0 (2026-09-27)

**New:** the `ws-client-zio-http` engine (engine id `zio-http`; zio-http / Netty + the ZIO
runtime) - see [Engines](docs/engines.md).

**⚠️ Upgrading to 1.1.0 - behavior changes**

- **No logging backend is shipped anymore.** `logback-classic` used to come transitively; add
  your own SLF4J backend (e.g. logback).
- **The sttp engine no longer follows redirects** - a 3xx comes back as a non-acceptable
  status, as on every other engine.
- **Streaming calls fail on a non-2xx status**, on every engine, with a `CequenceWSException`
  (`"<service>: HTTP 401 - <start of the error body>"`) instead of feeding the error page to
  the SSE / JSON parser. At most 4 KiB of the error body are read, within 10 s (or the request
  timeout, if shorter).
- **Multipart uploads send the file's base name**, never its local path, unless a display name
  is given.
- **Repeated form keys keep every value** (URL-encoded and multipart bodies) on every engine -
  previously some engines kept only the last one.
- **`DirectWSClient`** defaults a scheme-less URL to `https://` and rejects schemes other than
  http / https.
- **Jackson 2.22** is declared directly (play-json pins 2.11.4, affected by CVE-2025-52999 and
  CVE-2020-36518): deeply nested JSON now fails with a `CequenceWSException`, and a single JSON
  string value longer than Jackson's default maximum length no longer parses.
- **JsonRepair** keeps apostrophes inside values and keys on its repair path, and no longer
  crashes on values that are a single quote character.
- Proxy URLs are no longer echoed in logs or error messages (only host and port).

## 1.0.0 (2026-07-22)

- **Pluggable engines:** the engine-discovery SPI (`WSClientEngineRegistry`), a site-stateless
  engine contract (the site rides on every call as a `SiteBinding`), and new backends next to
  Play WS on Akka - the Pekko flavor (`ws-client-play-pekko`, `ws-client-play-pekko-stream`),
  `ws-client-jdk`, `ws-client-sttp` and `ws-client-pekko-http`.
- **⚠️ Upgrading from ≤ 0.8.1 - artifact rename.** The Akka-based Play modules were renamed
  for symmetry with the Pekko flavor: `ws-client-play` → `ws-client-play-akka` and
  `ws-client-play-stream` → `ws-client-play-akka-stream`. The old and new artifacts contain the
  same packages and classes but have different artifact ids, so dependency resolution does
  **not** evict the old ones - see
  [Compatibility and upgrading](docs/compatibility.md#artifacts-renamed-in-100) for the
  exclusion rule.
