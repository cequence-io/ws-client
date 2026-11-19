# Dependencies and vulnerabilities 🔐

[← back to the README](../README.md)

What every published ws-client module brings onto a classpath - per Scala version, transitive dependencies and the libraries bundled inside other jars included - and how known vulnerabilities in them are tracked. The tables are generated from the build on `main` (`sbt +dependencyExport`, then `security/dependencies.py --write`).

## The daily scan

The `Vulnerability scan` workflow runs every day and on every change to the build: it resolves the dependencies of every published module for every Scala version, adds the libraries that jars bundle under their own name, and asks [OSV.dev](https://osv.dev) (GitHub advisories, CVEs) about each artifact - under every name an advisory may be filed for it (a Scala library's advisories are filed per Scala suffix, not always for all of them). It scans `main` and, from 1.3.0 on, the latest release - what users install - and publishes the result for the README badges (branch `security-status`).

An advisory that is not in the accepted list below fails the run and opens an issue: the dependency is upgraded, or - only when no fixed version can be used - the advisory is accepted with its status and reason. A run that could not reach OSV.dev fails without an issue.

Bundled libraries: Play WS's shaded AsyncHttpClient and Netty and jars with embedded Maven metadata are read from the jars themselves; for the others (Akka's and Pekko's shaded protobuf, Play WS's shaded OAuth signer) the script holds one rule per jar version, taken from that jar's build. A jar with relocated or vendored packages that no rule covers fails the check. A source copy without upstream coordinates is listed as such and cannot be scanned.

Run it locally:

```
sbt +dependencyExport
security/dependencies.py            # the check CI runs
security/dependencies.py --write    # after a dependency change: regenerate the tables
```

## Accepted advisories

Advisories that match a dependency's version and are accepted (`security/accepted.json`): not reachable - the vulnerable code is never used; closed in ws-client - the engine's own code prevents it; or open - not closed, with what it takes and the way out. An entry covers exactly its artifact, versions and advisories: a new advisory for the same artifact fails the scan. All of them are in libraries that Play WS and Akka bundle (shaded) - the Akka flavor's old Play WS lines for Scala 2.12 and Scala 3 have no release with newer ones.

| Artifact | Versions | Advisories | Status | Why |
|---|---|---|---|---|
| `io.netty:netty-common` | 4.1.45.Final, 4.1.60.Final | [GHSA-389x-839f-4rhx](https://osv.dev/vulnerability/GHSA-389x-839f-4rhx), [GHSA-xq3w-v528-46rv](https://osv.dev/vulnerability/GHSA-xq3w-v528-46rv) (CVE-2024-47535, CVE-2025-25193) | **open** | NOT closed. Windows only, local attacker: at start-up Netty reads `C:\etc\os-release`, which a local user may create - a huge one exhausts the JVM's memory. It matters on a Windows host shared with untrusted local users. No Play WS release for Akka on Scala 2.12 or Scala 3 carries a fixed Netty: there, use Scala 2.13 (Play WS 2.2.18) or the Pekko flavor. |
| `io.netty:netty-handler` | 4.1.45.Final | [GHSA-mm9x-g8pc-w292](https://osv.dev/vulnerability/GHSA-mm9x-g8pc-w292) (CVE-2020-11612) | closed in ws-client | Reached by gzip and deflate response bodies. The engine's bounded inflater fails a response whose decompressed size passes its limit (256 MiB by default); what stays unbounded by it is the inflation of ONE network read (at most about a thousand times a 64 KiB read buffer) before the limit is checked. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3 | [GHSA-7grg-jcf7-rpmx](https://osv.dev/vulnerability/GHSA-7grg-jcf7-rpmx) (CVE-2026-85721) | closed in ws-client | Closed in ws-client: where the bundled AsyncHttpClient has no limit, the engine replaces its response inflater with a bounded one (`maxDecompressedResponseSize`, 256 MiB by default). Pinned by the Play provider specs with a gzip bomb. |
| `com.google.protobuf:protobuf-java` | 3.16.1 | [GHSA-4gg5-vx3j-xwc7](https://osv.dev/vulnerability/GHSA-4gg5-vx3j-xwc7), [GHSA-735f-pc8j-v9w8](https://osv.dev/vulnerability/GHSA-735f-pc8j-v9w8), [GHSA-g5ww-5jh7-63cx](https://osv.dev/vulnerability/GHSA-g5ww-5jh7-63cx), [GHSA-h4h5-3hr4-j3g2](https://osv.dev/vulnerability/GHSA-h4h5-3hr4-j3g2) (CVE-2022-3171, CVE-2022-3509, CVE-2022-3510, CVE-2024-7254) | not reachable | Denial of service while PARSING protobuf input. Akka (which shades this protobuf-java in `akka-protobuf-v3`) parses protobuf only when it deserializes messages from another node - remoting, cluster, persistence, stream refs. The engines' actor systems are local: none of those Akka modules is a dependency (this page's lists show it), so no protobuf input is ever parsed. Akka 2.6.21 is the last Akka 2.6 release; the Pekko flavor's protobuf-java (4.33.5) is not affected. |
| `io.netty:netty-codec` | 4.1.45.Final, 4.1.60.Final | [GHSA-3p8m-j85q-pgmj](https://osv.dev/vulnerability/GHSA-3p8m-j85q-pgmj), [GHSA-558v-64gr-wgg4](https://osv.dev/vulnerability/GHSA-558v-64gr-wgg4), [GHSA-9vjp-v76f-g363](https://osv.dev/vulnerability/GHSA-9vjp-v76f-g363), [GHSA-grg4-wf29-r9vv](https://osv.dev/vulnerability/GHSA-grg4-wf29-r9vv), [GHSA-mj4r-2hfc-f8p6](https://osv.dev/vulnerability/GHSA-mj4r-2hfc-f8p6) (CVE-2021-37136, CVE-2021-37137, CVE-2025-58057, CVE-2026-42583, CVE-2026-59901) | not reachable | The Brotli, Bzip2, LZ4 and Snappy decoders are never in the pipeline: only gzip and deflate response bodies are decoded (Netty's zlib decoders). |
| `io.netty:netty-codec-http` | 4.1.45.Final | [GHSA-5mcr-gq6c-3hq2](https://osv.dev/vulnerability/GHSA-5mcr-gq6c-3hq2) (CVE-2021-21290) | not reachable | Server-side code - the multipart request decoder, the CORS handler, the response encoder, WebSocket handshakers, SPDY: a client pipeline has none of it. |
| `io.netty:netty-codec-http` | 4.1.45.Final, 4.1.60.Final | [GHSA-f6hv-jmp6-3vwv](https://osv.dev/vulnerability/GHSA-f6hv-jmp6-3vwv) (CVE-2026-42587) | not reachable | These Netty versions decode only gzip and deflate response bodies (they have no Brotli, Zstandard or Snappy content decoder), and the engine bounds the decompressed size itself. |
| `io.netty:netty-codec-http` | 4.1.45.Final, 4.1.60.Final | [GHSA-57rv-r2g8-2cj3](https://osv.dev/vulnerability/GHSA-57rv-r2g8-2cj3) (CVE-2026-42584) | not reachable | Needs pipelined requests with a `HEAD` among them: AsyncHttpClient sends one request at a time per connection, and the engine sends no `HEAD`. |
| `io.netty:netty-codec-http` | 4.1.45.Final, 4.1.60.Final | [GHSA-84h7-rjj3-6jx4](https://osv.dev/vulnerability/GHSA-84h7-rjj3-6jx4), [GHSA-gcjf-9mgh-3p7g](https://osv.dev/vulnerability/GHSA-gcjf-9mgh-3p7g), [GHSA-v8h7-rr48-vmmv](https://osv.dev/vulnerability/GHSA-v8h7-rr48-vmmv) (CVE-2025-67735, CVE-2026-41417, CVE-2026-59921) | not reachable | No caller-supplied value reaches the request line or a header with a raw line break: query keys and values are percent-encoded, and a URL or header with a line break is refused or encoded before Netty sees it - pinned on every engine by `TestServers.lineBreakInjection`. Netty's `HttpPostRequestEncoder` is not used (multipart bodies are built by ws-client). |
| `io.netty:netty-codec-http` | 4.1.45.Final, 4.1.60.Final | [GHSA-38f8-5428-x5cv](https://osv.dev/vulnerability/GHSA-38f8-5428-x5cv), [GHSA-fghv-69vj-qj49](https://osv.dev/vulnerability/GHSA-fghv-69vj-qj49), [GHSA-hvcg-qmg6-jm4c](https://osv.dev/vulnerability/GHSA-hvcg-qmg6-jm4c), [GHSA-m4cv-j2px-7723](https://osv.dev/vulnerability/GHSA-m4cv-j2px-7723), [GHSA-pwqr-wmgm-9rr8](https://osv.dev/vulnerability/GHSA-pwqr-wmgm-9rr8), [GHSA-wx5j-54mm-rqqq](https://osv.dev/vulnerability/GHSA-wx5j-54mm-rqqq), [GHSA-xxqh-mfjm-7mv9](https://osv.dev/vulnerability/GHSA-xxqh-mfjm-7mv9) (CVE-2021-43797, CVE-2025-58056, CVE-2026-33870, CVE-2026-42580, CVE-2026-42581, CVE-2026-42585, CVE-2026-50020) | not reachable | Lenient parsing of chunk sizes, chunk extensions, `Transfer-Encoding` and control characters: request smuggling against a server or proxy that parses REQUESTS with Netty. The engine parses responses only, on connections pooled per target host - the one peer that can send such a response is the site itself. |
| `io.netty:netty-codec-http` | 4.1.45.Final, 4.1.60.Final | [GHSA-269q-hmxg-m83q](https://osv.dev/vulnerability/GHSA-269q-hmxg-m83q), [GHSA-4mp9-239f-g9hg](https://osv.dev/vulnerability/GHSA-4mp9-239f-g9hg), [GHSA-5jpm-x58v-624v](https://osv.dev/vulnerability/GHSA-5jpm-x58v-624v), [GHSA-6cqp-g7gg-8hr5](https://osv.dev/vulnerability/GHSA-6cqp-g7gg-8hr5), [GHSA-6jqx-86gh-f27w](https://osv.dev/vulnerability/GHSA-6jqx-86gh-f27w), [GHSA-8c42-7qj2-3j46](https://osv.dev/vulnerability/GHSA-8c42-7qj2-3j46), [GHSA-jppx-w49h-x2qq](https://osv.dev/vulnerability/GHSA-jppx-w49h-x2qq), [GHSA-mvh2-crg5-v77c](https://osv.dev/vulnerability/GHSA-mvh2-crg5-v77c), [GHSA-q4f6-jm68-57ww](https://osv.dev/vulnerability/GHSA-q4f6-jm68-57ww) (CVE-2022-24823, CVE-2024-29025, CVE-2026-55831, CVE-2026-55833, CVE-2026-56745, CVE-2026-56746, CVE-2026-59898, CVE-2026-59899, CVE-2026-59903) | not reachable | Server-side code - the multipart request decoder, the CORS handler, the response encoder, WebSocket handshakers, SPDY: a client pipeline has none of it. |
| `io.netty:netty-handler` | 4.1.45.Final, 4.1.60.Final | [GHSA-3qp7-7mw8-wx86](https://osv.dev/vulnerability/GHSA-3qp7-7mw8-wx86), [GHSA-6mjq-h674-j845](https://osv.dev/vulnerability/GHSA-6mjq-h674-j845), [GHSA-c4c3-7fpv-j4q5](https://osv.dev/vulnerability/GHSA-c4c3-7fpv-j4q5), [GHSA-fccg-mwvh-qqg4](https://osv.dev/vulnerability/GHSA-fccg-mwvh-qqg4), [GHSA-x4gw-5cx5-pgmh](https://osv.dev/vulnerability/GHSA-x4gw-5cx5-pgmh) (CVE-2023-34462, CVE-2026-44249, CVE-2026-45416, CVE-2026-75595, CVE-2026-75596) | not reachable | Server-side handlers (`SniHandler`, `IpSubnetFilter`). |
| `io.netty:netty-handler` | 4.1.45.Final, 4.1.60.Final | [GHSA-c653-97m9-rcg9](https://osv.dev/vulnerability/GHSA-c653-97m9-rcg9) (CVE-2026-50010) | not reachable | Needs a caller-supplied plain `X509TrustManager`: the engine supplies none - certificates and host names are checked by the JDK's default trust managers. Pinned by the Play provider specs (an untrusted certificate and a certificate for another host name are rejected). |
| `io.netty:netty-handler-proxy` | 4.1.45.Final, 4.1.60.Final | [GHSA-45q3-82m4-75jr](https://osv.dev/vulnerability/GHSA-45q3-82m4-75jr) (CVE-2026-42578) | not reachable | Netty's `HttpProxyHandler` is not used: AsyncHttpClient opens `CONNECT` tunnels itself, and the engine configures no SOCKS proxy. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3 | [GHSA-f9m8-cv68-674w](https://osv.dev/vulnerability/GHSA-f9m8-cv68-674w), [GHSA-m452-q8c9-rg2f](https://osv.dev/vulnerability/GHSA-m452-q8c9-rg2f), [GHSA-mfj5-cf8g-g2fv](https://osv.dev/vulnerability/GHSA-mfj5-cf8g-g2fv) (CVE-2024-53990, CVE-2026-107280, CVE-2026-55688) | not reachable | The engine runs AsyncHttpClient without a cookie store (`setCookieStore(null)`): no cookie is stored or replayed. Pinned on every engine by `TestServers.cookieIsolation`. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3 | [GHSA-mfj3-87qq-382v](https://osv.dev/vulnerability/GHSA-mfj3-87qq-382v), [GHSA-rqf5-2wxv-rjf4](https://osv.dev/vulnerability/GHSA-rqf5-2wxv-rjf4), [GHSA-xr57-gcx8-52hf](https://osv.dev/vulnerability/GHSA-xr57-gcx8-52hf) (CVE-2026-107231, CVE-2026-107283, CVE-2026-85720) | not reachable | Needs AsyncHttpClient's own logins (a `Realm`: Basic, Digest, NTLM, Kerberos) or proxy credentials: the engine configures neither. A site's credentials are plain request headers, and they never go on the `CONNECT` to a proxy (pinned by `TestServers.connectRequestHead`). |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3 | [GHSA-cmxv-58fp-fm3g](https://osv.dev/vulnerability/GHSA-cmxv-58fp-fm3g), [GHSA-fmxf-pm6p-7xgm](https://osv.dev/vulnerability/GHSA-fmxf-pm6p-7xgm) (CVE-2026-40490, CVE-2026-45300) | not reachable | Redirects are never followed (`setFollowRedirect(false)`, whatever the JVM-wide AsyncHttpClient property says). Pinned by the Play provider specs. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3 | [GHSA-jmqq-x5g9-9p2w](https://osv.dev/vulnerability/GHSA-jmqq-x5g9-9p2w) (CVE-2026-107282) | not reachable | A replay to another host needs a `ResponseFilter` that returns a different request, or a followed redirect: the engine installs no filter and follows no redirect. AsyncHttpClient's own I/O retry replays to the same host, which is not affected. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3 | [GHSA-3wp9-xfwm-rjjf](https://osv.dev/vulnerability/GHSA-3wp9-xfwm-rjjf) (CVE-2026-107285) | not reachable | The engine opens no WebSocket. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3, 2.16.1 | [GHSA-2jwh-9rmr-j4xf](https://osv.dev/vulnerability/GHSA-2jwh-9rmr-j4xf), [GHSA-p2jm-6hj6-9rjg](https://osv.dev/vulnerability/GHSA-p2jm-6hj6-9rjg) (CVE-2026-107226, CVE-2026-107228) | not reachable | The engine runs AsyncHttpClient without a cookie store (`setCookieStore(null)`): no cookie is stored or replayed. Pinned on every engine by `TestServers.cookieIsolation`. |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3, 2.16.1 | [GHSA-v2j5-22fr-j62r](https://osv.dev/vulnerability/GHSA-v2j5-22fr-j62r) (CVE-2026-107230) | not reachable | Needs AsyncHttpClient's own logins (a `Realm`: Basic, Digest, NTLM, Kerberos) or proxy credentials: the engine configures neither. A site's credentials are plain request headers, and they never go on the `CONNECT` to a proxy (pinned by `TestServers.connectRequestHead`). |
| `org.asynchttpclient:async-http-client` | 2.10.5, 2.12.3, 2.16.1 | [GHSA-x8v2-478q-2hvg](https://osv.dev/vulnerability/GHSA-x8v2-478q-2hvg) (CVE-2026-107227) | not reachable | The engine opens no WebSocket. |

## Libraries

| Library | Scala 2.12 | Scala 2.13 | Scala 3 | Declared by |
|---|---|---|---|---|
| `co.fs2:fs2-core` | 3.12.2 | 3.12.2 | 3.12.2 | fs2 |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 | json-repair, core |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 | json-repair, core |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 | json-repair, core |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 | json-repair, core |
| `com.softwaremill.sttp.client4:core` | 4.0.3 | 4.0.3 | - | sttp |
| `com.typesafe.akka:akka-http` | 10.5.3 | 10.5.3 | 10.5.3 | play-akka-stream |
| `com.typesafe.akka:akka-stream` | 2.6.20 | 2.6.21 | 2.6.20 | core-akka |
| `com.typesafe.play:play-ahc-ws-standalone` | 2.1.11 | 2.2.18 | 2.2.0-M2 | play-akka |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 | json-repair, core |
| `com.typesafe.play:play-ws-standalone-json` | 2.1.11 | 2.2.18 | 2.2.0-M2 | play-akka |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 | json-repair, core |
| `com.typesafe:config` | 1.4.3 | 1.4.3 / 1.4.6 | 1.4.3 / 1.4.6 | core |
| `com.typesafe:ssl-config-core` | 0.4.3 | 0.4.3 / 0.6.1 / 0.7.1 | 0.6.1 / 0.7.1 | core-akka |
| `dev.zio:zio-http` | 3.11.6 | 3.11.6 | 3.11.6 | zio-http |
| `dev.zio:zio-interop-reactivestreams` | 2.0.2 | 2.0.2 | 2.0.2 | zio-http |
| `dev.zio:zio-streams` | 2.1.26 | 2.1.26 | 2.1.26 | zio |
| `org.apache.pekko:pekko-http` | - | 1.3.0 | 1.3.0 | pekko-http, play-pekko-stream |
| `org.apache.pekko:pekko-stream` | - | 1.6.0 | 1.6.0 | core-pekko |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | 1.86 | 1.86 | zio-http |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | 1.86 | 1.86 | zio-http |
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | 1.86 | 1.86 | zio-http |
| `org.playframework:play-ahc-ws-standalone` | - | 3.0.14 | 3.0.14 | play-pekko |
| `org.playframework:play-ws-standalone-json` | - | 3.0.14 | 3.0.14 | play-pekko |
| `org.typelevel:cats-effect` | 3.6.3 | 3.6.3 | 3.6.3 | fs2 |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 / 2.13.18 | every module |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 / 3.3.8 | every module |

Bundled inside other jars (shaded or vendored - not visible as dependencies):

| Jar | Its version: 2.12 | 2.13 | 3 | Bundles | 2.12 | 2.13 | 3 |
|---|---|---|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 | `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 |
| `com.typesafe.akka:akka-http-core` | 10.5.3 | 10.5.3 | 10.5.3 | `com.twitter:hpack` | source copy | source copy | source copy |
| `com.typesafe.akka:akka-protobuf-v3` | 2.6.20 | 2.6.21 | 2.6.20 | `com.google.protobuf:protobuf-java` | 3.16.1 | 3.16.1 | 3.16.1 |
| `com.typesafe.play:shaded-asynchttpclient` | 2.1.11 | 2.2.18 | 2.2.0-M2 | Netty | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final |
| `com.typesafe.play:shaded-asynchttpclient` | 2.1.11 | 2.2.18 | 2.2.0-M2 | `org.asynchttpclient:async-http-client` | 2.10.5 | 2.16.1 | 2.12.3 |
| `com.typesafe.play:shaded-oauth` | 2.1.11 | 2.2.18 | 2.2.0-M2 | `commons-codec:commons-codec` | 1.3 | - | - |
| `com.typesafe.play:shaded-oauth` | 2.1.11 | 2.2.18 | 2.2.0-M2 | `oauth.signpost:signpost-core` | 1.2.1.2 | 2.1.1 | 2.1.1 |
| `dev.zio:izumi-reflect-thirdparty-boopickle-shaded` | 3.0.9 | 3.0.9 | 3.0.9 | `io.suzaku:boopickle` | source copy | source copy | source copy |
| `io.netty:netty-common` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final | `org.jctools:jctools-core-jdk11` | 4.0.7 | 4.0.7 | 4.0.7 |
| `io.netty:netty-common` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final | `org.jctools:jctools-core` | 4.0.7 | 4.0.7 | 4.0.7 |
| `org.apache.pekko:pekko-http-core` | - | 1.3.0 | 1.3.0 | `com.twitter:hpack` | - | source copy | source copy |
| `org.apache.pekko:pekko-protobuf-v3` | - | 1.6.0 | 1.6.0 | `com.google.protobuf:protobuf-java` | - | 4.33.5 | 4.33.5 |
| `org.playframework:shaded-asynchttpclient` | - | 3.0.14 | 3.0.14 | Netty | - | 4.1.138.Final | 4.1.138.Final |
| `org.playframework:shaded-asynchttpclient` | - | 3.0.14 | 3.0.14 | `org.asynchttpclient:async-http-client` | - | 2.16.1 | 2.16.1 |
| `org.playframework:shaded-oauth` | - | 3.0.14 | 3.0.14 | `oauth.signpost:signpost-core` | - | 2.1.1 | 2.1.1 |

## Per module

### json-repair

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 | declared |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |

### ws-client-core

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 | declared |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 | declared |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 | declared |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |

### ws-client-core-akka

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `com.typesafe.akka:akka-stream` | 2.6.20 | 2.6.21 | 2.6.20 | declared |
| `com.typesafe:ssl-config-core` | 0.4.3 | 0.4.3 | 0.6.1 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.typesafe.akka:akka-actor` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.akka:akka-protobuf-v3` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.reactivestreams:reactive-streams` | 1.0.3 | 1.0.3 | 1.0.3 |  |
| `org.scala-lang.modules:scala-java8-compat` | 0.8.0 | 1.0.0 | 1.0.0 |  |
| `org.scala-lang.modules:scala-parser-combinators` | 1.1.2 | 1.1.2 | - |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 3.16.1 | 3.16.1 | 3.16.1 | bundled in `akka-protobuf-v3` |

### ws-client-core-pekko

Scala 2.13, 3.

| Artifact | Scala 2.13 | Scala 3 | |
|---|---|---|---|
| `org.apache.pekko:pekko-stream` | 1.6.0 | 1.6.0 | declared |
| `org.scala-lang:scala-library` | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | 3.3.8 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.6 | 1.4.6 |  |
| `com.typesafe:ssl-config-core` | 0.7.1 | 0.7.1 |  |
| `joda-time:joda-time` | 2.10.5 | - |  |
| `org.apache.pekko:pekko-actor` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-protobuf-v3` | 1.6.0 | 1.6.0 |  |
| `org.reactivestreams:reactive-streams` | 1.0.4 | 1.0.4 |  |
| `org.scala-lang:scala-reflect` | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 4.33.5 | 4.33.5 | bundled in `pekko-protobuf-v3` |

### ws-client-fs2

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `co.fs2:fs2-core` | 3.12.2 | 3.12.2 | 3.12.2 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.3.8 | declared |
| `org.typelevel:cats-effect` | 3.6.3 | 3.6.3 | 3.6.3 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.scodec:scodec-bits` | 1.1.38 | 1.1.38 | 1.1.38 |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `org.typelevel:cats-core` | 2.11.0 | 2.11.0 | 2.11.0 |  |
| `org.typelevel:cats-effect-kernel` | 3.6.3 | 3.6.3 | 3.6.3 |  |
| `org.typelevel:cats-effect-std` | 3.6.3 | 3.6.3 | 3.6.3 |  |
| `org.typelevel:cats-kernel` | 2.11.0 | 2.11.0 | 2.11.0 |  |
| `org.typelevel:cats-mtl` | 1.3.1 | 1.3.1 | 1.3.1 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |

### ws-client-jdk

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |

### ws-client-pekko-http

Scala 2.13, 3.

| Artifact | Scala 2.13 | Scala 3 | |
|---|---|---|---|
| `org.apache.pekko:pekko-http` | 1.3.0 | 1.3.0 | declared |
| `org.scala-lang:scala-library` | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | 3.3.8 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.6 | 1.4.6 |  |
| `com.typesafe:ssl-config-core` | 0.7.1 | 0.7.1 |  |
| `joda-time:joda-time` | 2.10.5 | - |  |
| `org.apache.pekko:pekko-actor` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-http-core` | 1.3.0 | 1.3.0 |  |
| `org.apache.pekko:pekko-parsing` | 1.3.0 | 1.3.0 |  |
| `org.apache.pekko:pekko-protobuf-v3` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-stream` | 1.6.0 | 1.6.0 |  |
| `org.parboiled:parboiled` | 2.5.1 | 2.5.1 |  |
| `org.reactivestreams:reactive-streams` | 1.0.4 | 1.0.4 |  |
| `org.scala-lang:scala-reflect` | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 4.33.5 | 4.33.5 | bundled in `pekko-protobuf-v3` |
| `com.twitter:hpack` | source copy | source copy | bundled in `pekko-http-core` |

### ws-client-play-akka

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `com.typesafe.play:play-ahc-ws-standalone` | 2.1.11 | 2.2.18 | 2.2.0-M2 | declared |
| `com.typesafe.play:play-ws-standalone-json` | 2.1.11 | 2.2.18 | 2.2.0-M2 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.typesafe.akka:akka-actor` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.akka:akka-protobuf-v3` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.akka:akka-stream` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.play:cachecontrol` | 2.0.0 | 2.3.2 | 2.2.0 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-ws-standalone` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.play:shaded-asynchttpclient` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.play:shaded-oauth` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `com.typesafe:ssl-config-core` | 0.4.3 | 0.6.1 | 0.6.1 |  |
| `javax.inject:javax.inject` | 1 | 1 | 1 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.reactivestreams:reactive-streams` | 1.0.3 | 1.0.4 | 1.0.4 |  |
| `org.scala-lang.modules:scala-java8-compat` | 0.9.1 | 1.0.0 | 1.0.0 |  |
| `org.scala-lang.modules:scala-parser-combinators` | 1.1.2 | 1.1.2 | 2.1.0 |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 2.0.19 | 2.0.1 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 3.16.1 | 3.16.1 | 3.16.1 | bundled in `akka-protobuf-v3` |
| `commons-codec:commons-codec` | 1.3 | - | - | bundled in `shaded-oauth` |
| `io.netty:netty-buffer` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-http` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-socks` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-common` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler-proxy` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-resolver` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport-native-unix-common` | - | 4.1.138.Final | - | bundled in `shaded-asynchttpclient` |
| `oauth.signpost:signpost-core` | 1.2.1.2 | 2.1.1 | 2.1.1 | bundled in `shaded-oauth` |
| `org.asynchttpclient:async-http-client` | 2.10.5 | 2.16.1 | 2.12.3 | bundled in `shaded-asynchttpclient` |

### ws-client-play-akka-stream

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `com.typesafe.akka:akka-http` | 10.5.3 | 10.5.3 | 10.5.3 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.10 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.2.2 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.typesafe.akka:akka-actor` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.akka:akka-http-core` | 10.5.3 | 10.5.3 | 10.5.3 |  |
| `com.typesafe.akka:akka-parsing` | 10.5.3 | 10.5.3 | 10.5.3 |  |
| `com.typesafe.akka:akka-protobuf-v3` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.akka:akka-stream` | 2.6.20 | 2.6.21 | 2.6.20 |  |
| `com.typesafe.play:cachecontrol` | 2.0.0 | 2.3.2 | 2.2.0 |  |
| `com.typesafe.play:play-ahc-ws-standalone` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-ws-standalone` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.play:play-ws-standalone-json` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.play:shaded-asynchttpclient` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.play:shaded-oauth` | 2.1.11 | 2.2.18 | 2.2.0-M2 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `com.typesafe:ssl-config-core` | 0.4.3 | 0.6.1 | 0.6.1 |  |
| `javax.inject:javax.inject` | 1 | 1 | 1 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.reactivestreams:reactive-streams` | 1.0.3 | 1.0.4 | 1.0.4 |  |
| `org.scala-lang.modules:scala-java8-compat` | 0.9.1 | 1.0.0 | 1.0.0 |  |
| `org.scala-lang.modules:scala-parser-combinators` | 1.1.2 | 1.1.2 | 2.1.0 |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 2.0.19 | 2.0.1 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 3.16.1 | 3.16.1 | 3.16.1 | bundled in `akka-protobuf-v3` |
| `com.twitter:hpack` | source copy | source copy | source copy | bundled in `akka-http-core` |
| `commons-codec:commons-codec` | 1.3 | - | - | bundled in `shaded-oauth` |
| `io.netty:netty-buffer` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-http` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-socks` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-common` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler-proxy` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-resolver` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport` | 4.1.45.Final | 4.1.138.Final | 4.1.60.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport-native-unix-common` | - | 4.1.138.Final | - | bundled in `shaded-asynchttpclient` |
| `oauth.signpost:signpost-core` | 1.2.1.2 | 2.1.1 | 2.1.1 | bundled in `shaded-oauth` |
| `org.asynchttpclient:async-http-client` | 2.10.5 | 2.16.1 | 2.12.3 | bundled in `shaded-asynchttpclient` |

### ws-client-play-pekko

Scala 2.13, 3.

| Artifact | Scala 2.13 | Scala 3 | |
|---|---|---|---|
| `org.playframework:play-ahc-ws-standalone` | 3.0.14 | 3.0.14 | declared |
| `org.playframework:play-ws-standalone-json` | 3.0.14 | 3.0.14 | declared |
| `org.scala-lang:scala-library` | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | 3.3.8 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.6 | 1.4.6 |  |
| `com.typesafe:ssl-config-core` | 0.7.1 | 0.7.1 |  |
| `javax.inject:javax.inject` | 1 | 1 |  |
| `joda-time:joda-time` | 2.10.5 | - |  |
| `org.apache.pekko:pekko-actor` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-protobuf-v3` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-stream` | 1.6.0 | 1.6.0 |  |
| `org.playframework:cachecontrol` | 3.0.1 | 3.0.1 |  |
| `org.playframework:play-ws-standalone` | 3.0.14 | 3.0.14 |  |
| `org.playframework:shaded-asynchttpclient` | 3.0.14 | 3.0.14 |  |
| `org.playframework:shaded-oauth` | 3.0.14 | 3.0.14 |  |
| `org.reactivestreams:reactive-streams` | 1.0.4 | 1.0.4 |  |
| `org.scala-lang.modules:scala-parser-combinators` | 1.1.2 | 2.3.0 |  |
| `org.scala-lang:scala-reflect` | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 2.0.19 | 2.0.19 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 4.33.5 | 4.33.5 | bundled in `pekko-protobuf-v3` |
| `io.netty:netty-buffer` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-http` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-socks` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-common` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler-proxy` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-resolver` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport-native-unix-common` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `oauth.signpost:signpost-core` | 2.1.1 | 2.1.1 | bundled in `shaded-oauth` |
| `org.asynchttpclient:async-http-client` | 2.16.1 | 2.16.1 | bundled in `shaded-asynchttpclient` |

### ws-client-play-pekko-stream

Scala 2.13, 3.

| Artifact | Scala 2.13 | Scala 3 | |
|---|---|---|---|
| `org.apache.pekko:pekko-http` | 1.3.0 | 1.3.0 | declared |
| `org.scala-lang:scala-library` | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | 3.3.8 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.6 | 1.4.6 |  |
| `com.typesafe:ssl-config-core` | 0.7.1 | 0.7.1 |  |
| `javax.inject:javax.inject` | 1 | 1 |  |
| `joda-time:joda-time` | 2.10.5 | - |  |
| `org.apache.pekko:pekko-actor` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-http-core` | 1.3.0 | 1.3.0 |  |
| `org.apache.pekko:pekko-parsing` | 1.3.0 | 1.3.0 |  |
| `org.apache.pekko:pekko-protobuf-v3` | 1.6.0 | 1.6.0 |  |
| `org.apache.pekko:pekko-stream` | 1.6.0 | 1.6.0 |  |
| `org.parboiled:parboiled` | 2.5.1 | 2.5.1 |  |
| `org.playframework:cachecontrol` | 3.0.1 | 3.0.1 |  |
| `org.playframework:play-ahc-ws-standalone` | 3.0.14 | 3.0.14 |  |
| `org.playframework:play-ws-standalone` | 3.0.14 | 3.0.14 |  |
| `org.playframework:play-ws-standalone-json` | 3.0.14 | 3.0.14 |  |
| `org.playframework:shaded-asynchttpclient` | 3.0.14 | 3.0.14 |  |
| `org.playframework:shaded-oauth` | 3.0.14 | 3.0.14 |  |
| `org.reactivestreams:reactive-streams` | 1.0.4 | 1.0.4 |  |
| `org.scala-lang.modules:scala-parser-combinators` | 1.1.2 | 2.3.0 |  |
| `org.scala-lang:scala-reflect` | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 2.0.19 | 2.0.19 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `com.google.protobuf:protobuf-java` | 4.33.5 | 4.33.5 | bundled in `pekko-protobuf-v3` |
| `com.twitter:hpack` | source copy | source copy | bundled in `pekko-http-core` |
| `io.netty:netty-buffer` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-http` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-codec-socks` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-common` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-handler-proxy` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-resolver` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `io.netty:netty-transport-native-unix-common` | 4.1.138.Final | 4.1.138.Final | bundled in `shaded-asynchttpclient` |
| `oauth.signpost:signpost-core` | 2.1.1 | 2.1.1 | bundled in `shaded-oauth` |
| `org.asynchttpclient:async-http-client` | 2.16.1 | 2.16.1 | bundled in `shaded-asynchttpclient` |

### ws-client-sttp

Scala 2.12, 2.13.

| Artifact | Scala 2.12 | Scala 2.13 | |
|---|---|---|---|
| `com.softwaremill.sttp.client4:core` | 4.0.3 | 4.0.3 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 |  |
| `com.softwaremill.sttp.model:core` | 1.7.13 | 1.7.13 |  |
| `com.softwaremill.sttp.shared:core` | 1.5.0 | 1.5.0 |  |
| `com.softwaremill.sttp.shared:ws` | 1.5.0 | 1.5.0 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | bundled in `jackson-core` |

### ws-client-zio

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `dev.zio:zio-streams` | 2.1.26 | 2.1.26 | 2.1.26 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.3.8 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `dev.zio:izumi-reflect` | 3.0.9 | 3.0.9 | 3.0.9 |  |
| `dev.zio:izumi-reflect-thirdparty-boopickle-shaded` | 3.0.9 | 3.0.9 | 3.0.9 |  |
| `dev.zio:zio` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `dev.zio:zio-internal-macros` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `dev.zio:zio-stacktracer` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.scala-lang.modules:scala-collection-compat` | 2.14.0 | 2.14.0 | 2.14.0 |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `io.suzaku:boopickle` | source copy | source copy | source copy | bundled in `izumi-reflect-thirdparty-boopickle-shaded` |

### ws-client-zio-http

Scala 2.12, 2.13, 3.

| Artifact | Scala 2.12 | Scala 2.13 | Scala 3 | |
|---|---|---|---|---|
| `dev.zio:zio-http` | 3.11.6 | 3.11.6 | 3.11.6 | declared |
| `dev.zio:zio-interop-reactivestreams` | 2.0.2 | 2.0.2 | 2.0.2 | declared |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | 1.86 | 1.86 | declared |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | 1.86 | 1.86 | declared |
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | 1.86 | 1.86 | declared |
| `org.scala-lang:scala-library` | 2.12.18 | 2.13.18 | 2.13.18 | declared |
| `org.scala-lang:scala3-library` | - | - | 3.3.8 | declared |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | 2.22 | 2.22 |  |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | 2.22.3 | 2.22.3 |  |
| `com.lihaoyi:unroll-annotation` | 0.1.12 | 0.1.12 | 0.1.12 |  |
| `com.softwaremill.magnolia1_2:magnolia` | 1.1.14 | 1.1.14 | - |  |
| `com.typesafe.play:play-functional` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.play:play-json` | 2.8.2 | 2.8.2 | 2.10.0-RC6 |  |
| `com.typesafe.scala-logging:scala-logging` | 3.9.5 | 3.9.5 | 3.9.5 |  |
| `com.typesafe:config` | 1.4.3 | 1.4.3 | 1.4.3 |  |
| `dev.zio:izumi-reflect` | 3.0.9 | 3.0.9 | 3.0.9 |  |
| `dev.zio:izumi-reflect-thirdparty-boopickle-shaded` | 3.0.9 | 3.0.9 | 3.0.9 |  |
| `dev.zio:zio` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `dev.zio:zio-constraintless` | 0.3.3 | 0.3.3 | 0.3.3 |  |
| `dev.zio:zio-internal-macros` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `dev.zio:zio-json` | 1.0.0 | 1.0.0 | 1.0.0 |  |
| `dev.zio:zio-prelude` | 1.0.0-RC48 | 1.0.0-RC48 | 1.0.0-RC48 |  |
| `dev.zio:zio-prelude-macros` | 1.0.0-RC48 | 1.0.0-RC48 | 1.0.0-RC48 |  |
| `dev.zio:zio-schema` | 1.8.7 | 1.8.7 | 1.8.7 |  |
| `dev.zio:zio-schema-derivation` | 1.8.7 | 1.8.7 | 1.8.7 |  |
| `dev.zio:zio-schema-json` | 1.8.7 | 1.8.7 | 1.8.7 |  |
| `dev.zio:zio-schema-macros` | 1.8.7 | 1.8.7 | 1.8.7 |  |
| `dev.zio:zio-schema-protobuf` | 1.8.7 | 1.8.7 | 1.8.7 |  |
| `dev.zio:zio-stacktracer` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `dev.zio:zio-streams` | 2.1.26 | 2.1.26 | 2.1.26 |  |
| `io.netty:netty-buffer` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-codec-base` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-codec-compression` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-codec-http` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-codec-socks` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-common` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-handler` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-handler-proxy` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-pkitesting` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-resolver` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-transport` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-transport-classes-epoll` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-transport-classes-kqueue` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-transport-native-epoll` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-transport-native-kqueue` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `io.netty:netty-transport-native-unix-common` | 4.2.18.Final | 4.2.18.Final | 4.2.18.Final |  |
| `joda-time:joda-time` | 2.10.5 | 2.10.5 | - |  |
| `org.reactivestreams:reactive-streams` | 1.0.4 | 1.0.4 | 1.0.4 |  |
| `org.scala-lang.modules:scala-collection-compat` | 2.14.0 | 2.14.0 | 2.14.0 |  |
| `org.scala-lang:scala-reflect` | 2.12.18 | 2.13.18 | - |  |
| `org.slf4j:slf4j-api` | 1.7.36 | 1.7.36 | 1.7.36 |  |
| `ch.randelshofer:fastdoubleparser` | 2.0.1 | 2.0.1 | 2.0.1 | bundled in `jackson-core` |
| `io.suzaku:boopickle` | source copy | source copy | source copy | bundled in `izumi-reflect-thirdparty-boopickle-shaded` |
| `org.jctools:jctools-core` | 4.0.7 | 4.0.7 | 4.0.7 | bundled in `netty-common` |
| `org.jctools:jctools-core-jdk11` | 4.0.7 | 4.0.7 | 4.0.7 | bundled in `netty-common` |
