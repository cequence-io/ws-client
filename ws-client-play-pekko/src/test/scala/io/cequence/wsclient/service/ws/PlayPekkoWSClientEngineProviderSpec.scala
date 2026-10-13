package io.cequence.wsclient.service.ws

import com.sun.net.httpserver.HttpExchange
import io.cequence.wsclient.domain.{SiteBinding, WsRequestContext}
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

import scala.concurrent.Await
import scala.concurrent.duration._

class PlayPekkoWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-pekko"

  private val ping: HttpExchange => Unit = respond(_, 200, """{"status":"ok"}""")

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
  }
}
