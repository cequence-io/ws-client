package io.cequence.wsclient.service.ws.stream

import io.cequence.wsclient.domain.SiteBinding
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  StreamedEngineRegistry,
  TransportSettings,
  WSClientEngineRegistry
}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import io.cequence.wsclient.testkit.LatchedSubscriber
import io.cequence.wsclient.testkit.TestServers.{echo, respond, sse, withServer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import scala.concurrent.Await
import scala.concurrent.duration._

class PlayPekkoStreamWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-pekko-stream"
  private val expectedBaseEngineId = "play-pekko"

  private val sseBody = "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n"

  s"$expectedEngineId provider" should {

    "be discovered alongside the base engine and win auto-selection" in {
      val providers = WSClientEngineRegistry.providers

      providers.map(_.engineId) shouldBe Seq(expectedEngineId, expectedBaseEngineId)
      providers.head.capabilities shouldBe Set(
        EngineCapability.InputStreaming,
        EngineCapability.OutputStreaming,
        EngineCapability.Multipart
      )

      WSClientEngineRegistry.provider().engineId shouldBe expectedEngineId
    }

    "stream SSE JSON events via StreamedEngineRegistry and close idempotently" in {
      withServer("/events" -> sse(sseBody)) { port =>
        val runSystem = ActorSystem("stream-spec-runner")
        try {
          val engine = StreamedEngineRegistry.outputStreamed(
            TransportSettings(),
            Some(expectedEngineId)
          )
          val site = SiteBinding(s"http://localhost:${port}")

          val events = Await.result(
            engine
              .execJsonStream(site, "events", "GET")
              .runWith(Sink.seq)(org.apache.pekko.stream.Materializer(runSystem)),
            30.seconds
          )

          events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))

          engine.close()
          noException should be thrownBy engine.close()
        } finally {
          runSystem.terminate()
          ()
        }
      }
    }

    "classify a stream's non-2xx status through the service's handleErrorCodes" in {
      withServer("/events" -> { exchange =>
        respond(exchange, 429, """{"error":"slow down"}""")
      }) { port =>
        val runSystem = org.apache.pekko.actor.ActorSystem("stream-classify-spec-runner")
        try {
          val streamingEngine =
            io.cequence.wsclient.service.spi.StreamedEngineRegistry.outputStreamed(
              TransportSettings(),
              Some(expectedEngineId)
            )
          val service = new StreamingTestService(
            streamingEngine,
            SiteBinding(s"http://localhost:$port")
          )

          val failure = Await.result(
            service
              .execJsonStream("events", "POST")
              .runWith(org.apache.pekko.stream.scaladsl.Sink.seq)(
                org.apache.pekko.stream.Materializer(runSystem)
              )
              .failed,
            30.seconds
          )

          failure shouldBe a[RateLimitedTestException]
          failure.getMessage shouldBe """429: {"error":"slow down"}"""

          streamingEngine.close()
        } finally {
          runSystem.terminate()
          ()
        }
      }
    }

    "fail a stream on a non-2xx status instead of parsing the error page" in {
      withServer("/events" -> { exchange =>
        respond(exchange, 401, """{"error":"invalid api key"}""")
      }) { port =>
        val runSystem = org.apache.pekko.actor.ActorSystem("stream-error-spec-runner")
        try {
          val engine = io.cequence.wsclient.service.spi.StreamedEngineRegistry.outputStreamed(
            TransportSettings(),
            Some(expectedEngineId)
          )
          val failure = Await.result(
            engine
              .execJsonStream(SiteBinding(s"http://localhost:$port"), "events", "POST")
              .runWith(org.apache.pekko.stream.scaladsl.Sink.seq)(
                org.apache.pekko.stream.Materializer(runSystem)
              )
              .failed,
            30.seconds
          )

          failure shouldBe a[io.cequence.wsclient.domain.CequenceWSHttpStatusException]
          val statusError =
            failure.asInstanceOf[io.cequence.wsclient.domain.CequenceWSHttpStatusException]
          statusError.statusCode shouldBe 401
          statusError.body should include("invalid api key")
          failure.getMessage should include("HTTP 401")
          failure.getMessage should include("invalid api key")

          engine.close()
        } finally {
          runSystem.terminate()
          ()
        }
      }
    }

    "expose the neutral Flow.Publisher contract (cold, [DONE]-terminated)" in {
      val requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
      withServer("/events" -> sse(sseBody, requestCount)) { port =>
        val engine = StreamedEngineRegistry.outputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:${port}")

        // the same flavored engine also carries the backend-agnostic publisher contract
        val publisher = engine.execJsonStreamPublisher(site, "events", "GET")

        Thread.sleep(200)
        requestCount.get() shouldBe 0 // COLD: no request until the first subscribe

        // a plain Flow.Subscriber - no akka/pekko types involved on the consumer side
        val subscriber = new LatchedSubscriber[play.api.libs.json.JsValue]
        publisher.subscribe(subscriber)

        subscriber.awaitDone(30) shouldBe true
        requestCount.get() shouldBe 1
        subscriber.completed shouldBe true
        subscriber.received shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))

        engine.close()
      }
    }

    "stream a request body via StreamedEngineRegistry.inputStreamed" in {
      withServer("/" -> echo) { port =>
        val engine = StreamedEngineRegistry.inputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:${port}")

        val source = org.apache.pekko.stream.scaladsl.Source(
          List(
            org.apache.pekko.util.ByteString("hello-"),
            org.apache.pekko.util.ByteString("stream")
          )
        )

        val response = Await.result(
          engine
            .execPOSTSourceRich(site, "upload", source = source)
            .map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "body").get shouldBe JsString("hello-stream")

        engine.close()
      }
    }
  }
}

// a service with its own error classification - what downstream clients do via handleErrorCodes
final class RateLimitedTestException(message: String) extends RuntimeException(message)

final class StreamingTestService(
  override protected val engine: io.cequence.wsclient.service.WSClientEngine
    with io.cequence.wsclient.service.WSClientOutputStreamExtraPekko,
  override protected val site: SiteBinding
)(
  implicit executionContext: scala.concurrent.ExecutionContext
) extends io.cequence.wsclient.service.WSClientWithEngineOutputStreamingBase[
      io.cequence.wsclient.service.WSClientEngine
        with io.cequence.wsclient.service.WSClientOutputStreamExtraPekko
    ] {
  protected type PEP = String
  protected type PT = String

  override protected implicit val ec: scala.concurrent.ExecutionContext = executionContext

  override protected def handleErrorCodes(
    httpCode: Int,
    message: String
  ): Nothing =
    throw new RateLimitedTestException(s"$httpCode: $message")
}
