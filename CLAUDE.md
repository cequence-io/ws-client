# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**WS Client** is a generic WebServices client library for Scala: one site-stateless engine contract with pluggable, classpath-discovered HTTP backends (Play WS on Akka or Pekko, pekko-http, JDK HttpClient, sttp, zio-http). The library enables type-safe HTTP requests with support for multiple Scala versions (2.12, 2.13, and 3).

The project includes:
- Core WS client interfaces and abstractions (Akka-free), including an engine-discovery SPI
- Akka-based streaming extensions (optional)
- Pekko-based mirror modules (source-generated from the Akka ones at build time)
- Play WS backend implementations (Play WS 2.x/Akka and Play WS 3.x/Pekko)
- Additional backends: pekko-http, JDK HttpClient, sttp, zio-http (ZIO runtime)
- Streaming support for large payloads
- A backend-neutral stream type (`AsyncStream`, since 1.2.0) with native Akka / Pekko / fs2 / ZIO interop
- JSON repair utility for fixing malformed JSON from LLMs

Current version: 1.1.5

## Build Commands

The project uses **SBT** as the build tool.

### Core Commands
- `sbt compile` - Compile all modules
- `sbt test` - Run all tests
- `sbt +compile` - Cross-compile for all Scala versions (2.12, 2.13, 3.2)
- `sbt +test` - Cross-test for all Scala versions

### Module-Specific Commands
- `sbt ws-client-core/compile` - Compile core module only
- `sbt ws-client-core-akka/compile` - Compile Akka extensions
- `sbt ws-client-play-akka/compile` - Compile Play WS implementation
- `sbt ws-client-play-akka-stream/compile` - Compile streaming support
- `sbt ws-client-core-pekko/compile` - Compile Pekko extensions (generated)
- `sbt ws-client-play-pekko/compile` - Compile Play WS 3.x / Pekko implementation (generated)
- `sbt ws-client-play-pekko-stream/compile` - Compile Pekko streaming support (generated)
- `sbt json-repair/compile` - Compile JSON repair module
- `sbt json-repair/test` - Run JSON repair tests

### Code Quality
- `sbt scalafmt` - Format code with scalafmt
- `sbt scalafmtCheck` - Check code formatting
- `sbt scalafix` - Run scalafix
- `sbt coverage test coverageReport` - Generate test coverage report

### Publishing
- `sbt publishLocal` - Publish to local ivy repository
- `sbt publishSigned` - Publish signed artifacts to Sonatype

## Architecture

### Module Structure

The project is organized into 15 SBT modules (11 handwritten + 3 Pekko mirrors + a test-only testkit):

1. **ws-client-core** - Core abstractions and interfaces (Akka-free)
   - No concrete HTTP implementation, no Akka dependency
   - Defines `WSClient`, `WSClientEngine`, and `WSClientBase` traits
   - Contains domain models (`Response`, `RichResponse`, `WsRequestContext`, `CequenceWSException`)
   - Provides service adapters (logging, retries, round-robin)
   - The backend-neutral stream layer (since 1.2.0, package `io.cequence.wsclient.stream`):
     `AsyncStream`, `AsyncPipe`, `Bytes`, `AsyncBroadcastHub`, `ByteFraming`, `JsonStreamPipes`,
     `StreamDiagnostics`; internals in `stream/impl` (the AST `Nodes`, the `StreamCompiler`
     visitor, the neutral pull interpreter `NeutralCompiler` / `NeutralRunner`, the RS bridges
     `CursorPublisher` / `SubscriberCursor`); timers in `io.cequence.wsclient.concurrent`
     (`AsyncScheduler` - default in implicit scope - and `Futures`). The service base
     `WSClientWithEngineAsyncStreamingBase` (streams + binary downloads - its public
     `execGETBytes` / `execGETStream` ... delegate to the protected `execBytesDownload` /
     `execStreamDownload` of `WSClientWithEngineBase`, which buffer on non-streaming engines),
     the public `DeferredPublisher`, `PollingHelperCore`,
     `ParallelServiceAdapters`, `WireFormat`

2. **ws-client-core-akka** - Akka-based streaming extensions
   - Depends on `ws-client-core` + Akka Streams
   - Adds `StreamedResponse` (extends `Response` with `source: Source[ByteString, _]`)
   - Adds `WSClientInputStreamExtraAkka` (Pekko twin: `WSClientInputStreamExtraPekko`) - mixin for input stream POST methods (`execPOSTSource*`)
   - Adds `WSClientOutputStreamExtraAkka` (Pekko twin: `WSClientOutputStreamExtraPekko`) - trait for SSE/JSON streaming (`execJsonStream`, `execRawStream`)
   - Provides `StreamResponseImplicits` for `asSafeSource` on `Response`/`RichResponse`
   - Contains `PollingHelper`, `ParallelTakeFirstAdapter`, `ServiceBaseAdaptersAkka`
   - The Akka interop of `AsyncStream` (since 1.2.0, package `io.cequence.wsclient.stream`):
     `SourceCompiler` (every node natively; the three custom stages in `stream/stages`),
     `SourceLeaf` / `FlowPipe`, `SourceInterop`, the opt-in `SourceConversions`, and
     `concurrent.ActorSchedulers`. New file and class names here must not contain "Akka" (the
     generator renames paths); public names are flavor-neutral, so downstream code compiles
     against either flavor

3. **ws-client-play-akka** - Play WS backend implementation (named `ws-client-play` before 1.0.0)
   - Depends on `ws-client-core-akka`
   - Implements `PlayWSClientEngine` using Play WS standalone client
   - Handles HTTP methods: GET, POST, DELETE, PATCH, PUT
   - Supports multiple content types: JSON, multipart/form-data, URL-encoded, file uploads, streaming
   - Default timeouts (for unset `Timeouts` fields): 60 s request/read, 5 s connect, 60 s pooled-connection-idle
     (the jdk/sttp engines default the request timeout to 120 s)
   - TLS (since 1.1.2): offers the JDK's default CLIENT protocols (`TLSv1.3, TLSv1.2` on JDK
     11+ - the list `java.net.http` / the jdk and sttp engines use; honors
     `jdk.tls.client.protocols` / `jdk.tls.disabledAlgorithms`, never names a protocol the JSSE
     lacks) in place of the shaded AHC's shipped default `TLSv1.2/1.1/1` (the JDK disables 1.1/1,
     so TLS-1.3-only hosts failed the handshake). Exactly that shipped default is replaced
     (`PlayWSClientEngine.tlsProtocols`); an explicitly configured AHC list stays in force -
     `-Dplay.shaded.ahc.org.asynchttpclient.enabledProtocols=...` set to anything, or that key
     in `play/shaded/ahc/org/asynchttpclient/config/ahc.properties` (the shaded AHC does NOT read
     a classpath-root `ahc.properties`). The JDK list comes from a throwaway `SSLContext`, never
     `SSLContext.getDefault` (that would initialize and freeze the JVM-wide default context);
     if the JDK yields none, AHC's own list stays. Java 8: the Scala 2.12/2.13 Akka jars (shaded
     Netty 4.1.45) have no TLS 1.3 cipher suites there, so they negotiate TLS 1.2 - TLS 1.3
     needs JDK 11+. Covered end-to-end by the Play provider specs (`TestServers.withTlsServer`,
     `withTrustingEngine`); `ws-client-play-akka` / `-pekko` / `-zio-http` fork their tests
     because those point the JVM-global default trust store at a test cert
   - Redirects are off explicitly (`.setFollowRedirect(false)`): the AHC builder's default comes
     from the JVM-wide property `play.shaded.ahc.org.asynchttpclient.followRedirect`. Response
     decompression: AHC < 2.16.1 (play-ws 2.1.11 / 2.2.0-M2 - the Akka flavor) inflates gzip /
     deflate bodies without any limit (CVE-2026-85721), so `PlayWSClientEngine.boundedInflater`
     replaces AHC's `inflater` handler with AHC 2.16.1's own design (`maxDecompressedResponseSize`
     property, default 256 MiB, 0 or less = no limit - keep AHC's `> 0` guard;
     `DecompressionException`, rest of the body dropped; counted per response, so a long
     compressed stream fails too). It is installed only when the shaded AHC lacks the limit (no
     `getMaxDecompressedResponseSize` on `AsyncHttpClientConfig`), so the Pekko flavor keeps
     AHC's own. The provider specs cover both (redirect with the property set; a gzip bomb under
     a lowered limit, and with 0 / -1)
   - Known CVEs (reachability checked 2026-10-24): the Akka flavor's shaded Netty 4.1.45 / 4.1.60
     and AHC 2.10.5 / 2.12.3 carry dozens; reachable were only the cookie store (now off),
     redirects (only with the property) and decompression - all three now closed in code.
     The rest (HTTP/2, SPDY, SNI, CORS, server decoders, Realm / NTLM, WebSocket) is unused by
     the engine. play-ws 2.2.18 (still Akka 2.6.21; AHC 2.16.1, Netty 4.1.138) is the Scala 2.13
     upgrade path; 2.12 has none (2.1.11 is the last), Scala 3 needs 3.3+

   - Scala 3 trap: with `play.api.libs.ws.JsonBodyReadables._` in scope, a bare
     `rawResponse.body` with no expected type resolves to `body[JsValue]` on Scala 3 (to the
     `String` one on Scala 2) - give it an expected type (`PlayWsResponse.string`)

4. **ws-client-play-akka-stream** - Streaming extensions (named `ws-client-play-stream` before 1.0.0)
   - Depends on `ws-client-core-akka` and `ws-client-play-akka`
   - Adds `PlayWSStreamClientEngine` for Server-Sent Events (SSE)
   - Uses Akka HTTP for JSON streaming over WebSockets
   - Streaming frame limit: 20,000 bytes (defaultMaxFrameLength)
   - Scala 3: Play WS 2.2.0-M2 implements `stream()` as the buffered `execute()` (the whole body
     as one element once the response ended; cancelling cannot abort the request), so
     `PlayWSStreaming` (in `src/main/scala-3`) calls the client's package-private streaming path
     instead, through `play.api.libs.ws.ahc.StreamedExecution` - what `stream()` does in Play WS
     2.1.x / 3.x (minus request filters, which the engine never sets). The `scala-2` twin calls
     `stream()`. Covered by the stream spec (first event before the body ends; the cancelled
     stream aborts the request)

5. **json-repair** - JSON repair utility
   - Independent module for fixing malformed JSON
   - Port of Python json_repair library
   - Main API: `JsonRepair.repair(String)`, `JsonRepair.loads(String)`, `JsonRepair.fromFile(File)`
   - 140+ tests ported from original Python library

6. **ws-client-jdk / ws-client-sttp / ws-client-pekko-http / ws-client-zio-http** - additional backends
   - `ws-client-jdk`: Akka/Pekko/Play-WS-free engine on the JDK 11+ `java.net.http.HttpClient`
     (depends only on ws-client-core - i.e. play-json, Jackson, Typesafe config, scala-logging;
     Scala 2.12/2.13/3.2). Multipart is materialized in memory
     via `MultipartBodyBuilder` (core); output (SSE) streaming via the backend-agnostic
     `WSClientOutputStreamCore` (`Flow.Publisher`) contract; no input streaming.
   - `ws-client-sttp`: engine over sttp client4 `core` (Scala 2.12/2.13; sttp4's Scala 3 needs
     3.3+). The provider uses sttp's JDK-based `HttpClientFutureBackend`; a custom sttp backend
     can be passed to `SttpWSClientEngine.apply`. The engine owns and closes its backend.
   - `ws-client-pekko-http`: direct pekko-http client engine (depends on ws-client-core-pekko;
     Scala 2.13 / 3.3). Supports input streaming (`execPOSTSource`) and native output (SSE) streaming.
     No Play WS / shaded AHC.
   - `ws-client-zio-http`: engine on the zio-http (Netty) client + ZIO runtime (depends on
     ws-client-core and, since 1.2.0, ws-client-zio; Scala 2.12/2.13 - zio-http's Scala 3
     artifacts need 3.3+). Output (SSE) streaming via `WSClientOutputStreamCore` (publisher path:
     ZStream bridged with zio-interop-reactivestreams + `FlowAdapters`, then the jdk engine's
     framing pipeline; `execJsonAsyncStream` / `execRawAsyncStream` are native ZIO leaves over the
     same body stream - `bodyChunks`); the stream status is checked before
     any byte is exposed. Discovery-created engines own a Netty driver + client (`close()`
     releases both); `copy(reuseExecContext = true)` shares the driver. `ZioHttpWSClientEngine(client)`
     / `.layer()` run on a caller-owned `Client` (never closed). Owned engines run Netty on
     DAEMON threads: zio-http has no public hook for that, so `ZioHttpWSClientEngine.DaemonDriver`
     reaches the package-private `NettyClientDriver` / `NettyRuntime` constructors REFLECTIVELY
     (a zio-http upgrade cannot break compilation) and falls back to the stock non-daemon driver
     with a warning if the lookup fails - re-check it on zio-http upgrades (the spec asserts the
     daemon driver is in use). With a proxy, zio-http still resolves the TARGET host via local
     DNS. TLS (since 1.1.2): `clientConfig` sets `ClientSSLConfig.FromSystemTrustStore` - never
     drop it: zio-http's own default (`ClientSSLConfig.Default`) is Netty's INSECURE trust
     manager (no certificate chain, no host name verification). Caller-owned clients
     (`apply(client)` / `layer()`) bring their own `.ssl`. The spec covers both (untrusted cert
     rejected; trusted cert negotiates TLS 1.3 / 1.2).
   - All four use `StringBackedResponse` / `SimpleRichResponse` from core (no streaming source
     on responses) and the shared `EngineSupport` scaffolding (core, `private[wsclient]`).

7. **ws-client-core-pekko / ws-client-play-pekko / ws-client-play-pekko-stream** - Pekko mirrors
   - Sources are GENERATED at build time by `project/PekkoGenerator.scala` from the corresponding
     Akka modules (`akka.*` → `org.apache.pekko.*` rewrite). NEVER edit files under
     `target/**/src_managed` - edit the Akka module sources instead; the Pekko flavor follows.
   - Handwritten exceptions: `PlayPekkoWSClientEngineProvider.scala`,
     `PlayPekkoStreamWSClientEngineProvider.scala` and their `META-INF/services` resources (the
     generator skips `*EngineProvider.scala` files because engine ids/priorities differ), plus the
     test specs of the play-pekko / play-pekko-stream modules - the generator mirrors Compile
     sources only there, so those Pekko modules have their own handwritten specs.
   - `ws-client-play-pekko` uses `org.playframework` play-ws 3.x (its play-json 3.x dependency is
     excluded - core's `com.typesafe.play` play-json is used instead);
     `ws-client-play-pekko-stream` uses pekko-http.
   - Cross-built for Scala 2.13 and 3.3 (since 1.2.0; play-ws 3.x and Pekko need 3.3+). The
     generator mirrors `src/<config>/scala` plus the `scala-2` variants, whatever Scala version
     the Akka module is at: Play WS 3.x behaves like Play WS 2.1.x on every Scala version (the
     Akka module's `scala-3` sources work around Play WS 2.2.0-M2). `ws-client-core-pekko` also
     runs core-akka's specs, generated (`PekkoGenerator.testGeneratorSettings`)
   - IMPORTANT: generated Pekko classes share fully qualified names with the Akka ones by design,
     so the Akka and Pekko flavors are mutually exclusive on one classpath (downstream code compiles
     unchanged against either). Avoid `akka.`-package references in strings/comments in the Akka
     modules - the generator rewrites `akka.` package references everywhere and fails the build if
     any survive.

8. **ws-client-testkit** - test-only fixtures (never published): embedded echo / SSE / proxy-stub
   / protocol-restricted TLS servers and a tunnelling CONNECT proxy (`TestServers`), the shared
   engine scenarios (`cookieIsolation`, `multipartInjection`, `queryEncoding`,
   `proxyConnectReply`, `connectTunnel`, `stalledBody`, `asyncStreamContract`), and a generic
   `LatchedSubscriber` for `Flow` publishers; engine modules depend on it `% Test`. Core's test
   classpath is NOT shared via `test->test` - its dummy engine providers would leak into every
   engine's discovery tests.

9. **ws-client-fs2 / ws-client-zio** - `AsyncStream` interop (since 1.2.0; Scala 2.12 / 2.13 /
   3.3): `Fs2Compiler` / `Fs2Interop` / `Fs2Conversions` (fs2 3.12, cats-effect 3.6; `Fs2Runner`
   for the fallback) and `ZioCompiler` / `ZioInterop` / `ZioConversions` (zio-streams 2.1.26 -
   the zio-http engine's ZIO; hand-written pull bridges over the neutral cursors, no
   zio-interop-reactivestreams)

### Key Architectural Patterns

#### Trait-Based Abstraction
The library uses a layered trait hierarchy:
```
WSClientBase (basic request handling)
  ↓
WSClient (HTTP method definitions with PEP/PT type parameters)
  ↓
WSClientEngine (site-stateless engine contract: transportSettings + copy; every call takes a SiteBinding)
  ↓
PlayWSClientEngine (concrete Play WS implementation, mixes in WSClientInputStreamExtraAkka)
```

Streaming is added via optional mixin traits in `ws-client-core-akka`:
- `WSClientInputStreamExtraAkka` (generated Pekko twin: `WSClientInputStreamExtraPekko`) - adds `execPOSTSource`/`execPOSTSourceRich` to `WSClient`
- `WSClientOutputStreamExtraAkka` (generated Pekko twin: `WSClientOutputStreamExtraPekko`) - adds `execJsonStream`/`execRawStream`; extends the backend-agnostic `WSClientOutputStreamCore` (core), whose `Flow.Publisher`-typed `execJsonStreamPublisher`/`execRawStreamPublisher` every streaming engine also implements
- `WSClientWithEngineInputStreamingBase` - delegates input stream methods to engine
- `WSClientWithEngineOutputStreamingBase` - service-level `execJsonStream`/`execRawStream`
  delegating to the engine, with non-2xx stream failures classified through the service's
  `handleErrorCodes` (`mapHttpStatusErrors`)

Type parameters `PEP` (endpoint) and `PT` (parameter type) allow subclasses to define their own endpoint and parameter types (commonly enums).

#### Engine Self-Discovery (ServiceLoader SPI)
Backends register a `WSClientEngineProvider` (in `io.cequence.wsclient.service.spi`, ws-client-core)
via `java.util.ServiceLoader` (`META-INF/services` resource per backend module). `WSClientEngineRegistry`
resolves a provider in this order: explicit engine id → config key `ws-client.engine` (system property
`-Dws-client.engine=...` takes precedence per Typesafe Config semantics) → auto-selection (single
provider, else highest `priority`; ties and an empty classpath throw `CequenceWSException`).

```scala
import io.cequence.wsclient.service.spi.{TransportSettings, WSClientEngineRegistry}

val engine = WSClientEngineRegistry() // site-stateless: the target site rides on each call as a SiteBinding
```

Engine ids (by auto-selection priority): `play-pekko-stream` (21), `play-pekko` (20),
`pekko-http` (15), `play-akka-stream` (11), `play-akka` (10), `zio-http` (7), `sttp` (5),
`jdk` (0). Engines that stream only through the Flow-typed `WSClientOutputStreamCore` (zio-http,
jdk) rank below every akka/pekko engine: the flavored `StreamedEngineRegistry` casts to the
Source-typed traits and must never auto-select them over a Source-typed engine. The stream
engines are strict supersets of their base engines. Discovered engines own their execution
environment - actor system or sttp backend (`close()` releases it, idempotent). Callers who want
to supply their own `Materializer`/`ExecutionContext`/backend should keep using the explicit
factories (`PlayWSClientEngine.apply`, `PlayWSStreamClientEngine.apply`, `JdkWSClientEngine.apply`,
`SttpWSClientEngine.apply`, `PekkoHttpWSClientEngine.apply`, `ZioHttpWSClientEngine.apply` /
`.layer`).

For typed streaming engines use `StreamedEngineRegistry` (ws-client-core-akka / -pekko, package
`io.cequence.wsclient.service.spi`): `outputStreamed(settings, engineId)` returns
`WSClientEngine with WSClientOutputStreamExtraAkka` (Pekko: `...ExtraPekko`; SSE via `execJsonStream`), `inputStreamed(...)`
returns `WSClientEngine with WSClientInputStreamExtraAkka` (`execPOSTSource`). The Akka-free core additionally offers `WSClientEngineRegistry.outputStreamed`, returning the backend-agnostic `WSClientEngine with WSClientOutputStreamCore` (`Flow.Publisher`-typed - works with Core-only engines like `jdk`, no akka/pekko needed). Both filter providers
by `EngineCapability.OutputStreaming`/`InputStreaming` using the same resolution order as
`WSClientEngineRegistry` (which also exposes the capability-filtered `provider(engineId,
requiredCapabilities, classLoader)` overload). `pekko-http` implements output streaming natively;
`ws-client-play-akka-stream`/`-pekko-stream` register the stream engines as providers.

**Owned-system lifecycle:** every discovery-created akka/pekko-based engine eagerly creates a dedicated ActorSystem whose threads are DAEMON (`akka/pekko.daemonic = on` override, with the application's config as fallback) - a leaked engine cannot block JVM exit, but `engine.close()` remains the correct way to release it (terminates the client AND the system). To share a caller-owned ActorSystem across engines instead, use the direct constructors (`PlayWSClientEngine(...)`, `PlayWSStreamClientEngine(...)`, `PekkoHttpWSClientEngine(...)`) - engines built that way never touch your system on close().

#### Service Adapters
The library provides composable service adapters in `io.cequence.wsclient.service.adapter`:
- `LogServiceAdapter` - Request/response logging
- `RoundRobinAdapter` - Load balancing across multiple services
- `RandomOrderAdapter` - Random service selection
- `ParallelTakeFirstAdapter` - Parallel execution, returns first response (in `ws-client-core-akka`)
- `PreServiceAdapter` - Execute action before each request
- `RepackExceptionsAdapter` - Transform exceptions

Use `ServiceBaseAdapters` for core adapters, mix in `ServiceBaseAdaptersAkka` for `parallelTakeFirst`.

#### Rich Response Pattern
All HTTP methods have two variants:
- `execGET()` - Returns `Future[Response]`, throws on error
- `execGETRich()` - Returns `Future[RichResponse]`, includes status/headers even on failure

This allows callers to handle non-2xx responses gracefully without exceptions.

#### Response Types
- `Response` - base trait with `json`, `string` and (since 1.2.0) `bytes` (in `ws-client-core`).
  `BytesBackedResponse` is a plain final class (companion `apply`), not a case class - public
  1.2.0 types that may grow must not be case classes (MiMa freezes apply / copy / unapply).
  `TextAndBytesResponse` extends `StringBackedResponse`: every buffered non-Play body is still
  a StringBackedResponse, as in 1.1.x.
  `bytes` is the body exactly as received on every engine; its trait default (UTF-8 of `string`)
  is only for foreign implementations. The non-Play engines build buffered bodies through core
  `ResponseBodies`: decoded once; a UTF-8 body without U+FFFD keeps only the text
  (`StringBackedResponse` - re-encoding reproduces the bytes exactly), any other body keeps the
  raw bytes too (`TextAndBytesResponse`) - never hold every body twice. `BytesBackedResponse`
  (public) is the bytes-first `Response` for callers
- `StreamedResponse extends Response` - adds `source: Source[ByteString, _]` (in `ws-client-core-akka`)
- `PlayWsResponse extends StreamedResponse` - concrete implementation backed by Play WS

#### Request Context
`WsRequestContext` carries per-request data only:
- Authentication headers (`authHeaders`)
- Extra query parameters (`extraParams`)

Client-level settings (timeouts, proxy) live in `TransportSettings` instead. Use
`SiteBinding.requestContextFun` to re-evaluate the context on every request (e.g. token refresh).

### Cross-Version Compatibility

The build manages different dependency versions for Scala 2.12, 2.13, and 3.2:
- Play JSON: 2.8.2 (Scala 2.12/2.13), 2.10.0-RC6 (Scala 3.2)
- Akka Stream: 2.6.20 (not 2.6.1 on Scala 2.12: its akka-protobuf-v3 POM pulls protobuf-java
  3.10.0); Akka HTTP 10.5.3 (ws-client-play-akka-stream only; BUSL-1.1 licensed)
- Play WS (Akka): 2.1.11 (Scala 2.12/2.13), 2.2.0-M2 (Scala 3.2)
- Play WS (Pekko, `org.playframework`): 3.0.14 (shaded AHC 2.16.1 / Netty 4.1.138 - stricter HTTP response parsing than 3.0.7's 4.1.118: bare-LF lines, CL + TE, control chars in headers are rejected); Pekko Stream: 1.6.0; Pekko HTTP: 1.3.0 (Scala 2.13 / 3.3)

Scala 3 requires manual cross-version suffix handling (e.g., `akka-stream_2.13` for Scala 3).

Cross-building is per-project: the Pekko modules (and `ws-client-pekko-http`) declare
`crossScalaVersions := pekkoScalaVersions` (2.13 and 3.3), fs2 / zio 2.12 / 2.13 / 3.3,
`ws-client-sttp` and `ws-client-zio-http` 2.12 / 2.13; the rest build for 2.12 / 2.13 / 3.2.
Version-dependent dependency settings must be `Def.setting`s reading the project-level
`scalaVersion` (NOT `inThisBuild` settingKeys) - with heterogeneous crossScalaVersions, `+` switches
Scala per project and an `inThisBuild` lookup sees the stale default version.

Two Scala 3 lines (since 1.2.0): the `_3` artifacts of core, core-akka, json-repair, jdk and
play-akka(-stream) are compiled with 3.2.2 (TASTy 28.2 - they serve 3.2 and 3.3+ users); the
Pekko modules, fs2 and zio with 3.3.8 (`scala33`). An sbt project dependency needs one Scala line
on both sides, so core and the testkit ALSO build with 3.3.8 - in-build only
(`publishedExceptOnScala33`: never published, or `ws-client-core_3` would be published twice).
A plain `+publishSigned` does the right thing. Guards (`project/ReleaseChecks.scala`):
`tastyCheck` fails a publishable Scala 3 jar of the wrong line; `tastyCheckStaged [dir]` checks
every `_3` jar of a staged release (default `target/sonatype-staging/<version>`) - run it before
`sonatypeBundleRelease`. MiMa's previous artifacts are the exact 1.1.5 module x Scala matrix.

## Testing

- Test files are located in `*/src/test/scala/`
- Primary test suite: `json-repair/src/test/scala/io/cequence/jsonrepair/JsonRepairSpec.scala`
- Engine discovery tests: `WSClientEngineRegistrySpec` (ws-client-core, uses dummy providers
  registered in test resources) and `Play{Akka,Pekko}WSClientEngineProviderSpec` (real GET against
  an embedded `com.sun.net.httpserver.HttpServer`, close idempotency)
- Run `sbt test` for all tests or `sbt <module>/test` for specific module
- Note: `sbt ws-client-play-akka/test` also runs core and json-repair tests (aggregation)
- Test logging: logback is a `% Test` dependency of every module with tests (`testLoggingSettings`
  in build.sbt), configured by the shared `test-resources/logback-test.xml`
- CI: `.github/workflows/ci.yml` runs `sbt scalafmtCheckAll scalafmtSbtCheck`, `sbt +test`,
  `sbt +mimaReportBinaryIssues` (against 1.1.5, see `project/ReleaseChecks.scala`) and
  `sbt +tastyCheck`
- The forked test JVMs of core, core-akka and core-pekko run in `target/`: TestNG (the Reactive
  Streams TCK suites) writes report files into the working directory

## Key Dependencies

- Play JSON - JSON serialization/deserialization
- Play WS Standalone - HTTP client
- Akka Streams - Reactive stream processing (in `ws-client-core-akka` and downstream)
- Akka HTTP - WebSocket and SSE support (streaming module)
- scala-logging - Logging via the SLF4J API only. No logging BACKEND is published (logback is
  test-scope): a backend would hijack the host's logging, and logback's no-config default is
  DEBUG, under which Play's shaded AHC logs complete request headers incl. `Authorization`
- Jackson - declared directly in core and json-repair (overriding the 2.11.4 that play-json pins)
  so consumers resolve a patched line; its `StreamReadConstraints` bound nesting depth and
  string/number length on every parse
- Bouncy Castle (ws-client-zio-http) - declared directly at 1.86 like Jackson: zio-http's
  compile dependency netty-pkitesting pulls 1.84 (CVE-2026-8763, CVE-2026-13506 - the client
  never uses it); bcprov / bcpkix / bcutil must share one version
- ScalaTest - Testing framework (test scope)

## Common Development Patterns

### Creating a WS Client
```scala
import io.cequence.wsclient.domain.{SiteBinding, WsRequestContext}
import io.cequence.wsclient.service.spi.TransportSettings
import io.cequence.wsclient.service.ws.PlayWSClientEngine
import akka.stream.Materializer
import scala.concurrent.ExecutionContext

implicit val materializer: Materializer = ???
implicit val ec: ExecutionContext = ???

// site-stateless engine on a caller-owned environment (or use WSClientEngineRegistry() for discovery)
val engine = PlayWSClientEngine(TransportSettings())

// the site rides on every call
val site = SiteBinding("https://api.example.com", WsRequestContext())
```

### Making Requests
Engine-level calls are `*Rich` and take the `SiteBinding` first:

```scala
// Simple GET
engine.execGETRich(site, endPoint = "users", params = Seq("id" -> Some(123)))

// POST with JSON body
engine.execPOSTRich(
  site,
  endPoint = "users",
  bodyParams = Seq("name" -> Some(Json.toJson("John")))
)

// Rich response with error handling
engine.execGETRich(
  site,
  endPoint = "users",
  acceptableStatusCodes = Seq(200, 404)
).map { response =>
  response.response match {
    case Some(resp) => // Success
    case None => // Handle non-acceptable status
  }
}
```

Services extending `WSClientWithEngineBase` hold their `SiteBinding` once and expose the
site-less, endpoint-first variants (`execGET`, `execGETRich`, ...) shown in the Rich Response
Pattern section.

### Using Service Adapters
Service adapters are applied via the `ServiceBaseAdapters` trait pattern. Extend your service with this trait and compose adapters:

```scala
// Round-robin across multiple backends
val service = roundRobin(service1, service2, service3)

// Add logging
val loggedService = log(service, "MyService")
```

## Scala Style - Pragmatic Principles

Readable, pragmatic Scala: plain OO (traits, classes, encapsulation) where it models the problem,
plain FP (immutable data, pure functions, `Option` / `Either` / `Try`, collection combinators) where
it reads well. Every abstraction must pay for itself - no monoid / type-class towers, no
tagless-final `F[_]`, no transformer stacks, no symbolic operators. Project rules above and below
win where they are more specific.

**Design**
- Least power: a plain method or function, then a case class, a sealed trait, a class, a trait
  hierarchy - an implicit or type class last. Generalize only for a second real use.
- Decompose by the axis of change: a closed set of alternatives is a sealed trait matched
  exhaustively (no `case _` on our own sealed types); an open set of implementations (engines,
  providers, adapters) is a trait with dynamic dispatch / SPI discovery.
- Small single-concern traits, concrete classes at the leaves, composition and constructor
  parameters over inheritance and cake wiring; abstract members are `def`s.
- Immutable data, pure decision logic (parsing, framing, encoding, error classification); I/O,
  clocks, logging and mutation at the edges. Mutable state is private and its owning thread or
  lock is stated. While-loops / arrays / `null` only on inherently hot paths (framers, cursors),
  encapsulated and commented.
- Data: `final case class` for plain data; a plain final class with a companion `apply` for
  public types that may grow (MiMa); `Option`, never null fields; covariant producer types.
- Reuse before writing: core helpers first (`EngineSupport`, `WireFormat`, `MultipartParams`,
  `JsonStreamFrames`, ...); logic a second engine needs moves to core as `private[wsclient]`.

**Library API** - everything public is a MiMa obligation after a release
- Smallest surface: helpers `private` / `private[wsclient]`; explicit result types on public /
  protected members and implicits; public parameter names are API.
- Never change a published signature in place: add an overload / forwarder (TransportSettings
  shows the shim for a case-class field); MiMa against the previous release decides.
- Scaladoc opens with a one-sentence summary; extension points state implementer obligations
  (thread safety, ownership / `close()`, call-once). Comments say why, not what.

**Errors and concurrency**
- `Option` for absence, `Either` / a small sealed result for outcomes callers branch on, a failed
  `Future` with a `CequenceWSException` subclass for transport / HTTP failures; no sentinels.
- A `Future`-returning method never throws synchronously (`Future.failed`, or build the request
  inside the future).
- Catch `NonFatal(e)` or specific types - `case e: Throwable` only to rethrow unchanged.
  `Option(javaValue)`, never `Some(javaValue)`; no unguarded `.get` / `.head` / `.reduce`.
- Take an implicit `ExecutionContext` (never `global` in library code); never block in a future or
  callback (`blocking {}` or a dedicated pool); `Await` only in tests. A `Promise` only to bridge
  callback / Reactive Streams APIs. Deadlines with `System.nanoTime`.
- One synchronization strategy per piece of state; never call foreign code (user functions,
  subscribers, subscriptions) while holding a lock.
- Implicits only for ambient context (`ExecutionContext`, `Materializer`, `AsyncScheduler`);
  domain values (`SiteBinding`, `TransportSettings`) stay explicit; implicit conversions only in
  opt-in objects imported by name (`SourceConversions`).

**Code shape**
- Expressions over statements; a combinator chain of more than about 3 steps gets a named
  intermediate; `for` for nested flatMaps; a plain `match` / local loop over a clever fold.
- No `return` inside a lambda or callback; `@tailrec` on intended tail recursion.
- Shared sources stay Scala-2 syntax Scala 3 accepts (no `given` / `using` / `enum` /
  `extension`); version-specific code lives in `scala-2` / `scala-3` dirs.
- Naming: no `get` prefix; `()` on side-effecting zero-arg methods; words, not symbols; public
  names must not clash with Akka / Pekko / ZIO / fs2 names a user imports next to ours.
- Tests: intercept the most specific exception; synchronize with latches / bounded waits (a sleep
  only to open a race window); behavior every engine shares is one testkit scenario.

Distilled (2026-11) from the Scala Style Guide, the Scala 3 Book, "Binary Compatibility for
Library Authors", Effective Scala, the Databricks Scala Style Guide, Li Haoyi's "Strategic Scala
Style" and Odersky's "Lean Scala".

## Important Notes

- The library uses Play JSON (`JsValue`) as the JSON representation
- All async operations return `Future[T]`
- `ws-client-core` has zero Akka dependencies; all Akka types are in `ws-client-core-akka`
- Engines are SITE-STATELESS: `TransportSettings(timeouts, proxyURL)` is client-level (per
  engine); everything site-specific rides in the per-call `SiteBinding(coreUrl,
  requestContext, recoverErrors, requestContextFun, label)`. `WsRequestContext` carries only
  per-request data (authHeaders, extraParams). Timeouts are deliberately NOT overridable per
  site - engines stay fully stateless (no per-config client caches); a service needing
  different timeouts uses its own engine - cheaply, via `engine.copy(transportSettings,
  reuseExecContext = true)`: the copy builds its own HTTP client but reuses the original's
  actor system/materializer/EC (close the env-owning original after its copies);
  `reuseExecContext = false` spins up an owned daemon system (discovery-created engines only). Engines resolve `Timeouts` per field (missing
  fields fall back to defaults - a partially-specified `Timeouts` never disables the request
  timeout)
- Query-param encoding contract: every engine takes raw keys and values (`params` and
  `WsRequestContext.extraParams`) and percent-encodes them - the Play engines since 1.1.5
  (`PlayWSClientEngine.encodeQueryComponent`, RFC 3986: `%20` for a space), the jdk engine
  RFC 3986 too since 1.2.0 (`WireFormat.url`; form encoding, `+`, up to 1.1.5). Up to 1.1.4 the Play engines passed them verbatim: a `#`
  cut the query off (Pinecone `fetch(ids)` with `doc#0` came back empty), `&` / `=` split a
  value. A query written into the endpoint string is not a parameter - the caller encodes it.
  The Play engines send it byte for byte (openai-scala's SigV4-signed Bedrock GETs build their
  query there: the sent query must match the signed one); sttp / pekko-http / zio-http
  re-encode it (`a%2Fb%20c` goes out as `a/b+c`, zio-http `a%2Fb+c` - pre-existing). Covered
  on every engine by the shared testkit scenario `TestServers.queryEncoding`; the Play specs
  pin the endpoint query's bytes
- `TransportSettings.proxyURL` ("host:port" or "scheme://host:port", port required; only
  host + port are used - always a plain HTTP proxy, scheme / credentials / path ignored) is
  honored by the Play/jdk/sttp/zio-http engines (zio-http tunnels via CONNECT but resolves the
  target host through local DNS); pekko-http warns and ignores it (CONNECT-only proxy
  support). A proxy is a property of the shared client - all engines bound to one transport
  share it
- `SiteBinding.recoverErrors` is composed with the engine's default normalization via
  `SiteBinding.resolveRecoverErrors` (on every creation path) - user recovery sees Cequence
  exceptions for recognized transport failures (portable across engines)
- `SiteBinding.requestContextFun` re-evaluates the request context per request on every
  engine (token refresh etc.); engines read the context exclusively via
  `site.requestContextFn` - exactly ONCE per request, so its headers and query params come
  from the same evaluation. Client-level settings (connect timeout, proxy; all timeouts on
  Play) are captured once at construction/first use
- Streaming: every engine checks the HTTP status BEFORE exposing a streamed body - a non-2xx
  status fails the stream with a `CequenceWSHttpStatusException` - a `CequenceWSException`
  subclass (`EngineSupport.streamErrorMessage`,
  "<svc>: HTTP <status> - <start of body>"), reading at most `EngineSupport.MaxErrorBodyBytes`
  (4 KiB) of the error body within 10 s or the request timeout (core `BoundedBodyReader` for
  `Flow` bodies, core-akka `StreamErrorBody` for `Source` bodies). The failure is a
  structured `CequenceWSHttpStatusException(statusCode, body)` (message unchanged since 1.1.0);
  services classify it with their own `handleErrorCodes` via `WSClientBase.mapHttpStatusErrors`
  - automatically in the service-level `execJsonStream` / `execRawStream` of
  `WSClientWithEngineOutputStreamingBase` (core-akka, generated to core-pekko), or with
  `.mapError(mapHttpStatusErrors)` on engine-level calls. The default `handleErrorCodes` of
  non-streamed calls throws the same structured exception. The akka/pekko `handleException`
  (shared core-akka `StreamFailures`, used by the Play stream and pekko-http engines) passes
  Cequence exceptions through unwrapped. SSE comment lines (`:`-first keep-alives such as
  OpenRouter's `: OPENROUTER PROCESSING` or OpenAI's bare `:`) and blank frames are ignored by the
  shared `JsonStreamFrames.parseFrame` (result `Skip`, since 1.1.3 - a comment-only frame used to
  fail the stream as non-JSON) on every engine. An SSE event is a frame with a line STARTING with
  `data:` (with or without the space after the colon; never matched mid-line): its comment lines
  are dropped, CR / CRLF line breaks are normalized, the payload is everything after the prefix;
  a frame without such a line is a raw payload (Gemini array framing) parsed as is after its
  leading comment lines; `[DONE]` is matched after trim. Still
  frame-level, not a full SSE parser: multi-line `data:` events are not joined, field lines after
  the data line are not dropped, and the frame delimiter stays `\n\n` by default (servers that
  terminate events with `\r\n\r\n` need `framingDelimiter = "\r\n\r\n"`)
- Form fields: repeated keys keep every value on every engine (`EngineSupport.groupValues`,
  never `.toMap`); multipart files go out under their BASE name (`FilePart.filenameAux`) unless
  a display name is given. Caller-supplied `Content-Disposition` values (data-part names, file
  keys, file names) are encoded by core `MultipartParams` (`"` / CR / LF -> `%22` / `%0D` /
  `%0A`, since 1.1.4) on every engine: the hand-built bodies (`MultipartBodyBuilder`,
  `PlayWSMultipartWritable`) via `FilePart.name` / `filenamePart` / `dataPartName`; the native
  sttp / zio-http / pekko-http renderers get `MultipartParams.escape`d names (none of them
  escapes CR / LF; pekko-http drops the header instead). The one exception: pekko-http FILE
  names go out raw - pekko-http renders them itself, `?` per unsafe character plus an RFC 5987
  `filename*` (so servers see a different file name there than on the other engines). A
  data-part name that is a token stays unquoted (bytes as before). Covered on every engine by
  `TestServers.multipartInjection`. `FilePart.extension` (the default content-type lookup) is
  the part after the last dot - empty for a name without a dot (`split('.').last` made such a
  name its own extension: a file named `pdf` went out as `application/pdf`), after a trailing
  dot, and for a name of only dots (`.last` threw there)
- A remote value never enters a message whole: `JsonUtil.JsonOps.asSafe` renders a mismatching
  JSON only as `jsonExcerpt` (compact, at most 500 chars, nesting below 16 levels elided) and
  once (its own `CequenceWSException` is not re-wrapped). Up to 1.1.4 it pretty-printed the
  whole value twice - text growing with size x depth: a 643 KB response exhausted a 512 MiB
  heap. Never interpolate `Json.prettyPrint` / `Json.stringify` of a whole remote value
- Timeouts bound the response BODY on every engine: java.net.http's `HttpRequest.timeout` stops
  at the response headers, so the jdk engine wraps `ofString` in `DeadlineBodySubscriber`
  (the rest of the `requestTimeout` budget; cancels the body subscription - which closes the
  connection - and fails with `HttpTimeoutException`) and bounds streams with
  `IdleTimeoutPublisher` (`readTimeout`, default `requestTimeout`; counts only while the
  subscriber has demand, so slow consumers and long healthy SSE streams are fine); the sttp
  engine reads the body itself (`asStringWithDeadline`: closes the stream at the deadline, the
  read marked `blocking`). Up to 1.1.4 a body that stalled after the headers hung jdk / sttp
  calls forever (sttp also parked a global-EC thread). Shared daemon timer:
  `EngineSupport.deadlineScheduler`. Covered on every engine by `TestServers.stalledBody`
  (pekko-http: the stream part is not checked - pekko's own client idle-timeout, 60 s, bounds
  it, not `readTimeout`)
- pekko-http: a response that arrives after `requestTimeout` is ABORTED (entity cancelled,
  connection closed), not drained (up to 1.1.4 `discardBytes` read a late SSE generation to
  its end, holding the pool slot). The per-host pool is sized for a shared multi-site engine
  (`PekkoHttpWSClientEngine.sizedForApiUse`: max-connections 1024 / max-open-requests 2048,
  each only while still at pekko-http's reference default - pekko's 4 let one site's slow
  calls queue every other site). Residual, by pekko-http's design: a request still queued for
  a connection (beyond max-connections in flight to one host) cannot be withdrawn and is sent
  after its caller timed out. Same-system copies share per-host pools (keyed by host +
  settings)
- A proxy's non-200 reply to CONNECT fails the request on the Play engines
  (`PlayWSClientEngine.ConnectReplyGuard`, added to every AHC channel by `channelInitializer`
  together with the bounded inflater - both flavors). Only a 200 establishes a tunnel; up to
  1.1.4 any other reply was taken as the https site's own response (a forged 202 + body
  accepted as success). Covered by `TestServers.proxyConnectReply` (Play, jdk, sttp, zio-http)
  and `connectTunnel` (Play: a real 200 tunnel to the TLS test server)
- Failure messages never carry the request URL (query params may hold API keys). sttp's
  `SttpClientException` text includes the full URI, so `SttpWSClientEngine.defaultRecoverErrors`
  describes a failure by the transport cause below the sttp wrapper and maps every remaining
  sttp failure to `CequenceWSException` - never put `e.getMessage` of a request-describing
  exception into a message
- `StreamTransformers.DelimiterFramer` (jdk / zio-http SSE framing) tracks frames by offset and
  compacts its buffer once per chunk - keep it linear per chunk (`DelimiterFramerSpec` diffs it
  against the 1.1.2 implementation)
- No engine follows redirects (sttp disables its follow-by-default per request, the Play engines
  set `setFollowRedirect(false)` - the shaded AHC's default is a JVM-wide property) - a 3xx comes
  back as a non-acceptable status. Following redirects would re-send bodies and custom secret
  headers (e.g. `X-Api-Key`) to whatever origin the `Location` names
- No engine keeps a cookie jar (since 1.1.4): `Set-Cookie` is surfaced in `RichResponse.headers`
  but never stored or replayed - a jar is per-engine state shared by every `SiteBinding`. Keep
  `.setCookieStore(null)` in `PlayWSClientEngine.client`: the shaded AHC builder defaults to ONE
  engine-wide `ThreadSafeCookieStore` (Play's own `AhcConfigBuilder` disables it, but the engine
  builds its AHC config directly), which replayed one site's / tenant's cookies on every other
  `SiteBinding` (AHC 2.1.11 even honored a foreign `Domain` attribute). Covered on every engine by
  the shared testkit scenario `TestServers.cookieIsolation`
- Public names of the stream layer must not clash with Akka / Pekko / ZIO / fs2 names a user
  imports next to ours (wildcard imports): `AsyncBroadcastHub`, `AsyncScheduler`,
  `AsyncCancellable`, `ByteFraming` (not `BroadcastHub`, `Scheduler`, `Cancellable`,
  `Framing`) - renamed before the 1.2.0 release, with the main downstream consumer's
  agreement. Bridge diagnostics are scoped only (`StreamDiagnostics.counted`); there is no
  JVM-wide counter
- Restricted headers (since 1.2.0): `java.net.http` throws for headers it sets itself (`Host`,
  `Connection`, ...). The jdk engine and the sttp engine ON ITS OWN JDK BACKEND
  (its `filterRestrictedHeaders` constructor flag: a caller-supplied sttp backend is never filtered) drop
  them with a one-time warning via `JdkRestrictedHeaders` (core, `private[wsclient]`) - only a
  header java.net.http derives itself (host, connection, content-length, expect, upgrade) AND
  the running JDK refuses (a throwaway `HttpRequest.Builder.header` probe, cached per name, so
  `-Djdk.httpclient.allowRestrictedHeaders` and `conf/net.properties` decide). Any other
  refusal still fails the call: JDK 11 before 11.0.14 also refuses `Referer`, `Origin`, ... -
  dropping those would silently break e.g. referrer-restricted API keys. It is loaded only by those two engines: core also runs on Java 8. Covered
  on every engine by `TestServers.restrictedHeaders` (and binary-safe `bytes` by
  `TestServers.bufferedBytes`)
- Stream HTTP-status failures (`CequenceWSHttpStatusException`) are logged at DEBUG, any other
  stream failure at ERROR (`StreamFailures.log`, Play stream + pekko-http; jdk / zio-http log
  none): the status is the caller's to handle (the download helpers map a 404 to `None`), and
  it still reaches the caller as the exception
- `DirectWSClient` defaults a scheme-less URL to `https://` and rejects schemes other than
  http/https; plain HTTP needs an explicit `http://`
- Artifact rename hazard: the pre-1.0 `ws-client-play` / `ws-client-play-stream` artifacts and
  their ≥ 1.0.0 successors `ws-client-play-akka` / `ws-client-play-akka-stream` share FQCNs
  but not artifact ids, so there is no eviction - old + new on one classpath means
  jar-order-dependent shadowing (README documents exclusion rules). `WSClientEngineRegistry`
  probes marker classes via `ClassLoader.getResources` on first use and logs a warning naming
  the conflicting jars (covers akka+pekko mixing and old+renamed coexistence); with the config
  key `ws-client.strict-classpath = true` (or the same system property) it throws a
  `CequenceWSException` instead, on every lookup - recommended for CI
- JSON repair is procedural (faithful port from Python) and may not be idiomatic Scala. Its
  repair path deliberately coerces the strings "true"/"false"/"null" to typed values - desired
  behavior, keep it (do not make it opt-in). `loads` / `repair` / `repairJson` decode a text
  that is exactly ONE valid JSON value (Jackson streaming check `isSingleJsonValue`: one value,
  then only whitespace) with `Json.parse` - up to 1.1.4 the brace-count heuristic
  (`isConcatenatedJsonString`) sent almost every valid NESTED document to the repair parser,
  where string content could add, replace or drop sibling members (a value ending in an
  escaped backslash) and `[ ]` / `{ }` swallowed the members after them. For such a text the
  heuristic flags, only the repair path's leaf coercions are kept (`coerceStringLeaves`:
  "true"/"false"/"null", `convertNumbers`) - never `convertToJsValue` on a strict value (it
  strips quote characters that are content); flat valid text gets `Json.parse` as before. Every
  other input takes the path it always took (malformed -> repair parser, `{..}{..}` -> array);
  `fromFile` / `repairJsonAsValue` are unaffected. The routing differential (2026-11-05): 20,000
  generated valid + mutated inputs x 11 entry-point variants on 2.12 and 2.13 - every difference
  a single valid value whose new result is `Json.parse` + the coercions, malformed input
  identical. On the repair path (malformed input) key order beyond 4 keys is lost (the parser
  accumulates into a plain `Map`) - deliberately left so; the repair path must not otherwise
  alter content: apostrophes are preserved. `JsonParser` is a large, fragile port: keep
  changes there minimal (prefer fixes in `JsonRepair.convertToJsValue`), and verify any change
  differentially against the previous version - thousands of generated valid + mutated inputs
  through every entry point (`repairJson`, `loads` flag variants, `repairJsonAsValue`,
  `fromFile`) on 2.12 and 2.13, every output difference explained. Arrays and concatenated
  top-level values accumulate with `::` plus one `reverse` (never `:+` - it copies the list per
  element, quadratic; `::` also keeps the recursive `parseArray` frame as small as before,
  which a `ListBuffer` did not). The public entry points run inside `withStackGuard`: input
  nested deeper than the thread's stack fails with an ordinary `IllegalArgumentException`
  instead of a `StackOverflowError` (which escapes `NonFatal` and leaves a `Future`
  incomplete). No depth limit by design - a limit would change results for deep input that
  repairs today on a large stack. `fromFile` parses exactly the characters the string entry
  points parse: `StringFileWrapper` decodes the whole file once (platform charset), by character
  (it used to index byte chunks by byte offset - multibyte files crashed); a differential check
  of `fromFile(skipJsonParse = true)` against `loads(content, skipJsonParse = true)` must match
  100%. Every parser loop must consume input per iteration: `parseArray` steps past a value that
  consumed none (as `parse()` does at the top level) - the context stack is known to leak (a
  `{""}` key without a value, the duplicate-key break leave `ObjectKey` behind; deliberately
  left so, the leak shapes many repairs), and under it `parseString` can return without
  consuming, which used to loop forever while the array grew. The right-trim (Python's
  `rstrip`) is the precompiled `(?<!\s)\s+$` - never `replaceAll("\\s+$")`, quadratic on a long
  interior whitespace run. Escape sequences decode like a JSON parser's (the decoded character
  replaces the backslash, `\uXXXX` included) EXCEPT `\"`, which keeps its backslash for
  `convertToJsValue` to unescape (it strips the quotes around a value, so a decoded
  `\"quoted\"` would lose them); a newline that came from an escape is content, not trimmed.
  Valid JSON now decodes exactly like Jackson (plus the leaf coercions) whatever its strings
  hold - the Jackson oracle must stay at 100%. In test sources, no backslash-u in any
  literal or comment: Scala 2.12's lexer decodes (or rejects) it everywhere, even in
  triple-quoted strings - build it as `"\\u" + hex`. Tips for the differential check: run it as a
  standalone JVM (sbt's 1 GB heap OOMs on a looping input), with a per-call timeout and
  `-XX:-OmitStackTraceInFastThrow` (hot NPEs otherwise lose their message), and compare
  exception classes, not messages. Numbers are a `Double` / `Long` only where that holds the literal exactly, else
  a `BigDecimal` of play-json's precision (DECIMAL128): a double rounded digits beyond its
  precision, flushed tiny numbers to 0 and huge ones to Infinity (a NumberFormatException in
  `JsNumber`), and an integer beyond Long came back as a string; `repairJsonAsValue` returns that
  `BigDecimal`. A literal longer than 1000 characters (Jackson's limit) never becomes one -
  `BigDecimal` parsing is superlinear - and stays text where a double cannot hold it. An
  exponent's `+` belongs to the number (Python's NUMBER_CHARS lacks it: `2.50E+3` came back as
  `2.5`); a `+` no digit follows still ends the number before its `e`. A second differential
  oracle - valid nested number-heavy JSON (signed / unsigned exponents, values beyond a double
  or a Long, long decimals) repairs exactly like Jackson / play-json parses it - must stay at
  100% (it was 27%)
- `AsyncStream` (since 1.2.0) is an initial encoding: an immutable AST of 19 private nodes
  (`stream/impl/Nodes.scala` - 8 leaves, 11 operators unary on their upstream; second branches
  are lazy children compiled at run time) dispatched through the `StreamCompiler` visitor, so a
  compiler that misses a node does not compile. Rules for every compiler (neutral, Akka/Pekko,
  fs2, ZIO): compiling is pure - no user thunk runs at compile time, only per run; the spine is
  walked iteratively (`StreamCompiler.compile`); a compiler returns its OWN native leaf as is
  (`src.toAsyncStream.toSource eq src`) and bridges only at a foreign leaf (1) or a foreign
  native pipe (2), counting each in its `BridgeCounter` (captured when a compilation / run
  starts: `StreamDiagnostics.counted` scopes it; lazy children inherit it - the Akka/fs2/ZIO
  compiler instance carries it, a neutral run carries it in its `RunContext`). A visitor
  method added after 1.2.0 needs a default (a new OPERATOR: the node as a neutral `NativePipe`
  via `viaNative`; a new LEAF: a neutral `NativeStream` via `native`, or `fromCursor`), so
  interop jars built before a NEW visitor method keep working - nothing more: the interop
  modules use other `private[wsclient]` core internals, so all ws-client artifacts must share
  one version (README says so). Semantics are pinned by
  differential specs (native vs neutral: core-akka/-pekko `SourceDifferentialSpec`, the fs2 and
  ZIO specs) - sanctioned differences: on a failure Akka's / Pekko's asynchronous stages
  (`flatMapConcat`, `mapAsync`) fail fast and may drop in-flight elements (the neutral
  interpreter, fs2 and ZIO deliver them first), and below an early stop a native upstream's
  completion can race the cancel (an extra end hook); fs2 / ZIO read an iterator (an `emits`
  leaf, an element stage's emitted iterable) up to 64 elements ahead (one output chunk,
  `MaxOutputChunk`) where neutral / Akka read one per demand. fs2 / ZIO element stages process
  chunks element by element (a throw emits the prefix first) and drain an emitted iterable
  lazily - never `out ++= emit` (an infinite `LazyList` must stay back-pressured); ZIO user
  functions run under `ZIO.attempt` (failures, not defects), and a native ZIO leaf's defect is
  a failure for recover / mapError / onTerminate (`ZioCompiler.failureOf`); waits on user
  futures are cancelable (fs2 `fromFutureCancelable`), termination hooks are awaited. The
  Akka / Pekko concat is `ConcatQueueStage` (one stage, a per-materialization queue, next
  streams unrolled - constant memory for recursive concats; a thunk runs only on demand: never
  a flatMapConcat over the thunks, Akka's flatMapConcat opens the next inner source early; it
  completes as soon as its last stream ended, demand or not). `toFlow`'s fallback input (a
  `prefixAndTail(0)` tail) has no subscription timeout and is cancelled when the pipe's output
  ends unopened or the pipe's function throws; it can be consumed once. Akka / Pekko stages
  that outlive their upstream (a pending hook / task) guard `onPull` with `isClosed(in)`.
  Akka's publisher input buffer (`fromPublisher`'s `bufferSize`) is preallocated: capped at
  `SourceCompiler.MaxInputBuffer` (4096). Every publisher into fs2 goes through the pull bridge
  `Fs2Compiler.cursorStream` over a neutral cursor (`SubscriberCursor`) - never
  `fs2.interop.flow.fromPublisher`, whose subscriber holds elements back until a chunk is full
  (SSE in bursts; a stalled stream withheld events). `ZioCursor` serializes pull vs cancel
  (Idle / Starting / in-flight / Cancelled) and closes the scope only after an interrupted pull
  finished; ZIO keeps an interruption an interruption (`failureOf`), where the cursor bridge
  sees a leaf's self-raised interruption as a failure (sanctioned)
- The neutral interpreter (`NeutralCompiler`) is pull-based: at most one outstanding
  `Cursor.next()`, `cancel()` may race it (the pending future completes with `None`). Foreign
  completions re-enter the run's `ec`; internal continuations use the `Trampoline`. Consecutive
  map / mapConcat / stateful nodes fuse into one cursor (`FusedCursor` - keep its scan linear:
  after feeding stage i+1 continue at i+1, never restart from the top); consecutive concats fuse
  and a next stream that is itself a concat is flattened (constant stack for `def loop =
  single(1) ++ loop`). `CursorPublisher` pulls one result ahead of demand (RS 1.4: a failure
  is signalled without demand); a `request` from outside its drain loop drains on the run's
  `ec` (pulling runs the run's user functions - never on a subscriber's / IO thread), and the
  loop yields `ec` every 1024 synchronous results (an ec that rejects: the loop continues
  inline, iteratively - never recurse); the start task holds the loop while `onSubscribe` runs
  (rule 1.3: a request from inside it only registers). A cursor that hands a promise to `ec`
  (`SubscriberCursor`, the hub consumer) keeps it reachable for `cancel()` (`delivering`), so
  a cancel in that window still completes the outstanding `next()` with `None`
- Never call an upstream `Flow.Subscription` while holding a lock the upstream's thread may
  need: `TransformPublisher` (jdk / zio-http streams) delivers downstream signals under its
  monitor, so its upstream `request` / `cancel` calls are only recorded and issued by one
  serializing loop (`flushUpstream`, RS 2.7) - skipped while `Thread.holdsLock(this)` (a cancel
  a downstream issues from inside our signal, e.g. at `[DONE]`), so the thread's outermost frame
  issues them after releasing the monitor; every entry point flushes after its synchronized
  block. Nothing may unwind through that loop: downstream signals go through `signal` (a
  throwing subscriber counts as a cancel, rule 2.13) and a throwing upstream `request` fails
  the stream (3.16) - an escaped exception would leave the loop's counter up and swallow every
  later cancel. Up to 1.1.5 it cancelled under the monitor (a downstream
  `take(1)` racing the next element deadlocked two chained transforms), and requested under it
  (a request racing the upstream's completion - fixed in 1.2.0); `SubscriberCursor` serializes
  its subscription calls with a lock-free work-in-progress loop for the same reason
- Wire format (since 1.2.0): every engine sends JSON bodies as `WireFormat.jsonBody` (Play WS's
  `Json.toBytes` rendering: non-BMP characters such as emoji escaped as `\uXXXX`) and reports
  it via `WSClientEngine.renderJsonBody`; `wireUrl` is the exact URL an engine sends (Play / jdk:
  the shared `WireFormat.url`; sttp / pekko-http / zio-http: their library's rendering, built by
  the same helper the request uses) - request signers (SigV4) depend on both. The testkit
  contract (`asyncStreamContract`) pins them on every streaming engine
- `TransportSettings.httpVersion` (since 1.2.0; honored by jdk) was added with a 1.1.x
  binary-compatibility shim: the explicit companion extends `AbstractFunction2` and keeps the
  2-arg `apply`, the class a 2-arg constructor and `copy`. MiMa does not report the changed
  generic signature of the synthetic `unapply` (Tuple3): Scala 2 code compiled against 1.1.x
  that calls it explicitly would fail with a ClassCastException (pattern matches use the
  accessors; on Scala 3 the synthetic `unapply` returns the instance - unchanged)
- The typed `CequenceWSFrameTooLongException(message, maxFrameLength)` (since 1.2.0) is what
  every engine fails an oversized frame with: the core framer (jdk, zio-http, `ByteFraming`)
  throws it, the Akka / Pekko engines map Akka's `FramingException` to it
  (`StreamFailures.frameTooLong`); the message is unchanged
- The Akka / Pekko stream engines return their `execJsonAsyncStream` / `execRawAsyncStream` as
  native leaves (`SourceInterop.engineLeaf` over `Source.lazySource` - Play and pekko-http fire
  the request when `execJsonStream` is CALLED, the neutral stream must be cold). zio-http
  returns native ZIO leaves (`ZioInterop.byteChunkEngineLeaf` over `ZStream.suspend` - the
  request context and request are evaluated per run; the JSON stream is that leaf +
  `JsonStreamPipes.jsonFrames`, compiled natively): `toZStream` / `toZByteStream` cost 0 bridges,
  a neutral run 1 per stream. `preflight` (the 404 check of `execStreamDownload`) keeps such
  a stream native: a native leaf that mixes in the core hook `NativePreflight`
  (`private[wsclient]`; `SourceLeaf`, `ZioLeaf` - not fs2, which takes the neutral preflight)
  preflights the WHOLE stream with its backend's compiler, on the leaf's own materializer /
  runtime (the caller's `ec` is not used), and returns a native single-run leaf - a download's
  plan is `native(<backend>) -> mapError`, 0 bridges for a native consumer (pinned per engine:
  the contract's `downloadPlan`, the provider specs). Akka / Pekko: `prefixAndTail(1)` +
  `Sink.head`; the tail has NO subscription timeout (the caller may hold the stream for any
  time - with the default 5 s cancel mode the held run would be torn down) and the replay
  claims its single run in `Source.fromMaterializer` - at materialization, not on first demand
  (a `lazySource` there would leak the held request when a run is cancelled before it pulls).
  ZIO: the stream's `toPull` in a `Scope` of its own, closed with the run's exit (or at once
  when the stream is empty / failed); the future fails with the stream's own exception
  (`start.either` - no fiber-trace suppressed exception attached). An unrun preflighted stream
  holds its request open, as on the neutral path. `toZByteStream` peepholes the `Mapped(ZioLeaf, FromChunk)` shape
  back to the chunk stream (as `toByteStringSource` does for Akka). jdk inherits the
  publisher-wrapping defaults of `WSClientOutputStreamCore`
