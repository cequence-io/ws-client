package io.cequence.wsclient.service.spi

import io.cequence.wsclient.domain.CequenceWSException
import io.cequence.wsclient.service.{
  WSClientEngine,
  WSClientInputStreamExtraAkka,
  WSClientOutputStreamExtraAkka
}

/**
 * Typed engine discovery for the Akka/Pekko `Source`-typed streaming traits. The Akka-free
 * core's [[WSClientEngineRegistry]] cannot mention those flavored types - it offers the
 * backend-agnostic `outputStreamed` (returning `WSClientEngine with WSClientOutputStreamCore`,
 * `Flow.Publisher`-typed) instead; this registry filters providers by their streaming
 * capabilities and returns the flavored, `Source`-typed engine.
 *
 * Uses the same resolution order as [[WSClientEngineRegistry]] (explicit id, then config key,
 * then auto-selection by priority) restricted to capable providers.
 */
object StreamedEngineRegistry {

  /**
   * An engine that can stream responses (SSE/JSON) - e.g. for `execJsonStream`.
   */
  def outputStreamed(
    settings: TransportSettings = TransportSettings(),
    engineId: Option[String] = None
  ): WSClientEngine with WSClientOutputStreamExtraAkka =
    newEngine(settings, engineId, EngineCapability.OutputStreaming) match {
      case engine: WSClientOutputStreamExtraAkka => engine
      case engine =>
        engine.close()
        throw new CequenceWSException(
          s"The resolved WS client engine (${engine.getClass.getName}) advertises OutputStreaming " +
            "but does not implement the Source-typed WSClientOutputStreamExtraAkka - it is a " +
            "backend-agnostic (Flow.Publisher-only) streaming engine. Use " +
            "WSClientEngineRegistry.outputStreamed instead, or select a flavored engine " +
            "explicitly (e.g. engineId = Some(\"play-akka-stream\"))."
        )
    }

  /**
   * An engine that can stream a request body (byte source) - e.g. for `execPOSTSource`.
   */
  def inputStreamed(
    settings: TransportSettings = TransportSettings(),
    engineId: Option[String] = None
  ): WSClientEngine with WSClientInputStreamExtraAkka =
    newEngine(settings, engineId, EngineCapability.InputStreaming) match {
      case engine: WSClientInputStreamExtraAkka => engine
      case engine =>
        engine.close()
        throw new CequenceWSException(
          "The resolved WS client engine advertises InputStreaming but does not implement WSClientInputStreamExtraAkka."
        )
    }

  private def newEngine(
    settings: TransportSettings,
    engineId: Option[String],
    capability: EngineCapability
  ): WSClientEngine =
    WSClientEngineRegistry.provider(engineId, Set(capability)).newEngine(settings)
}
