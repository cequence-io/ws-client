package io.cequence.wsclient.service.ws.stream

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.sun.net.httpserver.HttpExchange
import io.cequence.wsclient.domain.{
  CequenceWSException,
  CequenceWSTimeoutException,
  SiteBinding
}
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  StreamedEngineRegistry,
  TransportSettings,
  WSClientEngineRegistry
}
import io.cequence.wsclient.service.ws.Timeouts
import io.cequence.wsclient.testkit.LatchedSubscriber
import io.cequence.wsclient.testkit.TestServers.{
  CookieIsolation,
  cookieIsolation,
  echo,
  respond,
  sse,
  withServer
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class PlayAkkaStreamWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-akka-stream"
  private val expectedBaseEngineId = "play-akka"

  private val sseBody = "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n"

  // OpenRouter-style keep-alive comments while a slow model works, before and between events
  private val sseBodyWithComments =
    ": OPENROUTER PROCESSING\n\n: OPENROUTER PROCESSING\n\ndata: {\"i\":1}\n\n: OPENROUTER PROCESSING\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n"

  private val ping: HttpExchange => Unit = respond(_, 200, """{"status":"ok"}""")

  private val slowPing: HttpExchange => Unit = exchange => {
    Thread.sleep(1000)
    respond(exchange, 200, """{"status":"ok"}""")
  }

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

    "ignore SSE comment frames (keep-alives such as OpenRouter's ': OPENROUTER PROCESSING')" in {
      withServer("/events" -> sse(sseBodyWithComments)) { port =>
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
              .runWith(Sink.seq)(akka.stream.Materializer(runSystem)),
            30.seconds
          )

          events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))

          engine.close()
        } finally {
          runSystem.terminate()
          ()
        }
      }
    }

    "stream CRLF-terminated SSE with comment frames and data: without a space (framingDelimiter)" in {
      val body =
        ": OPENROUTER PROCESSING\r\n\r\ndata: {\"i\":1}\r\n\r\ndata:{\"i\":2}\r\n\r\ndata: [DONE]\r\n\r\n"
      withServer("/events" -> sse(body)) { port =>
        val runSystem = ActorSystem("stream-spec-runner")
        try {
          val engine = StreamedEngineRegistry.outputStreamed(
            TransportSettings(),
            Some(expectedEngineId)
          )
          val site = SiteBinding(s"http://localhost:${port}")

          val events = Await.result(
            engine
              .execJsonStream(site, "events", "GET", framingDelimiter = "\r\n\r\n")
              .runWith(Sink.seq)(akka.stream.Materializer(runSystem)),
            30.seconds
          )

          events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))

          engine.close()
        } finally {
          runSystem.terminate()
          ()
        }
      }
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
              .runWith(Sink.seq)(
                akka.stream.Materializer(runSystem)
              ),
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

    "run its owned actor system on daemon threads (leaked engines must not block JVM exit)" in {
      val engine =
        StreamedEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
      try {
        val systemThreads = Thread.getAllStackTraces.keySet.toArray
          .map(_.asInstanceOf[Thread])
          .filter(_.getName.startsWith(s"ws-client-$expectedEngineId"))
        systemThreads should not be empty
        all(systemThreads.map(_.isDaemon).toSeq) shouldBe true
      } finally
        engine.close()
    }

    "refuse to create a client after close() (close/first-use ordering)" in {
      val engine =
        StreamedEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
      val site = SiteBinding("http://localhost:59999") // never contacted
      engine.close()

      // a first use AFTER close must fail fast instead of creating a client
      // nothing would ever close
      val thrown = intercept[Throwable] {
        Await.result(engine.execGETRich(site, "ping"), 10.seconds)
      }
      // the IllegalStateException may surface directly or wrapped by the engine's recovery
      def chain(t: Throwable): Seq[Throwable] =
        Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(5).toSeq
      assert(
        chain(thrown).exists(_.isInstanceOf[IllegalStateException]) ||
          thrown.getMessage.contains("already closed"),
        s"expected fail-fast after close, got: $thrown"
      )
    }

    "classify a stream's non-2xx status through the service's handleErrorCodes" in {
      withServer("/events" -> { exchange =>
        respond(exchange, 429, """{"error":"slow down"}""")
      }) { port =>
        val runSystem = akka.actor.ActorSystem("stream-classify-spec-runner")
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
              .runWith(akka.stream.scaladsl.Sink.seq)(akka.stream.Materializer(runSystem))
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
        val runSystem = akka.actor.ActorSystem("stream-error-spec-runner")
        try {
          val engine = io.cequence.wsclient.service.spi.StreamedEngineRegistry.outputStreamed(
            TransportSettings(),
            Some(expectedEngineId)
          )
          val failure = Await.result(
            engine
              .execJsonStream(SiteBinding(s"http://localhost:$port"), "events", "POST")
              .runWith(akka.stream.scaladsl.Sink.seq)(
                akka.stream.Materializer(runSystem)
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

        val source = akka.stream.scaladsl.Source(
          List(akka.util.ByteString("hello-"), akka.util.ByteString("stream"))
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

    "share one engine across a plain-GET site and an SSE-streaming site" in {
      withServer("/ping" -> ping) { pingPort =>
        withServer("/events" -> sse(sseBody)) { eventsPort =>
          val system = ActorSystem("play-akka-stream-engine-sharing-spec")
          implicit val materializer: Materializer = Materializer(system)
          implicit val ec: ExecutionContext = system.dispatcher

          val engine = new PlayWSStreamClientEngine()
          try {
            // a plain (unary) site - shares the engine's client with the streaming site
            val pingSite = SiteBinding(s"http://localhost:${pingPort}")
            // a DIFFERENT site for the Source-typed streaming call, same shared engine
            val eventsSite = SiteBinding(s"http://localhost:${eventsPort}")

            val pingResponse = Await.result(
              engine.execGETRich(pingSite, "ping").map(engine.getResponseOrError),
              30.seconds
            )
            (pingResponse.json \ "status").get shouldBe JsString("ok")

            val events = Await.result(
              engine.execJsonStream(eventsSite, "events", "GET").runWith(Sink.seq),
              30.seconds
            )
            events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))
          } finally {
            engine.close()
            system.terminate()
            ()
          }
        }
      }
    }

    "keep no cookie jar: never send a cookie the site did not supply (site-stateless)" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try cookieIsolation(engine) shouldBe CookieIsolation.expected(streaming = true)
      finally engine.close()
    }

    "copy() with reuseExecContext=true (default) shares this engine's actor system" in {
      withServer("/ping" -> ping) { port =>
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]
        val site = SiteBinding(s"http://localhost:${port}")

        val copy = engine.copy()

        copy.materializer.system should be theSameInstanceAs engine.materializer.system

        val response = Await.result(
          copy.execGETRich(site, "ping").map(copy.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        // the copy does NOT own the shared system - close() is a deliberate no-op, leaving
        // both the system and the original engine fully functional
        copy.close()
        noException should be thrownBy copy.close()
        noException should be thrownBy Await.result(
          engine.execGETRich(site, "ping"),
          30.seconds
        )

        val system = engine.materializer.system
        engine.close()
        Await.result(system.whenTerminated, 10.seconds)
      }
    }

    "copy(transportSettings) honors the new settings independent of the original" in {
      withServer("/ping" -> slowPing) { port =>
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]
        val site = SiteBinding(s"http://localhost:${port}")

        val tightTimeoutCopy = engine.copy(
          transportSettings = TransportSettings(Timeouts(requestTimeout = Some(300)))
        )

        a[CequenceWSTimeoutException] should be thrownBy Await.result(
          tightTimeoutCopy.execGETRich(site, "ping").map(tightTimeoutCopy.getResponseOrError),
          10.seconds
        )

        // the original engine's (much longer) default timeout tolerates the same 1 s delay
        val response = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        tightTimeoutCopy.close()
        engine.close()
      }
    }

    "copy(reuseExecContext = false) creates an independent, daemonic actor system" in {
      withServer("/ping" -> ping) { port =>
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]
        val site = SiteBinding(s"http://localhost:${port}")

        val independentCopy = engine.copy(reuseExecContext = false)

        independentCopy.materializer.system should not be theSameInstanceAs(
          engine.materializer.system
        )

        val isDaemon = Await.result(
          Future(Thread.currentThread().isDaemon)(independentCopy.ec),
          10.seconds
        )
        isDaemon shouldBe true

        val response = Await.result(
          independentCopy.execGETRich(site, "ping").map(independentCopy.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        // closing the copy terminates ONLY its own (newly-created) system
        independentCopy.close()
        Await.result(independentCopy.materializer.system.whenTerminated, 10.seconds)

        noException should be thrownBy Await.result(
          engine.execGETRich(site, "ping"),
          30.seconds
        )

        engine.close()
      }
    }

    "copy(reuseExecContext = false) on a caller-supplied (apply-created) engine throws" in {
      val testSystem = ActorSystem("play-akka-stream-engine-spec-copy-throws")
      try {
        val engine =
          PlayWSStreamClientEngine()(Materializer(testSystem), testSystem.dispatcher)

        a[CequenceWSException] should be thrownBy engine.copy(reuseExecContext = false)

        engine.close()
      } finally {
        testSystem.terminate()
        ()
      }
    }

    "a copy still streams SSE, with a static return type of PlayWSStreamClientEngine" in {
      withServer("/events" -> sse(sseBody)) { port =>
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]

        // no cast needed: engine.copy() is statically typed as PlayWSStreamClientEngine, so
        // its streaming methods (mixed in via WSClientOutputStreamExtraAkka) are available
        // directly
        val copy: PlayWSStreamClientEngine = engine.copy()
        val site = SiteBinding(s"http://localhost:${port}")

        val events = Await.result(
          copy.execJsonStream(site, "events", "GET").runWith(Sink.seq)(copy.materializer),
          30.seconds
        )
        events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))

        copy.close()
        engine.close()
      }
    }
  }
}

// a service with its own error classification - what downstream clients do via handleErrorCodes
final class RateLimitedTestException(message: String) extends RuntimeException(message)

final class StreamingTestService(
  override protected val engine: io.cequence.wsclient.service.WSClientEngine
    with io.cequence.wsclient.service.WSClientOutputStreamExtraAkka,
  override protected val site: SiteBinding
)(
  implicit executionContext: scala.concurrent.ExecutionContext
) extends io.cequence.wsclient.service.WSClientWithEngineOutputStreamingBase[
      io.cequence.wsclient.service.WSClientEngine
        with io.cequence.wsclient.service.WSClientOutputStreamExtraAkka
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
