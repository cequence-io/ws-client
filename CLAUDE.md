# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**WS Client** is a generic WebServices client library for Scala that provides an abstraction layer over Play WS implementation. The library enables type-safe HTTP requests with support for multiple Scala versions (2.12, 2.13, and 3).

The project includes:
- Core WS client interfaces and abstractions (Akka-free), including an engine-discovery SPI
- Akka-based streaming extensions (optional)
- Pekko-based mirror modules (source-generated from the Akka ones at build time)
- Play WS backend implementations (Play WS 2.x/Akka and Play WS 3.x/Pekko)
- Streaming support for large payloads
- JSON repair utility for fixing malformed JSON from LLMs

Current version: 1.1.0

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

The project is organized into 13 SBT modules (9 handwritten + 3 Pekko mirrors + a test-only testkit):

1. **ws-client-core** - Core abstractions and interfaces (Akka-free)
   - No concrete HTTP implementation, no Akka dependency
   - Defines `WSClient`, `WSClientEngine`, and `WSClientBase` traits
   - Contains domain models (`Response`, `RichResponse`, `WsRequestContext`, `CequenceWSException`)
   - Provides service adapters (logging, retries, round-robin)

2. **ws-client-core-akka** - Akka-based streaming extensions
   - Depends on `ws-client-core` + Akka Streams
   - Adds `StreamedResponse` (extends `Response` with `source: Source[ByteString, _]`)
   - Adds `WSClientInputStreamExtraAkka` (Pekko twin: `WSClientInputStreamExtraPekko`) - mixin for input stream POST methods (`execPOSTSource*`)
   - Adds `WSClientOutputStreamExtraAkka` (Pekko twin: `WSClientOutputStreamExtraPekko`) - trait for SSE/JSON streaming (`execJsonStream`, `execRawStream`)
   - Provides `StreamResponseImplicits` for `asSafeSource` on `Response`/`RichResponse`
   - Contains `PollingHelper`, `ParallelTakeFirstAdapter`, `ServiceBaseAdaptersAkka`

3. **ws-client-play-akka** - Play WS backend implementation (named `ws-client-play` before 1.0.0)
   - Depends on `ws-client-core-akka`
   - Implements `PlayWSClientEngine` using Play WS standalone client
   - Handles HTTP methods: GET, POST, DELETE, PATCH, PUT
   - Supports multiple content types: JSON, multipart/form-data, URL-encoded, file uploads, streaming
   - Default timeouts (for unset `Timeouts` fields): 60 s request/read, 5 s connect, 60 s pooled-connection-idle
     (the jdk/sttp engines default the request timeout to 120 s)

4. **ws-client-play-akka-stream** - Streaming extensions (named `ws-client-play-stream` before 1.0.0)
   - Depends on `ws-client-core-akka` and `ws-client-play-akka`
   - Adds `PlayWSStreamClientEngine` for Server-Sent Events (SSE)
   - Uses Akka HTTP for JSON streaming over WebSockets
   - Streaming frame limit: 20,000 bytes (defaultMaxFrameLength)

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
     Scala 2.13). Supports input streaming (`execPOSTSource`) and native output (SSE) streaming.
     No Play WS / shaded AHC.
   - `ws-client-zio-http`: engine on the zio-http (Netty) client + ZIO runtime (depends only on
     ws-client-core; Scala 2.12/2.13 - zio-http's Scala 3 artifacts need 3.3+). Output (SSE)
     streaming via `WSClientOutputStreamCore` (ZStream bridged with zio-interop-reactivestreams +
     `FlowAdapters`, then the jdk engine's framing pipeline); the stream status is checked before
     any byte is exposed. Discovery-created engines own a Netty driver + client (`close()`
     releases both); `copy(reuseExecContext = true)` shares the driver. `ZioHttpWSClientEngine(client)`
     / `.layer()` run on a caller-owned `Client` (never closed). Owned engines run Netty on
     DAEMON threads: zio-http has no public hook for that, so `ZioHttpWSClientEngine.DaemonDriver`
     reaches the package-private `NettyClientDriver` / `NettyRuntime` constructors REFLECTIVELY
     (a zio-http upgrade cannot break compilation) and falls back to the stock non-daemon driver
     with a warning if the lookup fails - re-check it on zio-http upgrades (the spec asserts the
     daemon driver is in use). With a proxy, zio-http still resolves the TARGET host via local
     DNS.
   - All four use `StringBackedResponse` / `SimpleRichResponse` from core (no streaming source
     on responses) and the shared `EngineSupport` scaffolding (core, `private[wsclient]`).

7. **ws-client-core-pekko / ws-client-play-pekko / ws-client-play-pekko-stream** - Pekko mirrors
   - Sources are GENERATED at build time by `project/PekkoGenerator.scala` from the corresponding
     Akka modules (`akka.*` → `org.apache.pekko.*` rewrite). NEVER edit files under
     `target/**/src_managed` - edit the Akka module sources instead; the Pekko flavor follows.
   - Handwritten exceptions: `PlayPekkoWSClientEngineProvider.scala`,
     `PlayPekkoStreamWSClientEngineProvider.scala` and their `META-INF/services` resources (the
     generator skips `*EngineProvider.scala` files because engine ids/priorities differ), plus all
     test specs - the generator mirrors Compile sources only, so each Pekko module has its own
     handwritten specs.
   - `ws-client-play-pekko` uses `org.playframework` play-ws 3.x (its play-json 3.x dependency is
     excluded - core's `com.typesafe.play` play-json is used instead);
     `ws-client-play-pekko-stream` uses pekko-http.
   - Cross-built for Scala 2.13 only (play-ws 3.x needs Scala 2.13 or 3.3+; the build is on 3.2.2).
   - IMPORTANT: generated Pekko classes share fully qualified names with the Akka ones by design,
     so the Akka and Pekko flavors are mutually exclusive on one classpath (downstream code compiles
     unchanged against either). Avoid `akka.`-package references in strings/comments in the Akka
     modules - the generator rewrites `akka.` package references everywhere and fails the build if
     any survive.

8. **ws-client-testkit** - test-only fixtures (never published): embedded echo / SSE / proxy-stub
   servers (`TestServers`) and a generic `LatchedSubscriber` for `Flow` publishers; engine
   modules depend on it `% Test`. Core's test classpath is NOT shared via `test->test` - its
   dummy engine providers would leak into every engine's discovery tests.

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
- `Response` - base trait with `json` and `string` (in `ws-client-core`)
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
- Akka Stream: 2.6.1 (Scala 2.12), 2.6.20 (Scala 2.13/3.2)
- Play WS (Akka): 2.1.11 (Scala 2.12/2.13), 2.2.0-M2 (Scala 3.2)
- Play WS (Pekko, `org.playframework`): 3.0.7; Pekko Stream: 1.6.0; Pekko HTTP: 1.3.0 (Scala 2.13 only)

Scala 3 requires manual cross-version suffix handling (e.g., `akka-stream_2.13` for Scala 3).

Cross-building is per-project: the Pekko modules declare `crossScalaVersions := List(scala213)`, so
`sbt +compile` from the root builds them only for 2.13. `ws-client-sttp` (2.12/2.13) and
`ws-client-pekko-http` (2.13) also narrow their versions; the rest build for all three.
Version-dependent dependency settings must be `Def.setting`s reading the project-level
`scalaVersion` (NOT `inThisBuild` settingKeys) - with heterogeneous crossScalaVersions, `+` switches
Scala per project and an `inThisBuild` lookup sees the stale default version.

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
- CI: `.github/workflows/ci.yml` runs `sbt scalafmtCheckAll scalafmtSbtCheck` and `sbt +test`

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
- Query-param encoding contract: the jdk/sttp/pekko-http/zio-http engines take raw values and
  percent-encode them; the Play engines pass values through verbatim (pre-existing behavior
  kept for backward compatibility - callers pre-encode reserved characters there)
- `TransportSettings.proxyURL` ("host:port" or "scheme://host:port", port required) is
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
  status fails the stream with a `CequenceWSException` (`EngineSupport.streamErrorMessage`,
  "<svc>: HTTP <status> - <start of body>"), reading at most `EngineSupport.MaxErrorBodyBytes`
  (4 KiB) of the error body within 10 s or the request timeout (core `BoundedBodyReader` for
  `Flow` bodies, core-akka `StreamErrorBody` for `Source` bodies). The akka/pekko
  `handleException` passes Cequence exceptions through unwrapped
- Form fields: repeated keys keep every value on every engine (`EngineSupport.groupValues`,
  never `.toMap`); multipart files go out under their BASE name (`FilePart.filenameAux`) unless
  a display name is given
- No engine follows redirects (sttp disables its follow-by-default per request) - a 3xx comes
  back as a non-acceptable status. Following redirects would re-send bodies and custom secret
  headers (e.g. `X-Api-Key`) to whatever origin the `Location` names
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
  behavior, keep it (do not make it opt-in). Valid NESTED JSON also takes the repair path (the
  concatenated-JSON heuristic counts braces), so the repair path must not otherwise alter
  content: apostrophes are preserved. Key order beyond 4 keys is NOT (the parser accumulates
  into a plain `Map`) - deliberately left so. `JsonParser` is a large, fragile port: keep
  changes there minimal (prefer fixes in `JsonRepair.convertToJsValue`), and verify any change
  differentially against the previous version - thousands of generated valid + mutated inputs
  through every entry point (`repairJson`, `loads` flag variants, `repairJsonAsValue`,
  `fromFile`) on 2.12 and 2.13, every output difference explained