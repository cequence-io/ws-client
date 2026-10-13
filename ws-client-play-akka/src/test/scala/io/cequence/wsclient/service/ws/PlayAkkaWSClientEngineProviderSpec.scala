package io.cequence.wsclient.service.ws

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.sun.net.httpserver.HttpExchange
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
import io.cequence.wsclient.testkit.TestServers.{
  respond,
  selfSignedKeyStore,
  tlsProtocol,
  withDefaultTrustStore,
  withDumbProxy,
  withEchoServer,
  withServer,
  withTlsServer
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.JsString

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class PlayAkkaWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-akka"

  private val ping: HttpExchange => Unit = respond(_, 200, """{"status":"ok"}""")

  private val slowPing: HttpExchange => Unit = exchange => {
    Thread.sleep(2000)
    respond(exchange, 200, """{"status":"ok"}""")
  }

  s"$expectedEngineId provider" should {

    "be discovered via ServiceLoader with the expected id and capabilities" in {
      val providers = WSClientEngineRegistry.providers

      providers.map(_.engineId) shouldBe Seq(expectedEngineId)

      val provider = providers.head
      provider.capabilities should contain allOf (
        EngineCapability.InputStreaming,
        EngineCapability.Multipart
      )
    }

    "create a working engine that can GET, and close idempotently" in {
      withServer("/ping" -> ping) { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:${port}")

        val response = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        engine.close()
        noException should be thrownBy engine.close()
      }
    }

    "speak TLS 1.3 (TLS-1.3-only hosts) and TLS 1.2, preferring 1.3" in {
      val keyStore = selfSignedKeyStore()

      Seq(
        Seq("TLSv1.3") -> "TLSv1.3",
        Seq("TLSv1.2") -> "TLSv1.2",
        Seq("TLSv1.3", "TLSv1.2") -> "TLSv1.3"
      ).foreach { case (serverProtocols, expectedProtocol) =>
        withTlsServer(keyStore, serverProtocols)("/" -> tlsProtocol) { port =>
          // the engine's client captures the trust store when it is created (first request)
          val protocol = withDefaultTrustStore(keyStore) {
            val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
            try {
              val response = Await.result(
                engine
                  .execGETRich(SiteBinding(s"https://localhost:$port"), "tls")
                  .map(engine.getResponseOrError),
                30.seconds
              )
              (response.json \ "protocol").as[String]
            } finally
              engine.close()
          }
          protocol shouldBe expectedProtocol
        }
      }
    }

    "POST repeated form keys in an in-memory multipart body, and a streamed one under the file's base name" in {
      withEchoServer { port =>
        val file = java.io.File.createTempFile("ws-client-play-", ".txt")
        file.deleteOnExit()
        val writer = new java.io.PrintWriter(file)
        writer.print("file-content")
        writer.close()

        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:$port")
        def post(useInMemoryBody: Boolean) =
          (Await
            .result(
              engine
                .execPOSTMultipartRich(
                  site,
                  "upload",
                  fileParams = Seq(("file", file, None)),
                  bodyParams = Seq("tag" -> Some("first"), "tag" -> Some("second")),
                  useInMemoryBody = useInMemoryBody
                )
                .map(engine.getResponseOrError),
              30.seconds
            )
            .json \ "body").as[String]

        Seq(true, false).foreach { inMemory =>
          val body = post(inMemory)
          withClue(s"useInMemoryBody = $inMemory: ") {
            """name="?tag"?\r\n""".r.findAllMatchIn(body).size shouldBe 2
            body should include("first")
            body should include("second")
            body should include(s"""filename="${file.getName}"""")
            body should not include file.getParent
          }
        }

        engine.close()
      }
    }

    "POST every value of a repeated URL-encoded form key" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val body = (Await
          .result(
            engine
              .execPOSTURLEncodedRich(
                SiteBinding(s"http://localhost:$port"),
                "form",
                bodyParams =
                  Seq("color" -> Some("red"), "size" -> Some("L"), "color" -> Some("blue"))
              )
              .map(engine.getResponseOrError),
            30.seconds
          )
          .json \ "body").as[String]

        // grouped by key (first-occurrence order), every value kept
        body shouldBe "color=red&color=blue&size=L"

        engine.close()
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

    "serve two independent sites (auth, servers) from one engine" in {
      withEchoServer { portA =>
        withEchoServer { portB =>
          val system = ActorSystem("play-akka-engine-sharing-spec")
          implicit val materializer: Materializer = Materializer(system)
          implicit val ec: ExecutionContext = system.dispatcher

          val engine = new PlayWSClientEngine()
          try {
            val siteA = SiteBinding(
              coreUrl = s"http://localhost:$portA",
              requestContext =
                WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer A"))
            )
            val siteB = SiteBinding(
              coreUrl = s"http://localhost:$portB",
              requestContext =
                WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer B"))
            )

            val responseA = Await.result(
              engine.execGETRich(siteA, "ping").map(engine.getResponseOrError),
              30.seconds
            )
            val responseB = Await.result(
              engine.execGETRich(siteB, "ping").map(engine.getResponseOrError),
              30.seconds
            )

            (responseA.json \ "auth").get shouldBe JsString("Bearer A")
            (responseB.json \ "auth").get shouldBe JsString("Bearer B")
          } finally {
            engine.close()
            system.terminate()
            ()
          }
        }
      }
    }

    "run its owned actor system on daemon threads (leaked engines must not block JVM exit)" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
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
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
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

    "terminate its owned actor system on close (provider path)" in {
      val engine = new PlayAkkaWSClientEngineProvider()
        .newEngine(TransportSettings())
        .asInstanceOf[PlayWSClientEngine]
      val system = engine.materializer.system

      engine.close()

      Await.result(system.whenTerminated, 10.seconds)
    }

    "copy() with reuseExecContext=true (default) shares this engine's actor system" in {
      withEchoServer { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSClientEngine]
        val site = SiteBinding(s"http://localhost:$port")

        val copy = engine.copy()

        copy.materializer.system should be theSameInstanceAs engine.materializer.system

        val response = Await.result(
          copy.execGETRich(site, "ping").map(copy.getResponseOrError),
          30.seconds
        )
        (response.json \ "query").get shouldBe JsString("")

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
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
          .asInstanceOf[PlayWSClientEngine]
        val site = SiteBinding(s"http://localhost:${port}")

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
          .asInstanceOf[PlayWSClientEngine]
        val site = SiteBinding(s"http://localhost:$port")

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
        (response.json \ "query").get shouldBe JsString("")

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
      val testSystem = ActorSystem("play-akka-engine-spec-copy-throws")
      try {
        val engine = PlayWSClientEngine()(Materializer(testSystem), testSystem.dispatcher)

        a[CequenceWSException] should be thrownBy engine.copy(reuseExecContext = false)

        engine.close()
      } finally {
        testSystem.terminate()
        ()
      }
    }
  }
}
