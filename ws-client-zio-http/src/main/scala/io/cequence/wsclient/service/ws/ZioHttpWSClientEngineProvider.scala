package io.cequence.wsclient.service.ws

import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineProvider
}

import scala.concurrent.ExecutionContext

/**
 * ServiceLoader-discovered provider for the zio-http (Netty) backend.
 *
 * `newEngine` builds an engine that OWNS its client stack - a Netty driver (event loops, DNS
 * resolver) plus the client's connection pool - released by `engine.close()`. To run on a ZIO
 * application's own `Client` and runtime instead, use `ZioHttpWSClientEngine(...)` or
 * `ZioHttpWSClientEngine.layer(...)`.
 *
 * Output streaming is provided through the backend-agnostic `WSClientOutputStreamCore`
 * (`java.util.concurrent.Flow`) contract - discover it via
 * `WSClientEngineRegistry.outputStreamed` (the flavored akka/pekko `StreamedEngineRegistry`
 * requires their `Source`-typed traits).
 */
class ZioHttpWSClientEngineProvider extends WSClientEngineProvider {

  override val engineId = "zio-http"

  // output streaming but no input streaming: below pekko-http (15), above the akka stack
  override val priority = 13

  override val capabilities: Set[EngineCapability] =
    Set(EngineCapability.Multipart, EngineCapability.OutputStreaming)

  override def newEngine(settings: TransportSettings): WSClientEngine = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    ZioHttpWSClientEngine.owned(settings)
  }
}
