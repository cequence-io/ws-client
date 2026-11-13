# Compatibility and upgrading 🧩

[← back to the README](../README.md)

What may and may not share a classpath, which Scala and Java versions each module serves, and
what to check when upgrading. Per-version notes are in the [changelog](../CHANGELOG.md).

## Akka or Pekko - never both

**The Akka and Pekko flavors are mutually exclusive on one classpath.** The Pekko modules are
generated from the Akka ones and deliberately keep the same package and class names (the same
trade-off Play made between 2.9 and 3.0), so downstream code compiles unchanged against either
flavor - switch by swapping the artifact and your `Materializer` import.

| Akka flavor | Pekko flavor |
|---|---|
| ws-client-core-akka | ws-client-core-pekko |
| ws-client-play-akka | ws-client-play-pekko |
| ws-client-play-akka-stream | ws-client-play-pekko-stream |
| - | ws-client-pekko-http |

Never depend on both: which classes (implicits such as `asSafeSource` included) actually load
then depends on jar order. `WSClientEngineRegistry` detects such duplicated classes at runtime
and logs a warning naming the conflicting jars; set the config key
`ws-client.strict-classpath = true` (or `-Dws-client.strict-classpath=true`) to fail engine
resolution with an exception instead - recommended for CI.

The flavor-free modules (`ws-client-core`, `ws-client-jdk`, `ws-client-sttp`,
`ws-client-zio-http`, `ws-client-fs2`, `ws-client-zio`, `json-repair`) combine with either.

## One version for all ws-client artifacts

Use one version for all ws-client artifacts on a classpath: the interop and engine modules call
core internals, so e.g. `ws-client-fs2` 1.2.0 next to `ws-client-core` 1.1.5 is not supported.

## Artifacts renamed in 1.0.0

**Upgrading from ≤ 0.8.1:** the Akka-based Play modules were renamed for symmetry with the
Pekko flavor - `ws-client-play` → `ws-client-play-akka` and `ws-client-play-stream` →
`ws-client-play-akka-stream`. The old and new artifacts contain the same packages and classes
but have different artifact ids, so dependency resolution will **not** evict the old ones - with
both on the classpath, which classes win depends on jar order, and engine discovery may not find
the `play-akka` / `play-akka-stream` providers. Make sure no transitive dependency still pulls
the old artifacts, or exclude them explicitly:

```scala
libraryDependencies += ("your" %% "dependency" % "x.y.z")
  .excludeAll(
    ExclusionRule("io.cequence", s"ws-client-play_${scalaBinaryVersion.value}"),
    ExclusionRule("io.cequence", s"ws-client-play-stream_${scalaBinaryVersion.value}")
  )
```

The `ws-client.strict-classpath` check above covers this case too.

## Logging

The library logs through the SLF4J API only and ships no logging backend - bring your own (e.g.
logback). Keep `play.shaded.ahc.org.asynchttpclient.netty.handler` at `INFO` or above: at
`DEBUG` Play's shaded AsyncHttpClient logs complete request headers, including `Authorization`.

## Scala versions

| Modules | Scala |
|---|---|
| ws-client-core, ws-client-core-akka, ws-client-play-akka, ws-client-play-akka-stream, ws-client-jdk, json-repair | 2.12, 2.13, 3 |
| ws-client-core-pekko, ws-client-play-pekko, ws-client-play-pekko-stream, ws-client-pekko-http | 2.13, 3 (3.3+) |
| ws-client-fs2, ws-client-zio | 2.12, 2.13, 3 (3.3+) |
| ws-client-sttp, ws-client-zio-http | 2.12, 2.13 |

The Scala 3 artifacts of the core, Akka, `jdk` and `json-repair` modules are compiled with Scala
3.2, so they serve Scala 3.2 and 3.3+ projects alike; the Pekko, `fs2` and `zio` modules - whose
own dependencies need it - build theirs with Scala 3.3 LTS.

## Java versions

- `ws-client-jdk` - and `ws-client-sttp` on its default backend - run on `java.net.http`, which
  needs JDK 11+.
- `ws-client-play-akka` / `ws-client-play-akka-stream` on Scala 2.13 need Java 11+ since
  1.2.0 (Play WS 2.2.18). Their Scala 2.12 and Scala 3 artifacts still run on Java 8.
- On the Play engines, TLS 1.3 needs JDK 11+ (on Java 8 the Akka flavor negotiates TLS 1.2).
- The `log` service adapter (`LogServiceAdapter`) needs Java 11+. 1.2.0 needed Java 17 for it
  by mistake (`UnsupportedClassVersionError` on Java 11) - use 1.2.1.

## Binary compatibility

Releases are checked against the previous one with MiMa. The one known exception in 1.2.0:
`TransportSettings` gained a field - code compiled against 1.1.x keeps working (construction,
`copy`, accessors and pattern matches), except, on Scala 2, an explicit call of its synthetic
`unapply`, whose result is now a three-element tuple.
