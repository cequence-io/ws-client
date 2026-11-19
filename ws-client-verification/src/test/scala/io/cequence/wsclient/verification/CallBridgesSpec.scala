package io.cequence.wsclient.verification

import io.cequence.wsclient.domain.SiteBinding
import io.cequence.wsclient.service.spi.{TransportSettings, WSClientEngineRegistry}
import io.cequence.wsclient.stream.CallDiagnostics
import io.cequence.wsclient.stream.ZioInterop._
import io.cequence.wsclient.testkit.TestServers.withEchoServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * The bridges of an engine call (AsyncCall, since 1.3.0) per engine and consumer: a call
 * consumed by its own backend costs none - the zio-http engine's call compiled for ZIO, a
 * `Future`-based engine's call run as a `Future` - and a foreign consumer one.
 */
class CallBridgesSpec extends AnyWordSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def runTask[A](task: zio.Task[A]): A =
    zio.Unsafe.unsafe { implicit unsafe =>
      Await.result(zio.Runtime.default.unsafe.runToFuture(task), 30.seconds)
    }

  // (bridges with toZIO, bridges with toFuture) of a GET call, mapped
  private def bridges(engineId: String): (Long, Long) =
    withEchoServer { port =>
      val engine = WSClientEngineRegistry(TransportSettings(), Some(engineId))
      try {
        val call = engine
          .execGETRichCall(SiteBinding(s"http://localhost:$port"), "echo")
          .map(_.status.code)

        val (task, zioBridges) = CallDiagnostics.counted(call.toZIO)
        runTask(task) shouldBe 200

        val (future, futureBridges) = CallDiagnostics.counted(call.toFuture)
        Await.result(future, 30.seconds) shouldBe 200

        (zioBridges.get, futureBridges.get)
      } finally engine.close()
    }

  "an engine call" should {

    "cost no bridge on ZIO and one as a Future on the zio-http engine" in {
      bridges("zio-http") shouldBe ((0L, 1L))
    }

    "cost one bridge on ZIO and none as a Future on the jdk engine" in {
      bridges("jdk") shouldBe ((1L, 0L))
    }
  }
}
