package io.cequence.wsclient.service.ws.stream

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.cequence.wsclient.domain.SiteBinding
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  StreamedEngineRegistry,
  TransportSettings,
  WSClientEngineRegistry
}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration._

class PlayPekkoStreamWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-pekko-stream"
  private val expectedBaseEngineId = "play-pekko"

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
            .runWith(Sink.seq)(org.apache.pekko.stream.Materializer(runSystem)),
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
      } finally
        server.stop(0)
    }
  }
}
