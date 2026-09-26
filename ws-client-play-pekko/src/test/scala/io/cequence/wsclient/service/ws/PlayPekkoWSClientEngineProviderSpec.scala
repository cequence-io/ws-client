package io.cequence.wsclient.service.ws

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.cequence.wsclient.domain.{SiteBinding, WsRequestContext}
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineRegistry
}
import io.cequence.wsclient.testkit.TestServers.withEchoServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration._

class PlayPekkoWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-pekko"

  // echoes the raw query string back as JSON
  private def withQueryEchoServer(test: (Int) => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.createContext(
      "/",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val query = Option(exchange.getRequestURI.getRawQuery).getOrElse("")
          val auth = Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          val response =
            Json.obj("query" -> query, "auth" -> auth).toString.getBytes("UTF-8")
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

      val provider = providers.head
      provider.capabilities should contain allOf (
        EngineCapability.InputStreaming,
        EngineCapability.Multipart
      )
    }

    "create a working engine that can GET, and close idempotently" in {
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
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        val site = SiteBinding(s"http://localhost:${server.getAddress.getPort}")

        val response = Await.result(
          engine.execGETRich(site, "ping").map(engine.getResponseOrError),
          30.seconds
        )
        (response.json \ "status").get shouldBe JsString("ok")

        engine.close()
        noException should be thrownBy engine.close()
      } finally
        server.stop(0)
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
      withQueryEchoServer { port =>
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
      withQueryEchoServer { port =>
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
