package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.{
  CequenceWSException,
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
import io.cequence.wsclient.testkit.LatchedSubscriber
import io.cequence.wsclient.testkit.TestServers.{
  respond,
  sse,
  withDumbProxy,
  withEchoServer,
  withServer
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, JsValue, Json}

import java.io.{File, PrintWriter}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class ZioHttpWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "zio-http"

  private def newEngine(settings: TransportSettings = TransportSettings()) =
    WSClientEngineRegistry(settings, Some(expectedEngineId))

  private def get(
    engine: io.cequence.wsclient.service.WSClientEngine,
    site: SiteBinding,
    endPoint: String = "ping",
    params: Seq[(String, Option[Any])] = Nil
  ) =
    Await.result(engine.execGETRich(site, endPoint, params = params), 30.seconds)

  private def tempFile(content: String): File = {
    val file = File.createTempFile("ws-client-zio-", ".txt")
    file.deleteOnExit()
    val writer = new PrintWriter(file)
    try writer.write(content)
    finally writer.close()
    file
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
        val engine = newEngine()
        val response =
          engine.getResponseOrError(get(engine, SiteBinding(s"http://localhost:$port")))

        (response.json \ "status").get shouldBe JsString("ok")
        (response.json \ "method").get shouldBe JsString("GET")

        engine.close()
        noException should be thrownBy engine.close()
      }
    }

    "own only daemon threads (a leaked engine must not block JVM exit)" in {
      withEchoServer { port =>
        // only the thread families this engine can create - suites of other modules run
        // concurrently in the same JVM (e.g. their JDK test servers' "HTTP-Dispatcher")
        def engineThread(t: Thread) = {
          val name = t.getName
          name.startsWith("ws-client-zio-http") || name.contains("IoEventLoopGroup") ||
          name.startsWith("zio-")
        }

        val before = Thread.getAllStackTraces.keySet.asScala.toSet
        val engine = newEngine()
        get(engine, SiteBinding(s"http://localhost:$port"))

        val created =
          Thread.getAllStackTraces.keySet.asScala.toSet.diff(before).filter(engineThread)

        engine.close()
        // the daemon driver is in use (no silent fallback to zio-http's stock driver)...
        created.map(_.getName).exists(_.startsWith("ws-client-zio-http")) shouldBe true
        // ...and nothing the engine started can hold the JVM
        created.filter(t => t.isAlive && !t.isDaemon).map(_.getName) shouldBe empty
      }
    }

    "POST a JSON body" in {
      withEchoServer { port =>
        val engine = newEngine()
        val response = Await.result(
          engine
            .execPOSTBodyRich(
              SiteBinding(s"http://localhost:$port"),
              "items",
              body = Json.obj("name" -> "O'Reilly")
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "method").get shouldBe JsString("POST")
        (response.json \ "contentType").get shouldBe JsString("application/json")
        Json.parse((response.json \ "body").as[String]) shouldBe Json.obj("name" -> "O'Reilly")

        engine.close()
      }
    }

    "POST a streamed multipart body under the file's base name, never its path" in {
      withEchoServer { port =>
        val engine = newEngine()
        val file = tempFile("file-content")

        val response = Await.result(
          engine
            .execPOSTMultipartRich(
              SiteBinding(s"http://localhost:$port"),
              "upload",
              fileParams = Seq(("file", file, None)),
              bodyParams = Seq("purpose" -> Some("test"))
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "contentType").as[String] should startWith("multipart/form-data")
        val body = (response.json \ "body").as[String]
        body should include("file-content")
        body should include("name=\"purpose\"")
        body should include(s"""filename="${file.getName}"""")
        body should not include file.getParent

        engine.close()
      }
    }

    "POST an in-memory multipart body with a Content-Length" in {
      withEchoServer { port =>
        val engine = newEngine()
        val response = Await.result(
          engine
            .execPOSTMultipartRich(
              SiteBinding(s"http://localhost:$port"),
              "upload",
              fileParams = Seq(("file", tempFile("in-memory"), Some("display.txt"))),
              useInMemoryBody = true
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "contentLength").as[String] should not be empty
        (response.json \ "transferEncoding").as[String] shouldBe empty
        (response.json \ "body").as[String] should include("filename=\"display.txt\"")

        engine.close()
      }
    }

    "honor a caller-provided Content-Type without duplicating it" in {
      withEchoServer { port =>
        val engine = newEngine()
        val response = Await.result(
          engine
            .execPOSTBodyRich(
              SiteBinding(s"http://localhost:$port"),
              "items",
              body = Json.obj("a" -> 1),
              extraHeaders = Seq("Content-Type" -> "application/vnd.custom+json")
            )
            .map(engine.getResponseOrError),
          30.seconds
        )

        (response.json \ "contentTypes").get shouldBe JsString("application/vnd.custom+json")

        engine.close()
      }
    }

    "merge (raw, percent-encoded) query params with a query embedded in the core URL" in {
      withEchoServer { port =>
        val engine = newEngine()
        val response = engine.getResponseOrError(
          get(
            engine,
            SiteBinding(s"http://localhost:$port/v1?api-version=2024-02-01"),
            params = Seq("q" -> Some("a b&c"))
          )
        )

        val query = (response.json \ "query").as[String]
        query should include("api-version=2024-02-01")
        query should (include("q=a%20b%26c") or include("q=a+b%26c"))

        engine.close()
      }
    }

    "evaluate a dynamic request context exactly once per request" in {
      withEchoServer { port =>
        val evaluations = new AtomicInteger(0)
        val engine = newEngine()
        val site = SiteBinding(
          s"http://localhost:$port",
          requestContextFun = Some { () =>
            val n = evaluations.incrementAndGet()
            WsRequestContext(
              authHeaders = Seq("Authorization" -> s"Bearer token-$n"),
              extraParams = Seq("tenant" -> s"tenant-$n")
            )
          }
        )

        val response = engine.getResponseOrError(get(engine, site))

        evaluations.get() shouldBe 1
        (response.json \ "auth").get shouldBe JsString("Bearer token-1")
        (response.json \ "query").as[String] should include("tenant=tenant-1")

        engine.close()
      }
    }

    "not follow a redirect to another origin" in {
      val stolen = new AtomicInteger(0)
      withServer("/" -> { exchange => stolen.incrementAndGet(); respond(exchange, 200, "") }) {
        attackerPort =>
          withServer("/" -> { exchange =>
            exchange.getRequestBody.readAllBytes()
            exchange.getResponseHeaders
              .add("Location", s"http://localhost:$attackerPort/steal")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
          }) { port =>
            val engine = newEngine()
            val site = SiteBinding(
              s"http://localhost:$port",
              requestContext = WsRequestContext(authHeaders = Seq("X-Api-Key" -> "secret"))
            )

            val response = Await.result(
              engine.execPOSTBodyRich(site, "upload", body = Json.obj("data" -> "private")),
              30.seconds
            )

            response.status.code shouldBe 307
            response.response shouldBe None
            stolen.get() shouldBe 0

            engine.close()
          }
      }
    }

    "map a DNS resolution failure to CequenceWSUnknownHostException" in {
      val engine = newEngine()
      val failure = Await.result(
        engine.execGETRich(SiteBinding("http://no-such-host.invalid"), "ping").failed,
        30.seconds
      )

      failure shouldBe a[CequenceWSUnknownHostException]
      engine.close()
    }

    "apply user recoverErrors to the normalized (Cequence) exception" in {
      val engine = newEngine()
      val site = SiteBinding(
        "http://no-such-host.invalid",
        recoverErrors = Some(_ => { case _: CequenceWSUnknownHostException =>
          SimpleRichResponse(None, StatusData(599, "recovered"), Map.empty)
        })
      )

      Await.result(engine.execGETRich(site, "ping"), 30.seconds).status shouldBe StatusData(
        599,
        "recovered"
      )
      engine.close()
    }

    "time out per TransportSettings.timeouts.requestTimeout" in {
      withServer("/" -> { exchange =>
        Thread.sleep(3000)
        respond(exchange, 200, "{}")
      }) { port =>
        val engine =
          newEngine(TransportSettings(timeouts = Timeouts(requestTimeout = Some(300))))

        val failure = Await.result(
          engine.execGETRich(SiteBinding(s"http://localhost:$port"), "slow").failed,
          30.seconds
        )

        failure shouldBe a[CequenceWSTimeoutException]
        engine.close()
      }
    }

    "copy() on the shared driver with its own timeouts, closing independently" in {
      withServer("/slow" -> { exchange =>
        Thread.sleep(1500)
        respond(exchange, 200, """{"status":"slow"}""")
      }) { port =>
        val engine = newEngine()
        val site = SiteBinding(s"http://localhost:$port")
        val fastTimeoutCopy =
          engine.copy(TransportSettings(timeouts = Timeouts(requestTimeout = Some(300))))

        Await
          .result(fastTimeoutCopy.execGETRich(site, "slow").failed, 30.seconds)
          .shouldBe(a[CequenceWSTimeoutException])

        fastTimeoutCopy.close()
        // the original's driver survives the copy's close
        (engine.getResponseOrError(get(engine, site, "slow")).json \ "status").get shouldBe
          JsString("slow")

        engine.close()
      }
    }

    "run on a caller-owned Client and never close it" in {
      withEchoServer { port =>
        val clientRuntime = zio.Unsafe.unsafe { implicit u =>
          zio.Runtime.unsafe.fromLayer(zio.http.Client.default)
        }
        implicit val runtime: zio.Runtime[Any] = clientRuntime
        val client = clientRuntime.environment.get[zio.http.Client]
        val site = SiteBinding(s"http://localhost:$port")

        val engine = ZioHttpWSClientEngine(client)
        (engine.getResponseOrError(get(engine, site)).json \ "status").get shouldBe JsString(
          "ok"
        )
        engine.close()

        // still usable after the engine's close
        val second = ZioHttpWSClientEngine(client)
        (second.getResponseOrError(get(second, site)).json \ "status").get shouldBe JsString(
          "ok"
        )

        zio.Unsafe.unsafe(implicit u => clientRuntime.unsafe.shutdown())
      }
    }

    "provide the engine as a ZLayer over the application's Client" in {
      withEchoServer { port =>
        val site = SiteBinding(s"http://localhost:$port")
        val program = zio.ZIO.serviceWithZIO[ZioHttpWSClientEngine](engine =>
          zio.ZIO.fromFuture(_ =>
            engine.execGETRich(site, "ping").map(engine.getResponseOrError)
          )
        )

        val response = zio.Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe
            .run(program.provide(zio.http.Client.default, ZioHttpWSClientEngine.layer()))
            .getOrThrowFiberFailure()
        }
        (response.json \ "status").get shouldBe JsString("ok")
      }
    }

    "route requests through the proxy from TransportSettings.proxyURL (CONNECT)" in {
      withDumbProxy {
        (
          proxyPort,
          requestLine
        ) =>
          val engine = newEngine(TransportSettings(proxyURL = Some(s"localhost:$proxyPort")))

          // zio-http resolves the TARGET host locally (the proxy then tunnels to the resolved
          // address), so the target must be resolvable here; nothing listens on it - the stub
          // proxy is not a real tunnel either, so the exchange itself fails. What matters is that
          // the connection went to the proxy as a CONNECT
          scala.util.Try(
            Await.result(
              engine.execGETRich(SiteBinding("http://localhost:1"), "ping"),
              30.seconds
            )
          )

          requestLine() should startWith("CONNECT ")
          requestLine() should include(":1 ")
          engine.close()
      }
    }

    // -----------------------------------------------------------------------------------
    // Output streaming via the backend-agnostic Flow.Publisher contract
    // -----------------------------------------------------------------------------------

    "stream SSE JSON events through WSClientEngineRegistry.outputStreamed, lazily, up to [DONE]" in {
      val requestCount = new AtomicInteger(0)
      withServer(
        "/events" -> sse(
          "data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\ndata: {\"i\":3}\n\n",
          requestCount
        )
      ) { port =>
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        val publisher =
          engine.execJsonStreamPublisher(
            SiteBinding(s"http://localhost:$port"),
            "events",
            "GET"
          )

        // COLD: creating the publisher must not fire the request
        Thread.sleep(200)
        requestCount.get() shouldBe 0

        val subscriber = new LatchedSubscriber[JsValue]
        publisher.subscribe(subscriber)
        subscriber.awaitDone(30) shouldBe true

        requestCount.get() shouldBe 1
        subscriber.received shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
        subscriber.completed shouldBe true
        subscriber.error shouldBe None

        // SINGLE-SHOT: a second subscriber is rejected
        val second = new LatchedSubscriber[JsValue]
        publisher.subscribe(second)
        second.awaitDone(5)
        second.error.map(_.getClass.getSimpleName) shouldBe Some("IllegalStateException")

        engine.close()
      }
    }

    "fail a stream on a non-2xx status instead of parsing the error page" in {
      withServer("/events" -> { exchange =>
        respond(exchange, 401, """{"error":"invalid api key"}""")
      }) { port =>
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))
        val subscriber = new LatchedSubscriber[JsValue]
        engine
          .execJsonStreamPublisher(SiteBinding(s"http://localhost:$port"), "events", "POST")
          .subscribe(subscriber)

        subscriber.awaitDone(30) shouldBe true
        subscriber.received shouldBe empty
        subscriber.error.get shouldBe a[CequenceWSException]
        subscriber.error.get.getMessage should include("HTTP 401")

        engine.close()
      }
    }

    "deliver raw bytes and stop after downstream cancellation" in {
      withServer("/raw" -> { exchange =>
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
      }) { port =>
        val engine =
          WSClientEngineRegistry.outputStreamed(TransportSettings(), Some(expectedEngineId))

        @volatile var receivedBytes = 0
        @volatile var cancelled = false
        val gotSome = new java.util.concurrent.CountDownLatch(1)

        engine
          .execRawStreamPublisher(
            SiteBinding(s"http://localhost:$port"),
            "raw",
            "GET",
            None,
            Nil,
            Nil,
            Nil
          )
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
        receivedBytes shouldBe bytesAtCancel

        engine.close()
      }
    }
  }

}
