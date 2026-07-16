package io.cequence.wsclient.service.ws

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.cequence.wsclient.domain.{
  CequenceWSTimeoutException,
  CequenceWSUnknownHostException,
  SimpleRichResponse,
  SiteBinding,
  StatusData,
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

import java.io.{File, PrintWriter}
import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration._

class JdkWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "jdk"

  // a plain Flow.Subscriber - deliberately no stream library involved
  private class LatchedJsonSubscriber
      extends java.util.concurrent.Flow.Subscriber[play.api.libs.json.JsValue] {
    private val buffer = scala.collection.mutable.ListBuffer[play.api.libs.json.JsValue]()
    @volatile var error: Option[Throwable] = None
    @volatile var completed = false
    private val done = new java.util.concurrent.CountDownLatch(1)
    private var subscription: java.util.concurrent.Flow.Subscription = _

    def received: List[play.api.libs.json.JsValue] = buffer.toList

    override def onSubscribe(s: java.util.concurrent.Flow.Subscription): Unit = {
      subscription = s
      s.request(1)
    }
    override def onNext(item: play.api.libs.json.JsValue): Unit = {
      buffer += item
      subscription.request(1)
    }
    override def onError(t: Throwable): Unit = {
      error = Some(t)
      done.countDown()
    }
    override def onComplete(): Unit = {
      completed = true
      done.countDown()
    }
    def awaitDone(seconds: Int): Unit = {
      done.await(seconds.toLong, java.util.concurrent.TimeUnit.SECONDS)
      ()
    }
  }

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
              // all values, comma-joined - catches duplicated headers
              "contentTypes" -> Option(exchange.getRequestHeaders.get("Content-Type"))
                .map(String.join(",", _))
                .getOrElse[String](""),
              "transferEncoding" -> Option(
                exchange.getRequestHeaders.getFirst("Transfer-encoding")
              ).getOrElse[String](""),
              "contentLength" -> Option(exchange.getRequestHeaders.getFirst("Content-length"))
                .getOrElse[String](""),
              "query" -> Option(exchange.getRequestURI.getRawQuery).getOrElse[String](""),
              "auth" -> Option(exchange.getRequestHeaders.getFirst("Authorization"))
                .getOrElse[String](""),
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

  // a single-connection HTTP proxy stub: captures the request line (absolute-form for proxied
  // plain-http requests) and answers with a fixed JSON response
  private def withDumbProxy(test: (Int, () => String) => Unit): Unit = {
    val server = new java.net.ServerSocket(0)
    @volatile var requestLine = ""

    val thread = new Thread(new Runnable {
      override def run(): Unit =
        try {
          // serve until the test closes the server socket - some clients probe with an
          // extra connection, so a single accept would be flaky
          while (true) {
            val socket = server.accept()
            try {
              val in = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream, "UTF-8")
              )
              val firstLine = in.readLine()
              if (firstLine != null && requestLine.isEmpty) requestLine = firstLine
              // drain the headers
              var line = in.readLine()
              while (line != null && line.nonEmpty) line = in.readLine()

              val body = """{"status":"proxied"}"""
              val response =
                s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
              socket.getOutputStream.write(response.getBytes("UTF-8"))
              socket.getOutputStream.flush()
            } finally
              socket.close()
          }
        } catch {
          case _: Throwable => // server closed by the test - nothing to do
        }
    })
    thread.start()

    try
      test(server.getLocalPort, () => requestLine)
    finally
      server.close()
  }

  s"$expectedEngineId provider" should {

    "be discovered via ServiceLoader with the expected id and capabilities" in {
      val providers = WSClientEngineRegistry.providers

      providers.map(_.engineId) shouldBe Seq(expectedEngineId)
      providers.head.capabilities shouldBe Set(
        EngineCapability.Multipart,
        EngineCapability.OutputStreaming
      )
    }

    "create a working engine that can GET, and close idempotently" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")

        val response = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")
        (response.json \ "method").get shouldBe JsString("GET")

        engine.close()
        noException should be thrownBy engine.close()
      }
    }

    "POST a JSON body" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")

        val response = Await.result(
          engine
            .execPOSTRich(
              site,
              "items",
              bodyParams = Seq("name" -> Some(JsString("John")))
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "method").get shouldBe JsString("POST")
        (response.json \ "contentType").get shouldBe JsString("application/json")
        Json.parse((response.json \ "body").get.as[String]) shouldBe Json.obj("name" -> "John")

        engine.close()
      }
    }

    "POST a multipart body" in {
      withEchoServer { port =>
        val file = File.createTempFile("ws-client-jdk-test", ".txt")
        file.deleteOnExit()
        val writer = new PrintWriter(file)
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
              bodyParams = Seq("purpose" -> Some("test"))
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
        body should include("""name="file"; filename="upload.txt"""")
        body should include("file-content")
        body should include("name=purpose")
        body should include("test")

        engine.close()
      }
    }

    "honor a caller-provided Content-Type without duplicating it" in {
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

        (response.json \ "contentTypes").get shouldBe JsString("application/xml")

        engine.close()
      }
    }

    "map a DNS resolution failure to CequenceWSUnknownHostException" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      val site = SiteBinding("http://nonexistent-host-ws-client-test.invalid")

      assertThrows[CequenceWSUnknownHostException] {
        Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
      }

      engine.close()
    }

    "apply user recoverErrors to the normalized (Cequence) exception" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      val site = SiteBinding(
        "http://nonexistent-host-ws-client-test.invalid",
        recoverErrors = Some((_: String) => { case _: CequenceWSUnknownHostException =>
          SimpleRichResponse(None, StatusData(599, "dns-fallback"), Map.empty)
        })
      )

      val response = Await.result(engine.execGETRich(site, "ping"), 30.seconds)
      response.status.code shouldBe 599
      response.status.message shouldBe "dns-fallback"

      engine.close()
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

    "re-evaluate a dynamic request context on every request" in {
      withEchoServer { port =>
        // simulates a token refresh happening between two requests
        @volatile var token = "t1"
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(
          s"http://localhost:$port",
          requestContextFun = Some(() =>
            WsRequestContext(authHeaders = Seq("Authorization" -> s"Bearer $token"))
          )
        )

        val first = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        token = "t2"
        val second = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )

        (first.json \ "auth").get shouldBe JsString("Bearer t1")
        (second.json \ "auth").get shouldBe JsString("Bearer t2")

        engine.close()
      }
    }

    "serve two sites through one engine, with correctly isolated auth headers" in {
      withEchoServer { portA =>
        withEchoServer { portB =>
          val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
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

          engine.close()
        }
      }
    }

    // ---------------------------------------------------------------------------
    // engine.copy() - a new engine with a client rebuilt from possibly-changed
    // TransportSettings, closed independently of the one it was copied from
    // ---------------------------------------------------------------------------

    "copy() with no args preserve settings and work independently, with idempotent closes" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val copyEngine = engine.copy()
        val site = SiteBinding(s"http://localhost:$port")

        copyEngine.transportSettings shouldBe engine.transportSettings

        val viaCopy = Await.result(
          copyEngine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (viaCopy.json \ "status").get shouldBe JsString("ok")

        // closing the copy leaves the original working
        copyEngine.close()
        noException should be thrownBy copyEngine.close()
        val viaOriginalAfterCopyClosed = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (viaOriginalAfterCopyClosed.json \ "status").get shouldBe JsString("ok")

        // ... and vice versa: closing the original leaves a second, still-open copy working
        val secondCopy = engine.copy()
        engine.close()
        noException should be thrownBy engine.close()
        val viaSecondCopyAfterOriginalClosed = Await.result(
          secondCopy.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (viaSecondCopyAfterOriginalClosed.json \ "status").get shouldBe JsString("ok")

        secondCopy.close()
      }
    }

    "a copy with a shorter requestTimeout times out fast while the original succeeds" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/slow",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            Thread.sleep(2000)
            try {
              val body = Json.obj("status" -> "ok").toString.getBytes("UTF-8")
              exchange.getResponseHeaders.add("Content-Type", "application/json")
              exchange.sendResponseHeaders(200, body.length)
              val os = exchange.getResponseBody
              os.write(body)
              os.close()
            } catch {
              // the fast-timeout copy's request may have already aborted its side by now
              case _: Throwable =>
            }
          }
        }
      )
      server.start()

      try {
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val fastTimeoutCopy =
          engine.copy(TransportSettings(timeouts = Timeouts(requestTimeout = Some(300))))
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        assertThrows[CequenceWSTimeoutException] {
          Await.result(
            fastTimeoutCopy.execGETRich(site, "slow").map(engine.getResponseOrError),
            5.seconds
          )
        }

        val response = Await.result(
          engine.execGETRich(site, "slow").map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        fastTimeoutCopy.close()
        engine.close()
      } finally
        server.stop(0)
    }

    // ---------------------------------------------------------------------------
    // Output streaming via the backend-agnostic Flow.Publisher contract - consumed
    // with a hand-rolled java.util.concurrent.Flow.Subscriber: NO stream library
    // anywhere (this module cannot even reference akka/pekko - enforced at compile
    // time by its dependency set)
    // ---------------------------------------------------------------------------

    "stream SSE JSON events through WSClientEngineRegistry.outputStreamed, lazily, up to [DONE]" in {
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
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val publisher = engine.execJsonStreamPublisher(site, "events", "GET")

        // COLD contract: creating the publisher must not fire the request
        Thread.sleep(200)
        requestCount.get() shouldBe 0

        val subscriber = new LatchedJsonSubscriber
        publisher.subscribe(subscriber)
        subscriber.awaitDone(30)

        requestCount.get() shouldBe 1
        subscriber.received shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
        subscriber.completed shouldBe true
        subscriber.error shouldBe None

        // SINGLE-SHOT contract: a second subscriber is rejected
        val second = new LatchedJsonSubscriber
        publisher.subscribe(second)
        second.awaitDone(5)
        second.error.map(_.getClass.getSimpleName) shouldBe Some("IllegalStateException")

        engine.close()
      } finally
        server.stop(0)
    }

    "stream SSE JSON events through a copy() of a streamed-capable engine" in {
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
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        // .copy() is narrowed by WSClientOutputStreamCore, so it keeps streaming capability
        val copyEngine = engine.copy()
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val subscriber = new LatchedJsonSubscriber
        copyEngine.execJsonStreamPublisher(site, "events", "GET").subscribe(subscriber)
        subscriber.awaitDone(30)

        subscriber.received shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
        subscriber.completed shouldBe true
        subscriber.error shouldBe None

        copyEngine.close()
        engine.close()
      } finally
        server.stop(0)
    }

    "honor custom framing with strip prefix/suffix (Gemini style)" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/stream",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val body = "[{\"i\":1}\n,\r\n{\"i\":2}]\n,\r\n".getBytes("UTF-8")
            exchange.sendResponseHeaders(200, body.length)
            val os = exchange.getResponseBody
            os.write(body)
            os.close()
          }
        }
      )
      server.start()

      try {
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val subscriber = new LatchedJsonSubscriber
        engine
          .execJsonStreamPublisher(
            site,
            "stream",
            "GET",
            framingDelimiter = "\n,\r\n",
            stripPrefix = Some("["),
            stripSuffix = Some("]")
          )
          .subscribe(subscriber)
        subscriber.awaitDone(30)

        subscriber.received shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
        subscriber.completed shouldBe true

        engine.close()
      } finally
        server.stop(0)
    }

    "deliver raw bytes and stop after downstream cancellation" in {
      val server = HttpServer.create(new InetSocketAddress(0), 0)
      server.createContext(
        "/raw",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            exchange.sendResponseHeaders(200, 0) // chunked
            val os = exchange.getResponseBody
            try {
              var i = 0
              while (i < 1000) {
                os.write(s"chunk-$i;".getBytes("UTF-8"))
                os.flush()
                Thread.sleep(5)
                i += 1
              }
            } catch {
              case _: Throwable => // client disconnected - expected on cancellation
            } finally
              try os.close()
              catch { case _: Throwable => }
          }
        }
      )
      server.start()

      try {
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        @volatile var receivedBytes = 0
        @volatile var cancelled = false
        val gotSome = new java.util.concurrent.CountDownLatch(1)

        engine
          .execRawStreamPublisher(site, "raw", "GET", None, Nil, Nil, Nil)
          .subscribe(new java.util.concurrent.Flow.Subscriber[java.nio.ByteBuffer] {
            private var subscription: java.util.concurrent.Flow.Subscription = _
            override def onSubscribe(s: java.util.concurrent.Flow.Subscription): Unit = {
              subscription = s
              s.request(1)
            }
            override def onNext(item: java.nio.ByteBuffer): Unit = {
              receivedBytes += item.remaining()
              if (receivedBytes > 20 && !cancelled) {
                cancelled = true
                subscription.cancel()
                gotSome.countDown()
              } else if (!cancelled) subscription.request(1)
            }
            override def onError(t: Throwable): Unit = gotSome.countDown()
            override def onComplete(): Unit = gotSome.countDown()
          })

        gotSome.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        cancelled shouldBe true
        val bytesAtCancel = receivedBytes
        Thread.sleep(300)
        // no further delivery after cancel
        receivedBytes shouldBe bytesAtCancel

        engine.close()
      } finally
        server.stop(0)
    }

    "route requests through the proxy from TransportSettings.proxyURL" in {
      withDumbProxy {
        (
          proxyPort,
          requestLine
        ) =>
          val engine = WSClientEngineRegistry(
            TransportSettings(proxyURL = Some(s"localhost:$proxyPort")),
            Some(expectedEngineId)
          )
          // never resolved or connected to - the proxy answers instead
          val site = SiteBinding(coreUrl = "http://target.example.com:8080")

          val response = Await.result(
            engine.execGETRich(site, "ping").map(engine.getResponseOrError),
            30.seconds
          )
          (response.json \ "status").get shouldBe JsString("proxied")
          requestLine() should startWith("GET http://target.example.com:8080/ping")

          engine.close()
      }
    }
  }
}
