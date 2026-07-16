package io.cequence.wsclient.service.ws

import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineProvider
}

import scala.concurrent.ExecutionContext

/**
 * ServiceLoader-discovered provider for the sttp (client4) backend.
 *
 * Creates engines over sttp's JDK-based `HttpClientFutureBackend`, owned by the engine and
 * closed on `close()`. To use a different sttp backend (OkHttp, Armeria, Pekko-HTTP, ...)
 * construct the engine explicitly via `SttpWSClientEngine.apply` with your backend in scope.
 */
class SttpWSClientEngineProvider extends WSClientEngineProvider {

  override val engineId = "sttp"

  override val priority = 5

  override val capabilities: Set[EngineCapability] = Set(EngineCapability.Multipart)

  override def newEngine(settings: TransportSettings): WSClientEngine = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    new SttpWSClientEngine(SttpWSClientEngine.buildBackend(settings), settings)
  }
}
