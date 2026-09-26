package io.cequence.wsclient.service.spi

import io.cequence.wsclient.service.ws.Timeouts

/**
 * Client-level (site-agnostic) settings for creating a
 * [[io.cequence.wsclient.service.WSClientEngine]] - the HTTP client/backend and its execution
 * environment. Everything here is captured ONCE, when the engine (or its lazy client) is
 * created, and is shared by all sites served through the engine; per-site data lives in
 * [[io.cequence.wsclient.domain.SiteBinding]] /
 * [[io.cequence.wsclient.domain.WsRequestContext]] instead.
 *
 * How the backends honor the timeouts:
 *   - `connectTimeout`: client-level everywhere (Play/AHC, pekko-http pool, jdk client, sttp
 *     backend, zio-http client)
 *   - `pooledConnectionIdleTimeout`: Play/AHC; zio-http uses it as the idle TTL of its dynamic
 *     connection pool; ignored by the other backends
 *   - `readTimeout`: Play/AHC client-level; pekko-http applies it per request (`toStrict`);
 *     zio-http as a read-idle timeout on the channel (no bytes within the window - also bounds
 *     the gap between SSE events); ignored by jdk and sttp
 *   - `requestTimeout`: Play/AHC client-level (bounds the WHOLE exchange incl. streaming);
 *     applied per request by pekko-http, jdk, sttp and zio-http (zio-http: the whole exchange
 *     for plain calls, but only the wait for the response headers for streams)
 *
 * Timeouts are ENGINE-level by design - deliberately not overridable per site, so engines stay
 * fully stateless (no per-configuration client caches). A service that genuinely needs
 * different timeouts uses its own engine.
 *
 * @param timeouts
 *   connect/read/request/pooled-idle timeouts baked into the client/backend (see above)
 * @param proxyURL
 *   proxy to route requests through - accepted forms are "host:port" and
 *   "http(s)://host:port". Honored by the Play, jdk, sttp and zio-http backends (zio-http
 *   tunnels via `CONNECT` and still resolves the TARGET host through local DNS); the
 *   pekko-http backend ignores it with a warning. A proxy is a property of the client: all
 *   sites served through one engine go through the same proxy.
 */
final case class TransportSettings(
  timeouts: Timeouts = Timeouts(),
  proxyURL: Option[String] = None
)
