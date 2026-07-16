package io.cequence.wsclient.service.ws.stream

import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineProvider
}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import java.util.concurrent.atomic.AtomicInteger

/**
 * ServiceLoader-discovered provider for the Play WS (Pekko) streaming backend. A strict
 * superset of the `play-pekko` engine - same base implementation plus SSE/JSON output
 * streaming - hence the slightly higher priority.
 *
 * Handwritten twin of `PlayAkkaStreamWSClientEngineProvider` (in ws-client-play-akka-stream) -
 * kept out of the source generation because the engine id, priority, and class name differ.
 *
 * `newEngine` creates a dedicated actor system and builds a SITE-STATELESS
 * [[PlayWSStreamClientEngine]] that owns it (`ownedSystem = Some(system)`), so
 * `engine.close()` shuts down both the underlying HTTP client and the actor system it created
 * \- preserving the self-contained-engine semantics of the discovery path. One engine serves
 * any number of sites/providers: every call takes a
 * [[io.cequence.wsclient.domain.SiteBinding]]. To supply your own materializer / execution
 * context instead, use `PlayWSStreamClientEngine(...)` directly.
 *
 * The engine is also given `newDaemonSystem` as its `newExecEnv` factory, so
 * `engine.copy(reuseExecContext = false)` can spin up further independent, equally daemonic
 * systems.
 */
class PlayPekkoStreamWSClientEngineProvider extends WSClientEngineProvider {

  override val engineId = "play-pekko-stream"

  override val priority = 21

  override val capabilities: Set[EngineCapability] =
    Set(
      EngineCapability.InputStreaming,
      EngineCapability.OutputStreaming,
      EngineCapability.Multipart
    )

  // engine-OWNED systems must never hold the JVM hostage: with the default pekko.daemonic =
  // off, a leaked (unclosed) engine would block JVM exit via its non-daemon dispatcher
  // threads. Explicit close() still terminates cleanly; the fallback keeps the application's
  // own config applicable to this system. Also used as the `newExecEnv` factory so
  // `copy(reuseExecContext = false)` can spin up further independent, equally daemonic
  // systems.
  def newDaemonSystem(): ActorSystem = ActorSystem(
    s"ws-client-$engineId-${PlayPekkoStreamWSClientEngineProvider.systemCounter.getAndIncrement()}",
    com.typesafe.config.ConfigFactory
      .parseString("pekko.daemonic = on")
      .withFallback(com.typesafe.config.ConfigFactory.load())
  )

  override def newEngine(settings: TransportSettings): WSClientEngine = {
    val system = newDaemonSystem()

    new PlayWSStreamClientEngine(
      settings,
      ownedSystem = Some(system),
      newExecEnv = Some(() => newDaemonSystem())
    )(
      Materializer(system),
      system.dispatcher
    )
  }
}

object PlayPekkoStreamWSClientEngineProvider {
  private val systemCounter = new AtomicInteger(0)
}
