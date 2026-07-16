package io.cequence.wsclient.service.ws

import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineProvider
}
import org.apache.pekko.actor.ActorSystem

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext

/**
 * ServiceLoader-discovered provider for the direct pekko-http backend.
 *
 * `newEngine` creates a dedicated actor system and builds a SITE-STATELESS
 * [[PekkoHttpWSClientEngine]] that owns it (`ownsSystem = true`), so `engine.close()` shuts
 * down the connection pools and terminates the system - preserving the self-contained-engine
 * semantics of the discovery path. One engine serves any number of sites/providers: every call
 * takes a [[io.cequence.wsclient.domain.SiteBinding]]. To supply your own actor system
 * instead, use `PekkoHttpWSClientEngine(...)` directly.
 *
 * The engine is also given `newDaemonSystem` as its `newExecEnv` factory, so
 * `engine.copy(reuseExecContext = false)` can spin up further independent, equally daemonic
 * systems.
 */
class PekkoHttpWSClientEngineProvider extends WSClientEngineProvider {

  override val engineId = "pekko-http"

  override val priority = 15

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
    s"ws-client-$engineId-${PekkoHttpWSClientEngineProvider.systemCounter.getAndIncrement()}",
    com.typesafe.config.ConfigFactory
      .parseString("pekko.daemonic = on")
      .withFallback(com.typesafe.config.ConfigFactory.load())
  )

  override def newEngine(settings: TransportSettings): WSClientEngine = {
    implicit val system: ActorSystem = newDaemonSystem()
    implicit val ec: ExecutionContext = system.dispatcher

    new PekkoHttpWSClientEngine(
      settings,
      ownsSystem = true,
      newExecEnv = Some(() => newDaemonSystem())
    )
  }
}

object PekkoHttpWSClientEngineProvider {
  private val systemCounter = new AtomicInteger(0)
}
