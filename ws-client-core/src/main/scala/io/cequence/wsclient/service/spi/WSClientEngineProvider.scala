package io.cequence.wsclient.service.spi

import io.cequence.wsclient.service.WSClientEngine

/**
 * SPI for WS client engine backends, discovered via [[java.util.ServiceLoader]].
 *
 * Implementations must be public, have a public no-arg constructor, and be registered in a
 * resource file `META-INF/services/io.cequence.wsclient.service.spi.WSClientEngineProvider`
 * containing the implementation's fully qualified class name.
 *
 * Use [[WSClientEngineRegistry]] to look up providers and create engines.
 */
trait WSClientEngineProvider {

  /**
   * Unique, stable engine id, e.g. "play-akka" or "play-pekko".
   */
  def engineId: String

  /**
   * Higher wins when several providers are present and no engine id is requested explicitly.
   * Equal priorities fail auto-selection (fast, with a message naming the candidates).
   *
   * Policy for the bundled engines (0-21): richer/newer stacks rank higher, and a stream
   * engine ranks exactly one above the base engine it supersedes: `play-pekko-stream` (21) >
   * `play-pekko` (20) > `pekko-http` (15) > `play-akka-stream` (11) > `play-akka` (10) >
   * `sttp` (5) > `jdk` (0). Third-party providers that want to win auto-selection over any
   * bundled engine should use 100 or higher.
   */
  def priority: Int = 0

  /**
   * Features supported by the created engines beyond the core [[WSClientEngine]] contract.
   */
  def capabilities: Set[EngineCapability] = Set.empty

  /**
   * Creates a new SITE-STATELESS engine - the HTTP client/backend plus an owned execution
   * environment (actor system / materializer / thread pools), configured by the client-level
   * [[TransportSettings]] (timeouts, proxy). The environment is typically created eagerly,
   * here, so treat engine creation as expensive and REUSE engines - one engine serves any
   * number of sites/providers (every engine call takes a
   * [[io.cequence.wsclient.domain.SiteBinding]]). `engine.close()` must release the
   * environment and be idempotent. Callers who want to supply their own execution environment
   * should use the backend's explicit factory (e.g. `PlayWSClientEngine.apply`) instead.
   */
  def newEngine(settings: TransportSettings): WSClientEngine
}
