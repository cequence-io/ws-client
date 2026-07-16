package io.cequence.wsclient.service.ws

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.cequence.wsclient.domain.{
  CequenceWSException,
  CequenceWSTimeoutException,
  SiteBinding,
  WsRequestContext
}
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineRegistry
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import java.net.InetSocketAddress
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class PekkoHttpWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "pekko-http"

  private def withEchoServer(test: (Int) => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.createContext(
      "/",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val requestBody =
            new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
          val response = Json
            .obj(
              "status" -> "ok",
              "method" -> exchange.getRequestMethod,
              "contentType" -> Option(exchange.getRequestHeaders.getFirst("Content-Type"))
                .getOrElse[String](""),
              "transferEncoding" -> Option(
                exchange.getRequestHeaders.getFirst("Transfer-encoding")
              ).getOrElse[String](""),
              "contentLength" -> Option(exchange.getRequestHeaders.getFirst("Content-length"))
                .getOrElse[String](""),
              "query" -> Option(exchange.getRequestURI.getRawQuery).getOrElse[String](""),
              "body" -> requestBody
            )
            .toString
            .getBytes("UTF-8")
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, response.length)
          val os = exchange.getResponseBody
          os.write(response)
          os.close()
        }
      }
    )
    server.start()
    try
      test(server.getAddress.getPort)
    finally
      server.stop(0)
  }

  // echoes the Authorization header back as JSON - used to verify per-site auth isolation
  // when several sites are served by one shared engine
  private def withAuthEchoServer(test: (Int) => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.createContext(
      "/",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val auth = Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          val response = Json.obj("auth" -> auth).toString.getBytes("UTF-8")
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, response.length)
          val os = exchange.getResponseBody
          os.write(response)
          os.close()
        }
      }
    )
    server.start()
    try
      test(server.getAddress.getPort)
    finally
      server.stop(0)
  }

  // responds only after `delayMs` - used to exercise request-timeout behavior
  private def withSlowEchoServer(delayMs: Long)(test: Int => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.createContext(
      "/",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          Thread.sleep(delayMs)
          val response = Json.obj("status" -> "ok").toString.getBytes("UTF-8")
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, response.length)
          val os = exchange.getResponseBody
          os.write(response)
          os.close()
        }
      }
    )
    server.start()
    try
      test(server.getAddress.getPort)
    finally
      server.stop(0)
  }

  s"$expectedEngineId provider" should {

    "be discovered via ServiceLoader with the expected id and capabilities" in {
      val providers = WSClientEngineRegistry.providers

      providers.map(_.engineId) shouldBe Seq(expectedEngineId)
      providers.head.capabilities should contain allOf (
        EngineCapability.InputStreaming,
        EngineCapability.OutputStreaming,
        EngineCapability.Multipart
      )
    }

    "create a working engine that can GET and POST JSON, and close idempotently" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")

        val getResponse = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (getResponse.json \ "status").get shouldBe JsString("ok")
        (getResponse.json \ "method").get shouldBe JsString("GET")

        val postResponse = Await.result(
          engine
            .execPOSTRich(
              site,
              "items",
              bodyParams = Seq("name" -> Some(JsString("John")))
            )
            .map(engine.getResponseOrError),
          30.seconds
        )
        (postResponse.json \ "method").get shouldBe JsString("POST")
        (postResponse.json \ "contentType").get shouldBe JsString("application/json")
        Json.parse((postResponse.json \ "body").get.as[String]) shouldBe Json.obj(
          "name" -> "John"
        )

        engine.close()
        noException should be thrownBy engine.close()
      }
    }

    "merge query params with a query already embedded in the endpoint" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")

        val response = Await.result(
          engine
            .execGETRich(
              site,
              "ping?api-version=2024-02-01",
              params = Seq("tag" -> Some("value"))
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        val query = (response.json \ "query").get.as[String]
        query should include("api-version=2024-02-01")
        query should include("tag=value")

        engine.close()
      }
    }

    "honor a Content-Type passed via extraHeaders" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")

        val response = Await.result(
          engine
            .execPOSTRich(
              site,
              "items",
              bodyParams = Seq("name" -> Some(JsString("John"))),
              extraHeaders = Seq("Content-Type" -> "application/xml")
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "contentType").get.as[String] should startWith("application/xml")

        engine.close()
      }
    }

    "POST an in-memory multipart body with a Content-Length" in {
      withEchoServer { port =>
        val file = java.io.File.createTempFile("ws-client-pekko-http-test", ".txt")
        file.deleteOnExit()
        val writer = new java.io.PrintWriter(file)
        writer.print("file-content")
        writer.close()

        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")

        val response = Await.result(
          engine
            .execPOSTMultipartRich(
              site,
              "upload",
              fileParams = Seq(("file", file, Some("upload.txt"))),
              bodyParams = Seq("purpose" -> Some("test")),
              useInMemoryBody = true
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "contentType").get.as[String] should startWith(
          "multipart/form-data; boundary="
        )
        // in-memory body => a known Content-Length, no chunked transfer encoding
        (response.json \ "transferEncoding").get.as[String] shouldBe empty
        (response.json \ "contentLength").get.as[String].toLong should be > 0L
        val body = (response.json \ "body").get.as[String]
        body should include("file-content")
        body should include("test")

        engine.close()
      }
    }

    "stream a request body via StreamedEngineRegistry.inputStreamed" in {
      withEchoServer { port =>
        val engine = io.cequence.wsclient.service.spi.StreamedEngineRegistry.inputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:$port")

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

    "stream SSE JSON events via StreamedEngineRegistry" in {
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

      val runSystem = org.apache.pekko.actor.ActorSystem("stream-spec-runner")
      try {
        val engine = io.cequence.wsclient.service.spi.StreamedEngineRegistry.outputStreamed(
          TransportSettings(),
          Some(expectedEngineId)
        )
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val events = Await.result(
          engine
            .execJsonStream(site, "events", "GET")
            .runWith(org.apache.pekko.stream.scaladsl.Sink.seq)(
              org.apache.pekko.stream.Materializer(runSystem)
            ),
          30.seconds
        )

        events shouldBe Seq(Json.obj("i" -> 1), Json.obj("i" -> 2))

        engine.close()
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
        val engine = io.cequence.wsclient.service.spi.StreamedEngineRegistry.outputStreamed(
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

    "serve two sites through one engine, with correctly isolated auth headers" in {
      withAuthEchoServer { portA =>
        withAuthEchoServer { portB =>
          val testSystem = org.apache.pekko.actor.ActorSystem("pekko-http-engine-spec-sites")
          try {
            val engine = PekkoHttpWSClientEngine()(testSystem, testSystem.dispatcher)

            val siteA = SiteBinding(
              s"http://localhost:$portA",
              requestContext =
                WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer site-a")),
              label = Some("site-a")
            )
            val siteB = SiteBinding(
              s"http://localhost:$portB",
              requestContext =
                WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer site-b")),
              label = Some("site-b")
            )

            val responseA = Await.result(
              engine.execGETRich(siteA, "ping").map(engine.getResponseOrError),
              30.seconds
            )
            val responseB = Await.result(
              engine.execGETRich(siteB, "ping").map(engine.getResponseOrError),
              30.seconds
            )

            (responseA.json \ "auth").get shouldBe JsString("Bearer site-a")
            (responseB.json \ "auth").get shouldBe JsString("Bearer site-b")

            // close() on a caller-supplied (non-owned) system is a deliberate no-op - the
            // system, and any sibling site still using this engine, are left alone
            engine.close()
            noException should be thrownBy engine.close()
            noException should be thrownBy Await.result(
              engine.execGETRich(siteA, "ping"),
              30.seconds
            )
          } finally {
            testSystem.terminate()
            ()
          }
        }
      }
    }

    "terminate its dedicated actor system when the provider's engine is closed" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PekkoHttpWSClientEngine]
        val site = SiteBinding(s"http://localhost:$port")

        Await.result(engine.execGETRich(site, "ping"), 30.seconds)

        engine.close()
        noException should be thrownBy engine.close()

        // the dedicated actor system the provider created for this engine is actually
        // terminated - not just the pool shutdown fired-and-forgotten
        Await.ready(engine.system.whenTerminated, 10.seconds)
      }
    }

    "copy() with reuseExecContext=true (default) shares this engine's actor system" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PekkoHttpWSClientEngine]
        val site = SiteBinding(s"http://localhost:$port")

        val copy = engine.copy()

        copy.system should be theSameInstanceAs engine.system

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

        engine.close()
      }
    }

    "copy(transportSettings) honors the new settings independent of the original" in {
      withSlowEchoServer(2000) { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PekkoHttpWSClientEngine]
        val site = SiteBinding(s"http://localhost:$port")

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
      }
    }

    "copy(reuseExecContext = false) creates an independent, daemonic actor system" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PekkoHttpWSClientEngine]
        val site = SiteBinding(s"http://localhost:$port")

        val independentCopy = engine.copy(reuseExecContext = false)

        independentCopy.system should not be theSameInstanceAs(engine.system)

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
        Await.ready(independentCopy.system.whenTerminated, 10.seconds)

        noException should be thrownBy Await.result(
          engine.execGETRich(site, "ping"),
          30.seconds
        )

        engine.close()
      }
    }

    "copy(reuseExecContext = false) on a caller-supplied (apply-created) engine throws" in {
      val testSystem =
        org.apache.pekko.actor.ActorSystem("pekko-http-engine-spec-copy-throws")
      try {
        val engine = PekkoHttpWSClientEngine()(testSystem, testSystem.dispatcher)

        a[CequenceWSException] should be thrownBy engine.copy(reuseExecContext = false)

        engine.close()
      } finally {
        testSystem.terminate()
        ()
      }
    }

    "a copy still streams SSE, with a static return type of PekkoHttpWSClientEngine" in {
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
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PekkoHttpWSClientEngine]

        // no cast needed: engine.copy() is statically typed as PekkoHttpWSClientEngine, so
        // its streaming methods (mixed in via WSClientOutputStreamExtraPekko) are available
        // directly
        val copy: PekkoHttpWSClientEngine = engine.copy()
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val events = Await.result(
          copy
            .execJsonStream(site, "events", "GET")
            .runWith(org.apache.pekko.stream.scaladsl.Sink.seq)(
              org.apache.pekko.stream.Materializer(copy.system)
            ),
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
