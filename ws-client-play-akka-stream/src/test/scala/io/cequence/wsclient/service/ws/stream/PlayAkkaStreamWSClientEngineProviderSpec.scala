package io.cequence.wsclient.service.ws.stream

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import java.net.InetSocketAddress
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class PlayAkkaStreamWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-akka-stream"
  private val expectedBaseEngineId = "play-akka"

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
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/events",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val body =
              "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.length)
            val os = exchange.getResponseBody
            os.write(body)
            os.close()
          }
        }
      )
      server.start()

      val runSystem = ActorSystem("stream-spec-runner")
      try {
        val engine = StreamedEngineRegistry.outputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

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
        server.stop(0)
        runSystem.terminate()
        ()
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

    "expose the neutral Flow.Publisher contract (cold, [DONE]-terminated)" in {
      val requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/events",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            requestCount.incrementAndGet()
            val body =
              "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.length)
            val os = exchange.getResponseBody
            os.write(body)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine = StreamedEngineRegistry.outputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        // the same flavored engine also carries the backend-agnostic publisher contract
        val publisher = engine.execJsonStreamPublisher(site, "events", "GET")

        Thread.sleep(200)
        requestCount.get() shouldBe 0 // COLD: no request until the first subscribe

        val received =
          new java.util.concurrent.CopyOnWriteArrayList[play.api.libs.json.JsValue]()
        val done = new java.util.concurrent.CountDownLatch(1)
        @volatile var completed = false

        // a hand-rolled subscriber - no akka/pekko types involved on the consumer side
        publisher.subscribe(
          new java.util.concurrent.Flow.Subscriber[play.api.libs.json.JsValue] {
            private var s: java.util.concurrent.Flow.Subscription = _
            override def onSubscribe(sub: java.util.concurrent.Flow.Subscription): Unit = {
              s = sub
              sub.request(1)
            }
            override def onNext(item: play.api.libs.json.JsValue): Unit = {
              received.add(item)
              s.request(1)
            }
            override def onError(t: Throwable): Unit = done.countDown()
            override def onComplete(): Unit = {
              completed = true
              done.countDown()
            }
          }
        )

        done.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        requestCount.get() shouldBe 1
        completed shouldBe true
        received.size shouldBe 2
        received.get(0) shouldBe Json.obj("i" -> 1)
        received.get(1) shouldBe Json.obj("i" -> 2)

        engine.close()
      } finally
        server.stop(0)
    }

    "stream a request body via StreamedEngineRegistry.inputStreamed" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val requestBody = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
            val response = Json.obj("body" -> requestBody).toString.getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length)
            val os = exchange.getResponseBody
            os.write(response)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine = StreamedEngineRegistry.inputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

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
      } finally
        server.stop(0)
    }

    "share one engine across a plain-GET site and an SSE-streaming site" in {
      val pingServer = HttpServer.create(new InetSocketAddress(0), 0)
      pingServer.createContext(
        "/ping",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val response = """{"status":"ok"}""".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length)
            val os = exchange.getResponseBody
            os.write(response)
            os.close()
          }
        }
      )
      pingServer.start()

      val eventsServer = HttpServer.create(new InetSocketAddress(0), 0)
      eventsServer.createContext(
        "/events",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val body =
              "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.length)
            val os = exchange.getResponseBody
            os.write(body)
            os.close()
          }
        }
      )
      eventsServer.start()

      val system = ActorSystem("play-akka-stream-engine-sharing-spec")
      implicit val materializer: Materializer = Materializer(system)
      implicit val ec: ExecutionContext = system.dispatcher

      val engine = new PlayWSStreamClientEngine()
      try {
        // a plain (unary) site - shares the engine's client with the streaming site
        val pingSite = SiteBinding(s"http://localhost:${pingServer.getAddress.getPort}")
        // a DIFFERENT site for the Source-typed streaming call, same shared engine
        val eventsSite = SiteBinding(s"http://localhost:${eventsServer.getAddress.getPort}")

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
        pingServer.stop(0)
        eventsServer.stop(0)
        ()
      }
    }

    "copy() with reuseExecContext=true (default) shares this engine's actor system" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/ping",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val response = """{"status":"ok"}""".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length)
            val os = exchange.getResponseBody
            os.write(response)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

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
      } finally
        server.stop(0)
    }

    "copy(transportSettings) honors the new settings independent of the original" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/ping",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            Thread.sleep(2000)
            val response = """{"status":"ok"}""".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length)
            val os = exchange.getResponseBody
            os.write(response)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val tightTimeoutCopy = engine.copy(
          transportSettings = TransportSettings(Timeouts(requestTimeout = Some(300)))
        )

        a[CequenceWSTimeoutException] should be thrownBy Await.result(
          tightTimeoutCopy.execGETRich(site, "ping").map(tightTimeoutCopy.getResponseOrError),
          10.seconds
        )

        // the original engine's (much longer) default timeout tolerates the same 2s delay
        val response = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        tightTimeoutCopy.close()
        engine.close()
      } finally
        server.stop(0)
    }

    "copy(reuseExecContext = false) creates an independent, daemonic actor system" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/ping",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val response = """{"status":"ok"}""".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length)
            val os = exchange.getResponseBody
            os.write(response)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

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
      } finally
        server.stop(0)
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
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/events",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val body =
              "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\n".getBytes("UTF-8")
            exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.length)
            val os = exchange.getResponseBody
            os.write(body)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine = StreamedEngineRegistry
          .outputStreamed(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSStreamClientEngine]

        // no cast needed: engine.copy() is statically typed as PlayWSStreamClientEngine, so
        // its streaming methods (mixed in via WSClientOutputStreamExtraAkka) are available
        // directly
        val copy: PlayWSStreamClientEngine = engine.copy()
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val events = Await.result(
          copy.execJsonStream(site, "events", "GET").runWith(Sink.seq)(copy.materializer),
          30.seconds
        )
        events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))

        copy.close()
        engine.close()
      } finally
        server.stop(0)
    }
  }
}
