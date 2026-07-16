package io.cequence.wsclient.service.ws

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.cequence.wsclient.domain.{
  CequenceWSTimeoutException,
  CequenceWSUnknownHostException,
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

import java.io.{File, PrintWriter}
import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration._

class SttpWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "sttp"

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
      providers.head.capabilities shouldBe Set(EngineCapability.Multipart)
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
        (response.json \ "contentType").get.as[String] should startWith("application/json")
        Json.parse((response.json \ "body").get.as[String]) shouldBe Json.obj("name" -> "John")

        engine.close()
      }
    }

    "POST an in-memory multipart body with a Content-Length" in {
      withEchoServer { port =>
        val file = File.createTempFile("ws-client-sttp-test", ".txt")
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
        body should include("name=purpose")
        body should include("test")

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
    // engine.copy() - a new engine with its own OWNED backend rebuilt from
    // possibly-changed TransportSettings, closed independently of the one it was
    // copied from
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

        // closing the copy leaves the original working - it owns a separate backend
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
