# WS Client (Cequence)
[![version](https://img.shields.io/badge/version-1.1.4-green.svg)](https://cequence.io) [![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](https://opensource.org/licenses/MIT) [![Twitter Follow](https://img.shields.io/twitter/follow/cequence_io?style=social)](https://twitter.com/0xbnd)

This repository contains a simple and efficient Web Service client implemented in Scala. The client is designed to interact with RESTful web services, making it easy to send requests, handle responses, and manage errors.

Pluggable HTTP engines - Play WS (Akka or Pekko), pekko-http, JDK HttpClient, sttp, and zio-http - picked up automatically from the classpath (see *Engine Self-Discovery* below).

**🔥 New in 1.1**: the `ws-client-zio-http` engine (zio-http / Netty + ZIO runtime), consistent streaming errors across engines, and structured HTTP-status failures that services can classify via `handleErrorCodes`.

As a part of this suite we also provide the [json-repair](./json-repair/README.md) library that can be used to fix common JSON syntax errors, repair malformed JSON objects and arrays.

## Installation 🚀

The currently supported Scala versions are **2.12, 2.13**, and **3** for the Akka-based modules, and **2.13** for the Pekko-based modules (Scala 3 support for Pekko is planned together with a Scala 3.3 LTS upgrade).

To install the library, add the following dependency to your *build.sbt*

```
"io.cequence" %% "ws-client-play-akka" % "1.1.4"     // Akka-based (Play WS 2.x)
```

or, for the Pekko flavor:

```
"io.cequence" %% "ws-client-play-pekko" % "1.1.4"    // Pekko-based (Play WS 3.x)
```

or to *pom.xml* (if you use maven)

```
<dependency>
    <groupId>io.cequence</groupId>
    <artifactId>ws-client-play-akka_2.12</artifactId>
    <version>1.1.4</version>
</dependency>
```

If you only need the core abstractions without Akka/Pekko dependencies:

```
"io.cequence" %% "ws-client-core" % "1.1.4"
```

> ⚠️ **The Akka and Pekko flavors are mutually exclusive on one classpath.** The Pekko modules are generated from the Akka ones and deliberately keep the same package and class names (the same trade-off Play made between 2.9 and 3.0), so downstream code compiles unchanged against either flavor - switch by swapping the artifact and your `Materializer` import. Never depend on both: which classes (implicits such as `asSafeSource` included) actually load then depends on jar order. `WSClientEngineRegistry` detects such duplicated classes at runtime and logs a warning naming the conflicting jars; set the config key `ws-client.strict-classpath = true` (or `-Dws-client.strict-classpath=true`) to fail engine resolution with an exception instead - recommended for CI.

> ℹ️ **Logging:** the library logs through the SLF4J API only and ships no logging backend - bring your own (e.g. logback). Keep `play.shaded.ahc.org.asynchttpclient.netty.handler` at `INFO` or above: at `DEBUG` Play's shaded AsyncHttpClient logs complete request headers, including `Authorization`.

> ℹ️ **1.1.4 - security hardening and json-repair fixes.** The Play engines (`play-akka`, `play-pekko` and their `-stream` variants) no longer keep a cookie jar. They ran on the shaded AsyncHttpClient's builder default - ONE cookie store for the whole engine, shared by every `SiteBinding` - so a `Set-Cookie` that one site or tenant received was sent on later requests of every other `SiteBinding` with a matching host name (any port; on the Akka flavor also a host that a response named in its `Domain` attribute). Play's own client configuration disables that store; ws-client builds the AHC configuration itself and kept it. Now no engine stores or replays cookies (the other engines never did): `Set-Cookie` headers still show up in `RichResponse.headers`, and a site that needs a cookie sends it itself - e.g. a `Cookie` header in its `WsRequestContext`. Behavior change only for code that relied on the Play engines' implicit cookie continuity (e.g. sticky-session cookies): pass the cookie explicitly. The Play engines also turn redirect following off explicitly: they relied on the shaded AsyncHttpClient's default, which `-Dplay.shaded.ahc.org.asynchttpclient.followRedirect=true` flips JVM-wide - and then re-sent bodies and custom secret headers such as `X-Api-Key` (on the Akka flavor `Authorization` and `Cookie` too: CVE-2026-40490, CVE-2026-45300) to whatever origin a redirect named. On the Akka flavor (`play-akka`, `play-akka-stream`: play-ws 2.1.11 / 2.2.0-M2 with a shaded AsyncHttpClient 2.10.5 / 2.12.3, which inflates gzip / deflate response bodies without any limit - CVE-2026-85721; a 510 KB response exhausted a 768 MB heap), a response whose decompressed body exceeds 256 MiB now fails with a `DecompressionException` - the Pekko flavor's AsyncHttpClient 2.16.1 limit, set by the same property, `play.shaded.ahc.org.asynchttpclient.maxDecompressedResponseSize`. The Akka flavor's shaded Netty (4.1.45 / 4.1.60) still has known CVEs that a client does not reach (HTTP/2, SPDY, SNI, CORS and server-side decoders); the Pekko flavor's (4.1.138) has none. Dependency updates that clear scanner findings with no reachable vulnerability: the Scala 2.12 artifacts move from Akka 2.6.1 to 2.6.20 (2.6.1 pulled protobuf-java 3.10.0), `play-akka-stream` to akka-http 10.5.3, and `zio-http` declares Bouncy Castle 1.86 directly (zio-http pulls 1.84 through netty-pkitesting). Multipart: a quote, CR or LF in a caller-supplied data-part name, file key or display file name is now encoded (`%22`, `%0D`, `%0A` - the browser / RFC 7578 encoding Play 2.8+ uses) on every engine, so it can no longer add parameters, part headers or a content prefix to its part (on `pekko-http` such a name dropped the part's `Content-Disposition`); other names go out byte-for-byte as before, except a data-part name that is not an HTTP token (e.g. `tags[]`), which the Play and in-memory bodies now quote like every other engine does. A file whose display name is only dots (e.g. `...`) no longer fails the request with a `NoSuchElementException` (the default content-type lookup by extension). `sttp`: a failed call's exception no longer carries the request URI - sttp's own messages included it in full, query-parameter API keys (`extraParams`) included; refused / reset connections and read failures now arrive as a `CequenceWSException` (with the transport exception as its cause) instead of the raw `SttpClientException` - a site `recoverErrors` matching sttp types needs to match the Cequence type or the cause. The `jdk` / `zio-http` SSE framing costs linear time per received chunk (a chunk of many tiny or blank frames was quadratic: a 1 MiB chunk took about 35 s of CPU). Two json-repair fixes, with repair results unchanged: input nested deeper than the calling thread's stack (a few KB of `[`) now fails with an ordinary `IllegalArgumentException` instead of a `StackOverflowError`, which escaped `Try` / `NonFatal` recovery and left a `Future` running the repair incomplete forever; and large arrays or many concatenated top-level values repair in linear time (200,000 elements: minutes before, well under a second now). Two json-repair crashes are fixed: `fromFile` threw a `StringIndexOutOfBoundsException` on a file with any multibyte (non-ASCII) character (and with a small `chunkLength` it could garble such characters instead) - the file is now decoded at once, in the platform charset as before, and repaired exactly like the same string (`chunkLength` no longer has an effect); and a top-level `null` after the first value (e.g. `[1] null` with `handleLiterals = true`) threw a `NullPointerException` - it is now kept as a value, like `true` / `false`. A json-repair repair no longer loops forever, allocating until the heap is exhausted, on an array value that consumes no input (e.g. `[{"a": {""}}, b"c": 1]`); trimming trailing whitespace is linear (a run of 200,000 spaces inside a broken string took over a minute); and escape sequences in strings on the repair path - which valid nested JSON takes too - are decoded the way a JSON parser decodes them. Before, `\n`, `\t`, `\r`, `\b` and `\\` kept their backslash next to the decoded character (`"x\ny"` came back as `x\`, a line break, `y`; a trailing `\n` as a stray backslash), `\uXXXX`, `\/` and `\f` were not decoded at all, and an escaped quote in a single-quoted string (`'it\'s'`) kept its backslash. Behavior change: repaired strings that contain escape sequences come out different - correct now - and, because some repair heuristics look at the decoded text (duplicate keys inside arrays, the `"true"` / `"null"` coercion, empty strings), so does the shape of some broken inputs' repairs. On Scala 3, `play-akka-stream` now streams incrementally: Play WS 2.2.0-M2, the last Play WS built for Scala 3.2, implements `stream()` as its buffered `execute()`, so an SSE / JSON stream delivered all of its events only once the response had ended, and cancelling the stream could not abort the request. The engine now takes the client's own streaming path, as Play WS 2.1.x and 3.x do. And json-repair numbers no longer change on the repair path, which valid nested JSON takes too: an exponent's `+` sign ended the number there (`2.50E+3` came back as `2.5`, the rest of it as debris), and every number became a double or a long, so digits beyond a double's precision were rounded (`0.255621758985604992` came back as `0.255621758985605`), a tiny number became `0`, a huge one (`1e400`) failed the repair with a `NumberFormatException`, and an integer beyond `Long` came back as a string. Numbers now repair the way Jackson / play-json parse them (to play-json's 34 significant digits). Behavior change for `repairJsonAsValue`: such a number is now a `BigDecimal` - a number a `Double` / `Long` holds exactly keeps that type.

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

Pekko flavor (Scala 2.13; sources generated from the Akka modules at build time):

- **ws-client-core-pekko** - Pekko-based streaming extensions
- **ws-client-play-pekko** - Play WS 3.x (`org.playframework`) backend implementation
- **ws-client-play-pekko-stream** - SSE/WebSocket streaming support (pekko-http)

Other backends:

- **ws-client-jdk** - backend on the JDK 11+ `java.net.http.HttpClient` (Scala 2.12, 2.13, 3); no Akka/Pekko/Play WS - it needs only `ws-client-core` (play-json, Jackson, Typesafe config, scala-logging)
- **ws-client-sttp** - backend over [sttp client4](https://sttp.softwaremill.com) (Scala 2.12, 2.13), unlocking any sttp `Future` backend (OkHttp, Armeria, Pekko-HTTP, ...)
- **ws-client-pekko-http** - direct pekko-http client backend (Scala 2.13), no Play WS / shaded AsyncHttpClient layer; supports input and output (SSE) streaming
- **ws-client-zio-http** - backend on the [zio-http](https://zio.dev/zio-http) (Netty) client and the ZIO runtime (Scala 2.12, 2.13); output (SSE) streaming via `Flow.Publisher`. Server certificates are verified against the JVM's default trust store (`ClientSSLConfig.FromSystemTrustStore`). A ZIO application can run it on its own `Client` via `ZioHttpWSClientEngine.layer()` - then configure that client's `.ssl(...)` yourself, as zio-http's default trusts any certificate. With a proxy, target host names are still resolved through local DNS

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

| Engine id | Module | Priority | Capabilities |
|---|---|---|---|
| `play-pekko-stream` | ws-client-play-pekko-stream | 21 | input + output streaming, multipart |
| `play-pekko` | ws-client-play-pekko | 20 | input streaming, multipart |
| `pekko-http` | ws-client-pekko-http | 15 | input + output streaming, multipart |
| `play-akka-stream` | ws-client-play-akka-stream | 11 | input + output streaming, multipart |
| `play-akka` | ws-client-play-akka | 10 | input streaming, multipart |
| `zio-http` | ws-client-zio-http | 7 | output streaming (`Flow.Publisher`), multipart |
| `sttp` | ws-client-sttp | 5 | multipart |
| `jdk` | ws-client-jdk | 0 | output streaming (`Flow.Publisher`), multipart (in-memory) |

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

Need different client-level settings (timeouts, proxy) for one consumer? **Copy the engine** - the copy always builds its OWN HTTP client from the (optionally overridden) settings and is closed independently:

```scala
val slow = engine.copy(TransportSettings(timeouts = Timeouts(requestTimeout = Some(600000))))
// ^ own client, SAME actor system/materializer/EC - close the env-owning original AFTER its copies
val island = engine.copy(reuseExecContext = false)
// ^ fully independent: its own owned daemon actor system (discovery-created engines only;
//   engines built on a caller-supplied environment throw - create those via their factory)
```

**Query-parameter encoding** differs between the engine families: the `jdk`, `sttp`, `pekko-http`, and `zio-http` engines expect **raw (unencoded) parameter values** and percent-encode them for you; the Play-based engines pass values through verbatim (long-standing behavior, kept for backward compatibility), so with those you must pre-encode values containing reserved characters yourself. Keep this in mind when swapping engines - a pre-encoded value like `a%20b` gets double-encoded on the non-Play engines.

**Cookies**: engines keep no cookie jar - a `Set-Cookie` response header is surfaced in `RichResponse.headers` but never stored or sent on a later request, so one site's (or tenant's) cookies can never reach another `SiteBinding`. A site that needs a cookie sends it itself, as a `Cookie` header in its `WsRequestContext` (or from `requestContextFun`).

**Proxy support**: `TransportSettings.proxyURL` (accepted forms: `host:port` or `scheme://host:port`; only the host and port are used - the proxy is always a plain HTTP proxy, even with an `https://` scheme (`https` targets are still tunnelled through it), and credentials, path or query in the URL are ignored: proxy authentication is not supported) is honored by the Play, `jdk`, `sttp`, and `zio-http` engines (`zio-http` tunnels via `CONNECT` and still resolves the target host through local DNS); the `pekko-http` engine logs a warning and ignores it (pekko-http only supports CONNECT-tunneling proxies).

**Custom error recovery** (`SiteBinding.recoverErrors`) composes with the engine's built-in transport-failure normalization: your partial function sees the Cequence exception taxonomy (`CequenceWSTimeoutException`, `CequenceWSUnknownHostException`, ...) for failures the backend recognizes, so the same recovery logic is portable across engines.

**Dynamic request context** (`SiteBinding.requestContextFun`): when set, the request context is re-evaluated on every request - e.g. to refresh an expiring auth token - on all engines. Client-level settings (`TransportSettings`: connect timeout, proxy; all timeouts on the Play engines) are captured once, at engine construction or first use.

Note for sbt-assembly users: ServiceLoader registrations live in `META-INF/services`, so fat jars need `MergeStrategy.concat` (or `filterDistinctLines`) for that path.

## License ⚖️

This library is available and published as open source under the terms of the [MIT License](https://opensource.org/licenses/MIT).
