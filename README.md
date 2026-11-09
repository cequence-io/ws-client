# WS Client (Cequence)
[![version](https://img.shields.io/badge/version-1.1.5-green.svg)](https://cequence.io) [![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](https://opensource.org/licenses/MIT) [![Twitter Follow](https://img.shields.io/twitter/follow/cequence_io?style=social)](https://twitter.com/0xbnd)

This repository contains a simple and efficient Web Service client implemented in Scala. The client is designed to interact with RESTful web services, making it easy to send requests, handle responses, and manage errors.

Pluggable HTTP engines - Play WS (Akka or Pekko), pekko-http, JDK HttpClient, sttp, and zio-http - picked up automatically from the classpath (see *Engine Self-Discovery* below).

**🔥 New in 1.2**: `AsyncStream` - one backend-neutral stream type for every engine, with native interop for Akka, Pekko, fs2 and ZIO (an engine's own `Source` comes back as itself, no bridge), binary-safe downloads on every streaming engine, a neutral `AsyncScheduler`, and Scala 3 artifacts for the Pekko modules (see *Backend-Neutral Streaming* below).

**New in 1.1**: the `ws-client-zio-http` engine (zio-http / Netty + ZIO runtime), consistent streaming errors across engines, and structured HTTP-status failures that services can classify via `handleErrorCodes`.

As a part of this suite we also provide the [json-repair](./json-repair/README.md) library that can be used to fix common JSON syntax errors, repair malformed JSON objects and arrays.

## Installation 🚀

The currently supported Scala versions are **2.12, 2.13** and **3** for the core and the Akka-based modules, and **2.13** and **3** for the Pekko-based modules. The Scala 3 artifacts of the core, Akka, `jdk` and `json-repair` modules are compiled with Scala 3.2, so they serve Scala 3.2 and 3.3+ projects alike; the Pekko, `fs2` and `zio` modules - whose own dependencies need it - build theirs with Scala 3.3 LTS.

To install the library, add the following dependency to your *build.sbt*

```
"io.cequence" %% "ws-client-play-akka" % "1.1.5"     // Akka-based (Play WS 2.x)
```

or, for the Pekko flavor:

```
"io.cequence" %% "ws-client-play-pekko" % "1.1.5"    // Pekko-based (Play WS 3.x)
```

or to *pom.xml* (if you use maven)

```
<dependency>
    <groupId>io.cequence</groupId>
    <artifactId>ws-client-play-akka_2.12</artifactId>
    <version>1.1.5</version>
</dependency>
```

If you only need the core abstractions without Akka/Pekko dependencies:

```
"io.cequence" %% "ws-client-core" % "1.1.5"
```

> ⚠️ **The Akka and Pekko flavors are mutually exclusive on one classpath.** The Pekko modules are generated from the Akka ones and deliberately keep the same package and class names (the same trade-off Play made between 2.9 and 3.0), so downstream code compiles unchanged against either flavor - switch by swapping the artifact and your `Materializer` import. Never depend on both: which classes (implicits such as `asSafeSource` included) actually load then depends on jar order. `WSClientEngineRegistry` detects such duplicated classes at runtime and logs a warning naming the conflicting jars; set the config key `ws-client.strict-classpath = true` (or `-Dws-client.strict-classpath=true`) to fail engine resolution with an exception instead - recommended for CI.

> ℹ️ **Logging:** the library logs through the SLF4J API only and ships no logging backend - bring your own (e.g. logback). Keep `play.shaded.ahc.org.asynchttpclient.netty.handler` at `INFO` or above: at `DEBUG` Play's shaded AsyncHttpClient logs complete request headers, including `Authorization`.

> ℹ️ **1.2.0 - backend-neutral streaming.**
> - **New:** `AsyncStream`, a stream type every streaming engine returns and every consumer runs: plain `Future`s, Akka, Pekko, fs2 (`ws-client-fs2`) or ZIO (`ws-client-zio`) - see *Backend-Neutral Streaming* below. Also new: binary-safe `Response.bytes` on every engine, download helpers on the service bases, `TransportSettings.httpVersion` (HTTP/1.1 or HTTP/2 on `jdk`), and the Pekko modules and `pekko-http` for Scala 3.3.
> - **JSON request bodies** on `jdk`, `sttp`, `pekko-http` and `zio-http` are now rendered exactly as the Play engines render them (Play's `Json.toBytes`: characters outside the Basic Multilingual Plane, such as emoji, go out as `\uXXXX` escapes). The body bytes change for request signers; `engine.renderJsonBody` returns them.
> - **`jdk` query parameters** are RFC 3986-encoded, like the Play engines': a space is sent as `%20` (was `+`).
> - **Restricted headers:** `jdk` - and `sttp` on its own JDK backend - drop a header `java.net.http` sets itself (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`) with a one-time warning instead of failing the request.
> - **Streams:** an oversized frame fails with the typed `CequenceWSFrameTooLongException` (a `CequenceWSException`; message unchanged); a stream's non-2xx failure is logged at DEBUG instead of ERROR (it still reaches the caller as `CequenceWSHttpStatusException`); a deadlock of `jdk` / `zio-http` streams - a downstream cancel or request racing the next element or the end of the response - is fixed.
> - **`TransportSettings`** gained a field: code compiled against 1.1.x keeps working (construction, `copy`, accessors and pattern matches), except, on Scala 2, an explicit call of its synthetic `unapply`, whose result is now a three-element tuple.
> - **Use one version for all ws-client artifacts** on a classpath: the interop modules call core internals.

> ℹ️ **1.1.5 - security fixes.**
> - **Query parameters on the Play engines** (`play-akka`, `play-pekko` and their `-stream` variants) are now percent-encoded, as on every other engine. Before, `params` and `extraParams` went into the URL verbatim: a `#` cut the query off (with every parameter after it, extra params included), `&` and `=` split a value, and `+` read as a space. **Behavior change:** if you pre-encoded values for the Play engines, stop - a pre-encoded `a%20b` is now sent as the literal text `a%20b`. A query written into the endpoint string itself is still sent byte for byte.
> - **Proxy `CONNECT` on the Play engines:** a proxy's non-200 reply to `CONNECT` now fails the request. Before, the Play engines took such a reply as the https site's own response, so the proxy - or anyone on the plain-HTTP hop to it - could make up an "HTTPS" response (e.g. a 202 with any body).
> - **Response-body timeouts on `jdk` and `sttp`:** `requestTimeout` now covers the response body. `java.net.http`'s request timeout stops at the response headers, so a server that sent the headers and then stalled hung the call forever (on `sttp` also holding a thread of the global `ExecutionContext`); it now fails with an `HttpTimeoutException` and the connection is closed. **Behavior change:** `jdk` streams (SSE / JSON) now fail after `readTimeout` without data while the consumer waits for it (default: `requestTimeout`; before, `jdk` ignored `readTimeout`). Time the consumer is busy does not count, so long healthy streams are unaffected.
> - **`pekko-http`:** a response that arrives after `requestTimeout` is aborted (connection closed) instead of being read to its end. The per-host pool now allows 1024 connections / 2048 open requests (pekko-http's default: 4 / 32, which queued every call to a host behind its slow ones), unless your configuration sets its own values. A copy of the engine on the same actor system shares its per-host pools.
> - **`asSafe` error messages** (`JsonUtil.JsonOps`) show a compact excerpt of the mismatching JSON, at most 500 characters, instead of pretty-printing the whole value twice (a 643 KB response could exhaust a 512 MiB heap). **Behavior change:** the message is no longer wrapped in "Error thrown while processing a JSON".
> - **json-repair:** a text that is exactly one valid JSON value is now decoded as such (`Json.parse`, plus the `"true"` / `"false"` / `"null"` and `convertNumbers` coercions where they applied before). Before, valid nested JSON went through the repair parser, where string content could add, replace or drop sibling members. **Behavior change:** such documents now decode exactly, and objects with more than 4 keys keep their key order. Malformed input repairs as before.

> ℹ️ **1.1.4 - security hardening and json-repair fixes.** The Play engines (`play-akka`, `play-pekko` and their `-stream` variants) no longer keep a cookie jar. They ran on the shaded AsyncHttpClient's builder default - ONE cookie store for the whole engine, shared by every `SiteBinding` - so a `Set-Cookie` that one site or tenant received was sent on later requests of every other `SiteBinding` with a matching host name (any port; on the Akka flavor also a host that a response named in its `Domain` attribute). Play's own client configuration disables that store; ws-client builds the AHC configuration itself and kept it. Now no engine stores or replays cookies (the other engines never did): `Set-Cookie` headers still show up in `RichResponse.headers`, and a site that needs a cookie sends it itself - e.g. a `Cookie` header in its `WsRequestContext`. Behavior change only for code that relied on the Play engines' implicit cookie continuity (e.g. sticky-session cookies): pass the cookie explicitly. The Play engines also turn redirect following off explicitly: they relied on the shaded AsyncHttpClient's default, which `-Dplay.shaded.ahc.org.asynchttpclient.followRedirect=true` flips JVM-wide - and then re-sent bodies and custom secret headers such as `X-Api-Key` (on the Akka flavor `Authorization` and `Cookie` too: CVE-2026-40490, CVE-2026-45300) to whatever origin a redirect named. On the Akka flavor (`play-akka`, `play-akka-stream`: play-ws 2.1.11 / 2.2.0-M2 with a shaded AsyncHttpClient 2.10.5 / 2.12.3, which inflates gzip / deflate response bodies without any limit - CVE-2026-85721; a 510 KB response exhausted a 768 MB heap), a response whose decompressed body exceeds 256 MiB now fails with a `DecompressionException` - the Pekko flavor's AsyncHttpClient 2.16.1 limit, set by the same property, `play.shaded.ahc.org.asynchttpclient.maxDecompressedResponseSize` (`0` or less: no limit). The limit counts the whole response, so a long-lived gzip / deflate-encoded SSE or JSON stream on `play-akka-stream` now fails once it has decompressed 256 MiB, as it already does on `play-pekko-stream`; raise the limit or set it to `0` for such streams. The Akka flavor's shaded Netty (4.1.45 / 4.1.60) still has known CVEs that a client does not reach (HTTP/2, SPDY, SNI, CORS and server-side decoders); the Pekko flavor's (4.1.138) has none. Dependency updates that clear scanner findings with no reachable vulnerability: the Scala 2.12 artifacts move from Akka 2.6.1 to 2.6.20 (2.6.1 pulled protobuf-java 3.10.0), `play-akka-stream` to akka-http 10.5.3, and `zio-http` declares Bouncy Castle 1.86 directly (zio-http pulls 1.84 through netty-pkitesting). Multipart: a quote, CR or LF in a caller-supplied data-part name, file key or display file name can no longer add parameters, part headers or a content prefix to its part (on `pekko-http` such a name in a data part or file key dropped the part's `Content-Disposition`). It is now encoded as `%22`, `%0D`, `%0A` (the browser / RFC 7578 encoding Play 2.8+ uses) on every engine, except in a display file name on `pekko-http`, which pekko-http renders itself, as before: `?` in place of each such character, plus the name RFC 5987-encoded in a `filename*` parameter. Other names go out byte-for-byte as before, except a data-part name that is not an HTTP token (e.g. `tags[]`), which the Play and in-memory bodies now quote like every other engine does. A file whose display name is only dots (e.g. `...`) no longer fails the request with a `NoSuchElementException` (the default content-type lookup by extension), and a file name without a dot is no longer taken as its own extension: a file named e.g. `pdf` or `json` went out as `application/pdf` / `application/json`, and now gets no content type, like any other name without an extension (an explicit `contentType` still applies). `sttp`: a failed call's exception no longer carries the request URI - sttp's own messages included it in full, query-parameter API keys (`extraParams`) included; refused / reset connections and read failures now arrive as a `CequenceWSException` (with the transport exception as its cause) instead of the raw `SttpClientException` - a site `recoverErrors` matching sttp types needs to match the Cequence type or the cause. The `jdk` / `zio-http` SSE framing costs linear time per received chunk (a chunk of many tiny or blank frames was quadratic: a 1 MiB chunk took about 35 s of CPU). Two json-repair fixes, with repair results unchanged: input nested deeper than the calling thread's stack (a few KB of `[`) now fails with an ordinary `IllegalArgumentException` instead of a `StackOverflowError`, which escaped `Try` / `NonFatal` recovery and left a `Future` running the repair incomplete forever; and large arrays or many concatenated top-level values repair in linear time (200,000 elements: minutes before, well under a second now). Two json-repair crashes are fixed: `fromFile` threw a `StringIndexOutOfBoundsException` on a file with any multibyte (non-ASCII) character (and with a small `chunkLength` it could garble such characters instead) - the file is now decoded at once, in the platform charset as before, and repaired exactly like the same string (`chunkLength` no longer has an effect); and a top-level `null` after the first value (e.g. `[1] null` with `handleLiterals = true`) threw a `NullPointerException` - it is now kept as a value, like `true` / `false`. A json-repair repair no longer loops forever, allocating until the heap is exhausted, on an array value that consumes no input (e.g. `[{"a": {""}}, b"c": 1]`); trimming trailing whitespace is linear (a run of 200,000 spaces inside a broken string took over a minute); and escape sequences in strings on the repair path - which valid nested JSON takes too - are decoded the way a JSON parser decodes them. Before, `\n`, `\t`, `\r`, `\b` and `\\` kept their backslash next to the decoded character (`"x\ny"` came back as `x\`, a line break, `y`; a trailing `\n` as a stray backslash), `\uXXXX`, `\/` and `\f` were not decoded at all, and an escaped quote in a single-quoted string (`'it\'s'`) kept its backslash. Behavior change: repaired strings that contain escape sequences come out different - correct now - and, because some repair heuristics look at the decoded text (duplicate keys inside arrays, the `"true"` / `"null"` coercion, empty strings), so does the shape of some broken inputs' repairs. On Scala 3, `play-akka-stream` now streams incrementally: Play WS 2.2.0-M2, the last Play WS built for Scala 3.2, implements `stream()` as its buffered `execute()`, so an SSE / JSON stream delivered all of its events only once the response had ended, and cancelling the stream could not abort the request. The engine now takes the client's own streaming path, as Play WS 2.1.x and 3.x do. And json-repair numbers no longer change on the repair path, which valid nested JSON takes too: an exponent's `+` sign ended the number there (`2.50E+3` came back as `2.5`, the rest of it as debris), and every number became a double or a long, so digits beyond a double's precision were rounded (`0.255621758985604992` came back as `0.255621758985605`), a tiny number became `0`, a huge one (`1e400`) failed the repair with a `NumberFormatException`, and an integer beyond `Long` came back as a string. Numbers now repair the way Jackson / play-json parse them (to play-json's 34 significant digits). Behavior change for `repairJsonAsValue`: such a number is now a `BigDecimal` - a number a `Double` / `Long` holds exactly keeps that type.

> ℹ️ **1.1.3 - SSE comment frames no longer fail a JSON stream.** Every streaming engine (`play-akka-stream`, `play-pekko-stream`, `pekko-http`, `jdk`, `zio-http`) now ignores SSE comment lines - lines starting with `:`, which servers send as keep-alives while a slow model works (OpenRouter's `: OPENROUTER PROCESSING`, OpenAI's bare `:`) - and blank frames. Before, a comment-only frame was parsed as JSON and failed the whole stream with `JSON marshaller problem - response is not a JSON`. Comment lines inside a `data:` event are dropped as well, the `data:` field is recognized at a line start only, with or without the space after the colon (a payload mentioning `data: ` inside a string value is no longer cut there), comment lines before a raw non-`data:` payload are dropped, CR and CRLF line breaks inside a frame are understood, and `[DONE]` is recognized with surrounding whitespace. The frame delimiter itself is unchanged (`\n\n` by default): servers that terminate events with `\r\n\r\n` still need the `framingDelimiter` argument of `execJsonStream`. Additive API change: `JsonStreamFrames.FrameResult` gains the `Skip` case, so an exhaustive match on `parseFrame` results needs a `Skip` arm.

> ℹ️ **1.1.2 - TLS 1.3 on the Play engines; Pekko flavor on play-ws 3.0.14.** The Play WS engines (`play-akka`, `play-pekko` and their `-stream` variants) now offer the JDK's default TLS client protocols - `TLSv1.3, TLSv1.2` on JDK 11+, the same list the `jdk` / `sttp` engines use (`jdk.tls.client.protocols` and `jdk.tls.disabledAlgorithms` apply). Before, they offered the shaded AsyncHttpClient's shipped default, `TLSv1.2, TLSv1.1, TLSv1` (current JDKs enable only 1.2 of those), so hosts that accept only TLS 1.3 failed the handshake (e.g. "close_notify during handshake"). An explicitly configured AHC list stays in force: `-Dplay.shaded.ahc.org.asynchttpclient.enabledProtocols=...` (also the no-code fix on 1.1.1: `...=TLSv1.3,TLSv1.2`), or that key in an `ahc.properties` resource under `play/shaded/ahc/org/asynchttpclient/config/` - the only place the shaded AHC reads it from (a classpath-root `ahc.properties` is ignored). TLS 1.3 needs JDK 11+: the Scala 2.12 / 2.13 Akka artifacts (play-ws 2.1.11, shaded Netty 4.1.45) enable TLS 1.3 cipher suites only on Java 11+, so on Java 8 they keep negotiating TLS 1.2 whatever the JDK offers; on a JSSE without TLS 1.3 the engines keep working with what it offers, and TLS 1.0/1.1 are offered only where the JDK itself enables them. The other engines already offered TLS 1.3 via the JDK / Netty defaults. Also: the Pekko flavor moves to play-ws 3.0.14, whose shaded AsyncHttpClient 2.16.1 fixes CVE-2026-40490 and CVE-2026-45300 (credentials / cookies re-sent on a cross-origin redirect). Neither was exploitable through ws-client, as no engine follows redirects, but dependency scanners flag the old shaded jar. Its shaded Netty (4.1.138, from 4.1.118) is also stricter: responses with bare-LF header lines, `Content-Length` together with `Transfer-Encoding: chunked`, or control characters in header values now fail instead of being tolerated. The Akka flavor stays on play-ws 2.1.11, the last release for Scala 2.12. And a security fix: the `zio-http` engine now verifies server certificates and host names against the JVM's default trust store - zio-http's own client default is Netty's insecure trust manager, which accepted any certificate. A ZIO application running the engine on its own `Client` (`ZioHttpWSClientEngine(client)` / `.layer()`) must configure that client's `.ssl(...)` itself.

> ℹ️ **1.1.1 - classifying HTTP errors of streamed calls.** A streamed call's non-2xx failure is now a structured `CequenceWSHttpStatusException` carrying `statusCode` and `body` (its message is unchanged), and so is the default `handleErrorCodes` failure of non-streamed calls. To get your service's own classification (`handleErrorCodes`) for stream errors too - as the non-streamed calls always had it - either extend `WSClientWithEngineOutputStreamingBase` and use its service-level `execJsonStream` / `execRawStream`, or append `.mapError(mapHttpStatusErrors)` to engine-level `engine.execJsonStream(site, ...)` calls.

> ⚠️ **Upgrading to 1.1.0** - behavior changes:
> - **No logging backend is shipped anymore.** `logback-classic` used to come transitively; add your own SLF4J backend (e.g. logback).
> - **The sttp engine no longer follows redirects** - a 3xx comes back as a non-acceptable status, as on every other engine.
> - **Streaming calls fail on a non-2xx status**, on every engine, with a `CequenceWSException` (`"<service>: HTTP 401 - <start of the error body>"`) instead of feeding the error page to the SSE/JSON parser. At most 4 KiB of the error body are read, within 10 s (or the request timeout, if shorter).
> - **Multipart uploads send the file's base name**, never its local path, unless a display name is given.
> - **Repeated form keys keep every value** (URL-encoded and multipart bodies) on every engine - previously some engines kept only the last one.
> - **`DirectWSClient`** defaults a scheme-less URL to `https://` and rejects schemes other than http/https.
> - **Jackson 2.22** is declared directly (play-json pins 2.11.4, affected by CVE-2025-52999 and CVE-2020-36518): deeply nested JSON now fails with a `CequenceWSException`, and a single JSON string value longer than Jackson's default maximum length no longer parses.
> - **JsonRepair** keeps apostrophes inside values and keys on its repair path, and no longer crashes on values that are a single quote character.
> - Proxy URLs are no longer echoed in logs or error messages (only host and port).
>
> New: the **`ws-client-zio-http`** engine (engine id `zio-http`) - see the engine table below.

> ⚠️ **Upgrading from ≤ 0.8.1:** the Akka-based Play modules were renamed for symmetry with the Pekko flavor - `ws-client-play` → `ws-client-play-akka` and `ws-client-play-stream` → `ws-client-play-akka-stream`. The old and new artifacts contain the same packages and classes but have different artifact ids, so dependency resolution will **not** evict the old ones - with both on the classpath, which classes win depends on jar order, and engine discovery may not find the `play-akka`/`play-akka-stream` providers. Make sure no transitive dependency still pulls the old artifacts, or exclude them explicitly:
>
> ```scala
> libraryDependencies += ("your" %% "dependency" % "x.y.z")
>   .excludeAll(
>     ExclusionRule("io.cequence", s"ws-client-play_${scalaBinaryVersion.value}"),
>     ExclusionRule("io.cequence", s"ws-client-play-stream_${scalaBinaryVersion.value}")
>   )
> ```

## Module Structure

Akka flavor (Scala 2.12, 2.13, 3):

- **ws-client-core** - Core abstractions and interfaces (Akka-free)
- **ws-client-core-akka** - Akka-based streaming extensions
- **ws-client-play-akka** - Play WS 2.x backend implementation (depends on core-akka); named `ws-client-play` before 1.0.0
- **ws-client-play-akka-stream** - SSE/WebSocket streaming support (akka-http); named `ws-client-play-stream` before 1.0.0

Pekko flavor (Scala 2.13, 3; sources generated from the Akka modules at build time):

- **ws-client-core-pekko** - Pekko-based streaming extensions
- **ws-client-play-pekko** - Play WS 3.x (`org.playframework`) backend implementation
- **ws-client-play-pekko-stream** - SSE/WebSocket streaming support (pekko-http)

Other backends:

- **ws-client-jdk** - backend on the JDK 11+ `java.net.http.HttpClient` (Scala 2.12, 2.13, 3); no Akka/Pekko/Play WS - it needs only `ws-client-core` (play-json, Jackson, Typesafe config, scala-logging)
- **ws-client-sttp** - backend over [sttp client4](https://sttp.softwaremill.com) (Scala 2.12, 2.13), unlocking any sttp `Future` backend (OkHttp, Armeria, Pekko-HTTP, ...)
- **ws-client-pekko-http** - direct pekko-http client backend (Scala 2.13 / 3.3), no Play WS / shaded AsyncHttpClient layer; supports input and output (SSE) streaming
- **ws-client-zio-http** - backend on the [zio-http](https://zio.dev/zio-http) (Netty) client and the ZIO runtime (Scala 2.12, 2.13); output (SSE) streaming via `Flow.Publisher`. Server certificates are verified against the JVM's default trust store (`ClientSSLConfig.FromSystemTrustStore`). A ZIO application can run it on its own `Client` via `ZioHttpWSClientEngine.layer()` - then configure that client's `.ssl(...)` yourself, as zio-http's default trusts any certificate. With a proxy, target host names are still resolved through local DNS

Stream interop (see *Backend-Neutral Streaming*):

- **ws-client-fs2** - `AsyncStream` <-> fs2 `Stream` / `Pipe` (Scala 2.12, 2.13, 3)
- **ws-client-zio** - `AsyncStream` <-> ZIO `ZStream` / `ZPipeline` (Scala 2.12, 2.13, 3)
- the Akka / Pekko interop lives in **ws-client-core-akka** / **ws-client-core-pekko**

Independent:

- **json-repair** - JSON repair utility

## Engine Self-Discovery 🔎

Backends register a `WSClientEngineProvider` via `java.util.ServiceLoader`, so an engine can be obtained without naming the backend class:

```scala
import io.cequence.wsclient.service.spi.{TransportSettings, WSClientEngineRegistry}

val engine = WSClientEngineRegistry() // site-stateless: the target site rides on each call
// ... use the engine (it owns its actor system) ...
engine.close() // releases the HTTP client and the owned actor system; idempotent
```

Provider resolution order:

1. an explicit engine id: `WSClientEngineRegistry(TransportSettings(), Some("play-pekko"))`
2. the config key `ws-client.engine` (also settable as a system property, `-Dws-client.engine=play-akka`, which takes precedence per standard Typesafe Config semantics)
3. auto-selection: a single provider is used as is; with several, the highest priority wins

Config keys:

| Key | Default | Meaning |
|---|---|---|
| `ws-client.engine` | (unset) | Engine id to use when none is passed explicitly |
| `ws-client.strict-classpath` | `false` | Fail engine resolution (instead of logging a warning) when mutually exclusive ws-client artifacts are detected on the classpath - mixed Akka/Pekko flavors, or a pre-1.0 artifact (`ws-client-play`, `ws-client-play-stream`) next to its renamed successor |

Available engines (auto-selection picks the highest priority present):

| Engine id | Module | Priority | Capabilities | `AsyncStream` (since 1.2) |
|---|---|---|---|---|
| `play-pekko-stream` | ws-client-play-pekko-stream | 21 | input + output streaming, multipart | native Pekko `Source` |
| `play-pekko` | ws-client-play-pekko | 20 | input streaming, multipart | - |
| `pekko-http` | ws-client-pekko-http | 15 | input + output streaming, multipart | native Pekko `Source` |
| `play-akka-stream` | ws-client-play-akka-stream | 11 | input + output streaming, multipart | native Akka `Source` |
| `play-akka` | ws-client-play-akka | 10 | input streaming, multipart | - |
| `zio-http` | ws-client-zio-http | 7 | output streaming (`Flow.Publisher`), multipart | via `Flow.Publisher` |
| `sttp` | ws-client-sttp | 5 | multipart | - |
| `jdk` | ws-client-jdk | 0 | output streaming (`Flow.Publisher`), multipart (in-memory) | via `Flow.Publisher` |

The stream engines are strict supersets of their base engines (same implementation plus SSE/JSON output streaming), hence the slightly higher priorities.

For a **typed streaming engine** use `StreamedEngineRegistry` (in ws-client-core-akka / ws-client-core-pekko) - the core registry cannot mention Akka/Pekko types, so this typed lookup filters providers by capability and returns the streaming-typed engine:

```scala
import io.cequence.wsclient.service.spi.{StreamedEngineRegistry, TransportSettings}

// WSClientEngine with WSClientOutputStreamExtraAkka (Pekko: ...ExtraPekko) - for execJsonStream / execRawStream (SSE)
// Engines created via discovery own a DAEMON-threaded ActorSystem (close() terminates it;
// a leaked engine won't block JVM exit). For a caller-shared ActorSystem use the direct
// constructors (PlayWSClientEngine / PlayWSStreamClientEngine / PekkoHttpWSClientEngine).
// Backend-agnostic alternative (no akka/pekko on the consumer side; works with the jdk engine):
// WSClientEngineRegistry.outputStreamed(...) returns WSClientEngine with WSClientOutputStreamCore
// (java.util.concurrent.Flow.Publisher-typed execJsonStreamPublisher / execRawStreamPublisher)
val streamedEngine = StreamedEngineRegistry.outputStreamed()

// WSClientEngine with WSClientInputStreamExtraAkka (Pekko: ...ExtraPekko) - for execPOSTSource
val inputEngine = StreamedEngineRegistry.inputStreamed()
```

Engines created through discovery own their execution environment (actor system / backend). To supply your own `Materializer`/`ExecutionContext`/backend, use the explicit factories (`PlayWSClientEngine(transportSettings)`, `PlayWSStreamClientEngine(...)`, `JdkWSClientEngine(...)`, `SttpWSClientEngine(...)`, `PekkoHttpWSClientEngine(...)`, `ZioHttpWSClientEngine(client)` / `ZioHttpWSClientEngine.layer()`) - they take only client-level `TransportSettings`; the site is per call.

## One Engine, Many Sites 🔗

Engines are SITE-STATELESS: an engine owns the HTTP client, the connection pool, and its execution environment (configured by `TransportSettings` - timeouts, proxy), while everything site-specific (base URL, auth context, error recovery, logging label) rides in a `SiteBinding` passed to every call. Timeouts are deliberately engine-level only - keeping engines fully stateless (no per-site client caches); a service that needs different timeouts uses its own engine. ONE engine therefore serves any number of sites/providers:

```scala
import io.cequence.wsclient.domain.SiteBinding
import io.cequence.wsclient.service.spi.{StreamedEngineRegistry, TransportSettings}

val engine = StreamedEngineRegistry.outputStreamed()   // one pool + one (daemon) actor system
// non-streaming variant: WSClientEngineRegistry()

val siteA = SiteBinding("https://api.a.com", ctxA, label = Some("a"))
val siteB = SiteBinding("https://api.b.com", ctxB, label = Some("b"))

engine.execGETRich(siteA, "models")            // same engine...
engine.execJsonStream(siteB, "chat", "POST")   // ...different site per call

engine.close()  // the one teardown - close it once, when done with all sites/services
```

Services built on `WSClientWithEngineBase` hold their `SiteBinding` once and the base threads it into every call; a service closes the engine only when it owns it (`ownsEngine` - false for shared, caller-supplied engines).

Need different client-level settings (timeouts, proxy) for one consumer? **Copy the engine** - the copy builds its OWN HTTP client from the (optionally overridden) settings and is closed independently (on `pekko-http` a copy on the same actor system shares that system's per-host connection pools - pekko-http keeps them per system, keyed by host and pool settings):

```scala
val slow = engine.copy(TransportSettings(timeouts = Timeouts(requestTimeout = Some(600000))))
// ^ own client, SAME actor system/materializer/EC - close the env-owning original AFTER its copies
val island = engine.copy(reuseExecContext = false)
// ^ fully independent: its own owned daemon actor system (discovery-created engines only;
//   engines built on a caller-supplied environment throw - create those via their factory)
```

**Query-parameter encoding**: every engine takes **raw (unencoded) parameter keys and values** - `params` and `WsRequestContext.extraParams` - and percent-encodes them for you. Up to 1.1.4 the Play-based engines put them into the URL verbatim: a `#` cut the query off (with every parameter after it, extra params included), `&` and `=` split a value, and `+` read as a space. If you pre-encoded values for the Play engines, stop: a pre-encoded value like `a%20b` is now sent as the literal text `a%20b`, as on every other engine. A query written into the endpoint string itself (e.g. `"items?api-version=2024-02-01"`) is not a parameter - encode that one yourself.

**Cookies**: engines keep no cookie jar - a `Set-Cookie` response header is surfaced in `RichResponse.headers` but never stored or sent on a later request, so one site's (or tenant's) cookies can never reach another `SiteBinding`. A site that needs a cookie sends it itself, as a `Cookie` header in its `WsRequestContext` (or from `requestContextFun`).

**Restricted headers**: `java.net.http` refuses a few headers it sets itself (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade` on current JDKs). Since 1.2.0 the `jdk` engine - and `sttp` on its own JDK backend - drops such a header with a one-time warning instead of failing the request (up to 1.1.5 the request failed). Whatever the JDK lets through, including `-Djdk.httpclient.allowRestrictedHeaders`, is still sent, and any other header the JDK refuses (JDK 11 before 11.0.14 also refuses e.g. `Referer` and `Origin`) still fails the request. The other engines hand the header to their HTTP library, which may still set its own value (e.g. `Host` from the URL).

**Proxy support**: `TransportSettings.proxyURL` (accepted forms: `host:port` or `scheme://host:port`; only the host and port are used - the proxy is always a plain HTTP proxy, even with an `https://` scheme (`https` targets are still tunnelled through it), and credentials, path or query in the URL are ignored: proxy authentication is not supported) is honored by the Play, `jdk`, `sttp`, and `zio-http` engines (`zio-http` tunnels via `CONNECT` and still resolves the target host through local DNS); the `pekko-http` engine logs a warning and ignores it (pekko-http only supports CONNECT-tunneling proxies).

**Custom error recovery** (`SiteBinding.recoverErrors`) composes with the engine's built-in transport-failure normalization: your partial function sees the Cequence exception taxonomy (`CequenceWSTimeoutException`, `CequenceWSUnknownHostException`, ...) for failures the backend recognizes, so the same recovery logic is portable across engines.

**Dynamic request context** (`SiteBinding.requestContextFun`): when set, the request context is re-evaluated on every request - e.g. to refresh an expiring auth token - on all engines. Client-level settings (`TransportSettings`: connect timeout, proxy; all timeouts on the Play engines) are captured once, at engine construction or first use.

Note for sbt-assembly users: ServiceLoader registrations live in `META-INF/services`, so fat jars need `MergeStrategy.concat` (or `filterDistinctLines`) for that path.

## Backend-Neutral Streaming 🌊

Since 1.2.0 every streaming engine returns its SSE / JSON and raw byte streams also as an `AsyncStream` (`io.cequence.wsclient.stream`, in `ws-client-core` - no Akka, Pekko, fs2 or ZIO needed), and every consumer runs it: plain `Future`s, Akka, Pekko, fs2 or ZIO.

```scala
import io.cequence.wsclient.service.spi.WSClientEngineRegistry
import io.cequence.wsclient.stream.AsyncStream

val engine = WSClientEngineRegistry.outputStreamed()   // any engine with output streaming

val texts: AsyncStream[String] =
  engine
    .execJsonAsyncStream(site, "chat/completions", "POST", bodyParams = body)
    .map(json => (json \ "text").asOpt[String].getOrElse(""))
    .filter(_.nonEmpty)

texts.runForeach(println)           // runs on the given ExecutionContext - nothing else needed
```

**Semantics.**
- **Cold and re-runnable:** building a stream does no I/O; each run is a new request (so a re-run is a new - possibly billed - call). Every thunk (`defer`, a `stateful` init, a publisher factory, `future`, a `concurrently` task, a blocking `open`) runs per run.
- **Per-run state:** allocate mutable state only inside `AsyncStream.defer`, a `stateful` init or a `statefulMapConcat` create - never when building a stream or a pipe, or every run shares it.
- **Back-pressured, one consumer per run;** fan-out goes through the hot `AsyncBroadcastHub`.
- **Cancellation** travels to the leaf: it aborts the HTTP request, closes an `InputStream` or a blocking iterator. A downstream `take(n)` cancels the upstream after the n-th element.
- **Failures** of user functions (and failed `evalMap` futures) fail the stream with that exception, unwrapped; there is no resume supervision. `guaranteeCase` / `onTerminate` hooks run exactly once per run (`Completed` / `Failed` / `Cancelled`), and a `stateful` end hook and a `Completed` hook finish before the completion goes downstream.

**Operators:** `map`, `mapConcat`, `filter`, `filterNot`, `collect`, `evalMap` (one future at a time), `stateful` / `statefulMapConcat`, `scan`, `fold`, `zipWithIndex`, `take`, `takeWhile`, `drop`, `concat` / `++`, `flatMapConcat`, `via(pipe)`, `zipVia`, `mapError`, `recover`, `recoverWith`, `onTerminate` / `guaranteeCase`, `concurrently(task, awaitCompletion)`, `preflight`. **Running:** `runForeach`, `runFold`, `runToSeq`, `runHead(Option)`, `runLast(Option)`, `runDrain`, `run(f)` (cancellable), `toPublisher`; `describe` shows the plan (never elements). `AsyncPipe[A, B]` is a reusable transformation with the same operators (`AsyncPipe.of[A].map(...)`); `ByteFraming.delimiter` / `lines` and `JsonStreamPipes.jsonFrames` frame any byte stream exactly as the engines do. Byte streams carry the immutable `Bytes`.

**Interop - native round trips.** A stream is a plan, so a backend runs its own streams natively: an Akka engine's stream consumed as an Akka `Source` is that engine's own `Source`, with every operator an Akka stage - `source.toAsyncStream.toSource eq source`. A bridge through `java.util.concurrent.Flow` is built only at a foreign leaf (once) or a foreign native pipe (twice).

| Library | Module | Import | To | From |
|---|---|---|---|---|
| Akka | ws-client-core-akka | `io.cequence.wsclient.stream.SourceInterop._` | `.toSource`, `.toByteStringSource`, `.toFlow` | `.toAsyncStream`, `.toAsyncByteStream`, `.toAsyncPipe` |
| Pekko | ws-client-core-pekko | the same names | the same | the same |
| fs2 | ws-client-fs2 | `io.cequence.wsclient.stream.Fs2Interop._` | `.toFs2[F]`, `.toFs2Pipe[F]` | `.toAsyncStream` (needs an `Fs2Runner`, e.g. from an `IORuntime`), `Fs2Interop.fromFs2Pipe` |
| ZIO | ws-client-zio | `io.cequence.wsclient.stream.ZioInterop._` | `.toZStream`, `.toZStreamFunction` | `.toAsyncStream` (needs a `Runtime`), `ZioInterop.fromZPipeline` |

The `Materializer` / `Fs2Runner` / `Runtime` a conversion takes is used only when the stream is consumed outside its own library. For 1.x code written against Akka `Source`s, `import io.cequence.wsclient.stream.SourceConversions._` (likewise `Fs2Conversions._`, `ZioConversions._`) adds opt-in implicit conversions - e.g. `stream.runWith(Sink.foreach(println))` compiles unchanged; they are `implicit def`s, warning-free under `-feature` on Scala 2 and 3. Count bridges in a test with `StreamDiagnostics.counted(stream.toSource)` - scoped to the streams compiled or started inside it. Three differences between backends: on a failure, Akka's / Pekko's asynchronous stages (`flatMapConcat`, `mapAsync`) fail fast and may drop elements still in flight, where the neutral interpreter, fs2 and ZIO deliver them before the failure; and fs2 / ZIO read an iterator (`fromIterator`, a `mapConcat` result) up to 64 elements ahead, one chunk, where the neutral interpreter and Akka read one element per demand - an iterator with side effects can be advanced past an early stop there; and on ZIO, a ZIO stream that ends with an interruption it raised itself (e.g. joining an interrupted fiber) stays an interruption (`recover` / `mapError` do not apply, `onTerminate` sees `Cancelled`), where the other backends see a failure. A pipe turned into an Akka `Flow` (`toFlow`) reads the flow's input once: a pipe that reads its input twice fails there.

**Services and downloads.** `WSClientWithEngineAsyncStreamingBase` is the backend-neutral counterpart of `WSClientWithEngineOutputStreamingBase` (same method names, so porting a service is a mixin swap): `execJsonStream` / `execRawStream` return `AsyncStream`s whose non-2xx failures go through the service's `handleErrorCodes`, and binary-safe downloads work on every streaming engine - `execGETBytes` / `execPOSTBytes` collect the body (at most `maxBytes`, 256 MiB by default), `execGETStream` / `execPOSTStream` complete once the body started - its first chunk arrived, or it ended (`None` for a 404) - with a single-run stream of the body. A service on the plain `WSClientWithEngineBase` gets the same downloads as the protected `execBytesDownload` / `execStreamDownload` (GET, POST, PUT, PATCH or DELETE; body parameters on POST / PUT / PATCH only) - on an engine without output streaming they buffer the response (a single-element stream). A collected streamed download is bounded per engine (the Play stream engines by the request timeout, `jdk` / `zio-http` only by the gaps between chunks): for a total bound, run the stream of `execStreamDownload` and cancel it on a timer. Buffered responses are binary-safe too: `Response.bytes` is the body exactly as received, on every engine (the text stays in `string`); `BytesBackedResponse` is the bytes-first `Response` for your own code. `DeferredPublisher` (a cold `Flow.Publisher` that calls its factory on subscription - single-shot: a second subscriber gets `onError(IllegalStateException)`; wrap it in `AsyncStream.fromPublisher(() => ...)` for a re-runnable stream) is public as well.

**More:** `AsyncBroadcastHub(upstream, bufferSize, drain)` - a hot fan-out whose `source(prefix)` registers a consumer per run; `preflight` - start a run now and fail early (e.g. on a 404) before handing the stream on; `AsyncStream.fromInputStream` / `fromBlockingIterator` - blocking sources read on a dedicated pool; `io.cequence.wsclient.concurrent.AsyncScheduler` / `Futures.after` / `delay` / `timeout` - timers without an actor system (the default is in implicit scope; `ActorSchedulers._` adapts an Akka / Pekko scheduler, `ZioInterop.scheduler` ZIO's clock); `PollingHelperCore` and `ParallelServiceAdapters.parallelTakeFirst` - Akka-free polling and parallel calls.

**Engine resolution.** `sttp`, `play-akka` and `play-pekko` have no output streaming: resolve streamed services through `WSClientEngineRegistry.outputStreamed`, which picks the highest-priority engine that streams - with `play-akka` and `jdk` on one classpath, streams then run on `jdk`.

**Request signing** (e.g. AWS SigV4): `engine.renderJsonBody(json)` is the exact JSON text an engine sends - every engine renders bodies the same way, as Play WS does (`WireFormat.jsonBody`) - and `engine.wireUrl(site, endPoint, endPointParam, params)` the exact URL, query parameters included. The Play and `jdk` engines send a query written into the endpoint string byte for byte; `sttp`, `pekko-http` and `zio-http` re-render URLs, which `wireUrl` reflects. With a `requestContextFun`, `wireUrl` and the request each evaluate it - sign and send with one fixed binding: `site.copy(requestContext = site.requestContextFn(), requestContextFun = None)`. `TransportSettings.httpVersion` selects HTTP/1.1 or HTTP/2 on the `jdk` engine.

## License ⚖️

This library is available and published as open source under the terms of the [MIT License](https://opensource.org/licenses/MIT).
