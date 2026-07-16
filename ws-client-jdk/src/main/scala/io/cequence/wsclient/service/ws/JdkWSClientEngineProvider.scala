package io.cequence.wsclient.service.ws

import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineProvider
}

import scala.concurrent.ExecutionContext

/**
 * ServiceLoader-discovered provider for the dependency-free JDK HttpClient backend.
 *
 * Lowest priority - it acts as the fallback engine when no Akka/Pekko-based backend is on the
 * classpath. Output streaming is provided through the backend-agnostic
 * `WSClientOutputStreamCore` (`java.util.concurrent.Flow`) contract - note that the flavored
 * `StreamedEngineRegistry` in the akka/pekko modules requires
 * `WSClientOutputStreamExtraAkka`/`-Pekko` and will reject this engine; use
 * `WSClientEngineRegistry.outputStreamed` instead. No input streaming; multipart bodies are
 * materialized in memory.
 */
class JdkWSClientEngineProvider extends WSClientEngineProvider {

  override val engineId = "jdk"

  override val priority = 0

  override val capabilities: Set[EngineCapability] =
    Set(EngineCapability.Multipart, EngineCapability.OutputStreaming)

  override def newEngine(settings: TransportSettings): WSClientEngine = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    JdkWSClientEngine(settings)
  }
}
