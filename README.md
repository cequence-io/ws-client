# WS Client (Cequence)
[![version](https://img.shields.io/badge/version-1.0.0-green.svg)](https://cequence.io) [![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](https://opensource.org/licenses/MIT) [![Twitter Follow](https://img.shields.io/twitter/follow/cequence_io?style=social)](https://twitter.com/0xbnd)

This repository contains a simple and efficient Web Service client implemented in Scala. The client is designed to interact with RESTful web services, making it easy to send requests, handle responses, and manage errors.

**🔥 New**: Pekko-based backend modules (`ws-client-play-pekko`) and classpath-based engine self-discovery - see below.

As a part of this suite we also provide the [json-repair](./json-repair/README.md) library that can be used to fix common JSON syntax errors, repair malformed JSON objects and arrays.

## Installation 🚀

The currently supported Scala versions are **2.12, 2.13**, and **3** for the Akka-based modules, and **2.13** for the Pekko-based modules (Scala 3 support for Pekko is planned together with a Scala 3.3 LTS upgrade).

To install the library, add the following dependency to your *build.sbt*

```
"io.cequence" %% "ws-client-play-akka" % "1.0.0"     // Akka-based (Play WS 2.x)
```

or, for the Pekko flavor:

```
"io.cequence" %% "ws-client-play-pekko" % "1.0.0"    // Pekko-based (Play WS 3.x)
```

or to *pom.xml* (if you use maven)

```
<dependency>
    <groupId>io.cequence</groupId>
    <artifactId>ws-client-play-akka_2.12</artifactId>
    <version>1.0.0</version>
</dependency>
```

If you only need the core abstractions without Akka/Pekko dependencies:

```
"io.cequence" %% "ws-client-core" % "1.0.0"
```

> ⚠️ **The Akka and Pekko flavors are mutually exclusive on one classpath.** The Pekko modules are generated from the Akka ones and deliberately keep the same package and class names (the same trade-off Play made between 2.9 and 3.0), so downstream code compiles unchanged against either flavor - switch by swapping the artifact and your `Materializer` import. Never depend on both: which classes (implicits such as `asSafeSource` included) actually load then depends on jar order. `WSClientEngineRegistry` detects such duplicated classes at runtime and logs a warning naming the conflicting jars; set the config key `ws-client.strict-classpath = true` (or `-Dws-client.strict-classpath=true`) to fail engine resolution with an exception instead - recommended for CI.

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

- **ws-client-jdk** - zero-dependency backend on the JDK 11+ `java.net.http.HttpClient` (Scala 2.12, 2.13, 3); no Akka/Pekko/Play at all
- **ws-client-sttp** - backend over [sttp client4](https://sttp.softwaremill.com) (Scala 2.12, 2.13), unlocking any sttp `Future` backend (OkHttp, Armeria, Pekko-HTTP, ...)
- **ws-client-pekko-http** - direct pekko-http client backend (Scala 2.13), no Play WS / shaded AsyncHttpClient layer; supports input and output (SSE) streaming

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

Engines created through discovery own their execution environment (actor system / backend). To supply your own `Materializer`/`ExecutionContext`/backend, use the explicit factories (`PlayWSClientEngine(transportSettings)`, `PlayWSStreamClientEngine(...)`, `JdkWSClientEngine(...)`, `SttpWSClientEngine(...)`, `PekkoHttpWSClientEngine(...)`) - they take only client-level `TransportSettings`; the site is per call.

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

**Query-parameter encoding** differs between the engine families: the `jdk`, `sttp`, and `pekko-http` engines expect **raw (unencoded) parameter values** and percent-encode them for you; the Play-based engines pass values through verbatim (long-standing behavior, kept for backward compatibility), so with those you must pre-encode values containing reserved characters yourself. Keep this in mind when swapping engines - a pre-encoded value like `a%20b` gets double-encoded on the non-Play engines.

**Proxy support**: `TransportSettings.proxyURL` (accepted forms: `host:port` or `scheme://host:port`) is honored by the Play, `jdk`, and `sttp` engines; the `pekko-http` engine logs a warning and ignores it (pekko-http only supports CONNECT-tunneling proxies).

**Custom error recovery** (`SiteBinding.recoverErrors`) composes with the engine's built-in transport-failure normalization: your partial function sees the Cequence exception taxonomy (`CequenceWSTimeoutException`, `CequenceWSUnknownHostException`, ...) for failures the backend recognizes, so the same recovery logic is portable across engines.

**Dynamic request context** (`SiteBinding.requestContextFun`): when set, the request context is re-evaluated on every request - e.g. to refresh an expiring auth token - on all engines. Client-level settings (`TransportSettings`: connect timeout, proxy; all timeouts on the Play engines) are captured once, at engine construction or first use.

Note for sbt-assembly users: ServiceLoader registrations live in `META-INF/services`, so fat jars need `MergeStrategy.concat` (or `filterDistinctLines`) for that path.

## License ⚖️

This library is available and published as open source under the terms of the [MIT License](https://opensource.org/licenses/MIT).
