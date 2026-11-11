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
import io.cequence.wsclient.testkit.{AsyncStreamContract, LatchedSubscriber}
import io.cequence.wsclient.testkit.TestServers.{
  asyncStreamContract,
  CookieIsolation,
  cookieIsolation,
  MultipartInjection,
  multipartInjection,
  QueryEncoding,
  queryEncoding,
  stalledBody,
  proxyConnectReply,
  echo,
  respond,
  sse,
  withServer
}
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

  // OpenRouter-style keep-alive comments while a slow model works, before and between events
  private val sseBodyWithComments =
    ": OPENROUTER PROCESSING\n\n: OPENROUTER PROCESSING\n\ndata: {\"i\":1}\n\n: OPENROUTER PROCESSING\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n"

  s"$expectedEngineId provider" should {

    "send (or, on java.net.http, drop) caller-set Host / Connection headers - never fail" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try
        io.cequence.wsclient.testkit.TestServers
          .restrictedHeaders(engine) shouldBe a[Right[_, _]]
      finally engine.close()
    }

    "keep a buffered response's raw bytes (Response.bytes)" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try {
        val (intact, latin1) = io.cequence.wsclient.testkit.TestServers.bufferedBytes(engine)
        intact shouldBe true
        info(s"Latin-1 text decoded as: $latin1")
      } finally engine.close()
    }

    "serve its AsyncStreams as native Sources: cold, no bridge, typed frame overflow" in {
      val requests = new java.util.concurrent.atomic.AtomicInteger()
      io.cequence.wsclient.testkit.TestServers.withServer(
        "/sse" -> io.cequence.wsclient.testkit.TestServers
          .sse("data: {\"n\":1}\n\ndata: {\"n\":2}\n\ndata: [DONE]\n\n", requests),
        "/big" -> io.cequence.wsclient.testkit.TestServers.sse("data: " + "1" * 300 + "\n\n")
      ) { port =>
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        val runSystem = org.apache.pekko.actor.ActorSystem("native-leaf-spec")
        implicit val runMaterializer: org.apache.pekko.stream.Materializer =
          org.apache.pekko.stream.Materializer(runSystem)
        try {
          import io.cequence.wsclient.stream.SourceInterop._
          import io.cequence.wsclient.stream.StreamDiagnostics.counted
          import scala.concurrent.Await
          import scala.concurrent.duration._

          val site = io.cequence.wsclient.domain.SiteBinding(s"http://localhost:$port")
          val stream =
            engine.execJsonAsyncStream(site, "sse", "GET").map(json => (json \ "n").as[Int])
          stream.describe should startWith("native(pekko)")
          Thread.sleep(100)
          requests.get() shouldBe 0 // cold

          val (source, bridges) = counted(stream.toSource)
          Await.result(
            source.runWith(org.apache.pekko.stream.scaladsl.Sink.seq),
            30.seconds
          ) shouldBe Seq(1, 2)
          Await.result(
            source.runWith(org.apache.pekko.stream.scaladsl.Sink.seq),
            30.seconds
          ) shouldBe Seq(1, 2)
          bridges.get shouldBe 0
          requests.get() shouldBe 2 // a request per run

          // raw bytes: the engine's own ByteString source, without a bridge
          val (raw, rawBridges) =
            counted(engine.execRawAsyncStream(site, "sse", "GET").toByteStringSource)
          Await.result(
            raw.runWith(org.apache.pekko.stream.scaladsl.Sink.fold(0)(_ + _.length)),
            30.seconds
          ) should be > 0
          rawBridges.get shouldBe 0

          // a download: its 404 preflight runs in pekko streams too, so the body stays the
          // engine's own source - through a materializer of another actor system as well
          val (download, downloadBridges) = counted {
            val body = Await
              .result(AsyncStreamContract.streamedDownload(engine, site, "sse"), 30.seconds)
              .get
            body.describe shouldBe "native(pekko) -> mapError"
            body.toByteStringSource
          }
          Await.result(
            download.runWith(org.apache.pekko.stream.scaladsl.Sink.fold(0)(_ + _.length)),
            30.seconds
          ) should be > 0
          downloadBridges.get shouldBe 0
          Await.result(
            AsyncStreamContract.streamedDownload(engine, site, "missing"),
            30.seconds
          ) shouldBe None

          // consumed outside the backend: the engine's materializer runs it (one bridge)
          val (neutral, neutralBridges) = counted(stream.runToSeq)
          Await.result(neutral, 30.seconds) shouldBe Seq(1, 2)
          neutralBridges.get shouldBe 1

          // an oversized frame fails with the typed exception
          val oversized =
            engine.execJsonAsyncStream(site, "big", "GET", maxFrameLength = Some(100))
          val failure = scala.util.Try(Await.result(oversized.runToSeq, 30.seconds)).failed.get
          failure shouldBe a[io.cequence.wsclient.domain.CequenceWSFrameTooLongException]
          failure
            .asInstanceOf[io.cequence.wsclient.domain.CequenceWSFrameTooLongException]
            .maxFrameLength shouldBe 100
        } finally {
          engine.close()
          runSystem.terminate()
          ()
        }
      }
    }

    "honor the AsyncStream contract (cold, framing, cancel, downloads, wire format)" in {
      val engine =
        WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
      // the engine's streams are native leaves: a neutral consumer bridges once per stream
      try
        asyncStreamContract(engine) shouldBe AsyncStreamContract.expected
          .copy(neutralBridges = 2, downloadPlan = "native(pekko) -> mapError")
      finally engine.close()
    }

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
              .runWith(Sink.seq)(org.apache.pekko.stream.Materializer(runSystem)),
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
              .runWith(Sink.seq)(org.apache.pekko.stream.Materializer(runSystem)),
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

    "keep no cookie jar: never send a cookie the site did not supply (site-stateless)" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try cookieIsolation(engine) shouldBe CookieIsolation.expected(streaming = true)
      finally engine.close()
    }

    "keep caller-supplied multipart names inside their Content-Disposition parameter" in {
      // a quote or CR/LF in a display file name, file key or data-part name used to add
      // parameters, part headers and a content prefix to the part
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try
        Seq(false, true).foreach { useInMemoryBody =>
          withClue(s"useInMemoryBody = $useInMemoryBody: ") {
            multipartInjection(engine, useInMemoryBody) shouldBe MultipartInjection.expected
          }
        }
      finally engine.close()
    }

    "percent-encode query param keys and values (raw values in, decoded as sent)" in {
      // the Play engines put them into the URL verbatim up to 1.1.4: a '#' cut the query off
      // (Pinecone's fetch(ids) with "doc#0" came back empty), '&' / '=' split a value, '+'
      // read as a space
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try queryEncoding(engine) shouldBe QueryEncoding.expected
      finally engine.close()
    }

    "fail a call whose response stalls after the headers within the request / read timeout" in {
      // up to 1.1.4 the jdk and sttp engines hung forever: java.net.http's HttpRequest.timeout
      // stops at the response headers
      val result =
        stalledBody()(settings => WSClientEngineRegistry(settings, Some(expectedEngineId)))

      result.nonStreamed shouldBe a[Left[_, _]]
      result.nonStreamedMs should be < 6000L
      result.streamed.foreach(_ shouldBe a[Left[_, _]])
      result.streamedMs.foreach(_ should be < 6000L)
    }

    "fail a proxy's non-200 reply to CONNECT instead of taking it as the https site's response" in {
      // up to 1.1.4 the Play engines took a forged 202 - and its body - from the proxy as the
      // https site's own response (only a 200 establishes a tunnel)
      val result =
        proxyConnectReply(settings => WSClientEngineRegistry(settings, Some(expectedEngineId)))

      result.proxySawConnect shouldBe true
      result.nonStreamed shouldBe a[Left[_, _]]
      result.streamed.foreach(_ shouldBe a[Left[_, _]])
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
