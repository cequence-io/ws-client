package io.cequence.wsclient.service.ws.stream

import akka.actor.ActorSystem
import akka.stream.Materializer
import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineProvider
}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext

/**
 * ServiceLoader-discovered provider for the Play WS (Akka) streaming backend. A strict
 * superset of the `play-akka` engine - same base implementation plus SSE/JSON output streaming
 * \- hence the slightly higher priority.
 *
 * The created engine owns a dedicated actor system; its `close()` shuts down both the
 * underlying HTTP client and the actor system. To supply your own materializer / execution
 * context use `PlayWSStreamClientEngine.apply` directly instead.
 */
class PlayAkkaStreamWSClientEngineProvider extends WSClientEngineProvider {

  override val engineId = "play-akka-stream"

  override val priority = 11

  override val capabilities: Set[EngineCapability] =
    Set(
      EngineCapability.InputStreaming,
      EngineCapability.OutputStreaming,
      EngineCapability.Multipart
    )

  override def newEngine(settings: TransportSettings): WSClientEngine = {
    val system = newDaemonSystem()

    implicit val materializer: Materializer = Materializer(system)
    implicit val ec: ExecutionContext = system.dispatcher

    new PlayWSStreamClientEngine(
      settings,
      ownedSystem = Some(system),
      newExecEnv = Some(() => newDaemonSystem())
    )
  }

  // handwritten HERE (not in the engine class, which is auto-translated to pekko) because the
  // "akka.daemonic = on" config literal must never pass through the akka -> pekko string
  // rewrite; the pekko twin hand-rolls its own copy of this with "pekko.daemonic = on"
  private def newDaemonSystem(): ActorSystem =
    ActorSystem(
      s"ws-client-$engineId-${PlayAkkaStreamWSClientEngineProvider.systemCounter.getAndIncrement()}",
      // engine-OWNED systems must never hold the JVM hostage: with the default
      // akka.daemonic = off, a leaked (unclosed) engine would block JVM exit via its
      // non-daemon dispatcher threads. Explicit close() still terminates cleanly; the
      // fallback keeps the application's own config applicable to this system.
      com.typesafe.config.ConfigFactory
        .parseString("akka.daemonic = on")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )
}

object PlayAkkaStreamWSClientEngineProvider {
  private val systemCounter = new AtomicInteger(0)
}
