package io.cequence.wsclient.service.spi

/**
 * Optional features a discovered engine may support beyond the core
 * [[io.cequence.wsclient.service.WSClientEngine]] contract.
 */
sealed trait EngineCapability

object EngineCapability {

  /**
   * The engine can post a byte-stream body - it mixes in
   * `WSClientInputStreamExtraAkka`/`-Pekko` (`execPOSTSource`).
   */
  case object InputStreaming extends EngineCapability

  /**
   * The engine can stream responses (SSE/JSON). Every such engine implements the
   * backend-agnostic [[io.cequence.wsclient.service.WSClientOutputStreamCore]]
   * (`Flow.Publisher`-typed; discover via `WSClientEngineRegistry.outputStreamed`). The
   * akka/pekko engines ADDITIONALLY implement the `Source`-typed
   * `WSClientOutputStreamExtraAkka`/`-Pekko` required by the flavored `StreamedEngineRegistry`
   * \- a Core-only engine (e.g. `jdk`) truthfully advertises this capability yet is rejected
   * by the flavored registry.
   */
  case object OutputStreaming extends EngineCapability

  /**
   * The engine supports multipart/form-data requests. All bundled engines declare it, so today
   * it never discriminates between them - it exists for third-party providers of
   * multipart-less backends (and for callers guarding against such engines via the
   * capability-filtered lookups).
   */
  case object Multipart extends EngineCapability
}
