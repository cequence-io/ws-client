package io.cequence.wsclient.service.ws

import com.sun.net.httpserver.HttpExchange
import io.cequence.wsclient.domain.{CequenceWSTimeoutException, SiteBinding, WsRequestContext}
import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.{
  EngineCapability,
  TransportSettings,
  WSClientEngineRegistry
}
import io.cequence.wsclient.testkit.TestServers.{
  CookieIsolation,
  cookieIsolation,
  MultipartInjection,
  multipartInjection,
  negotiatedProtocol,
  respond,
  selfSignedKeyStore,
  tlsAttempt,
  tlsProtocol,
  withDefaultTrustStore,
  withDumbProxy,
  withEchoServer,
  withServer,
  withSystemProperties,
  withTlsServer,
  withTrustingEngine
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}
import play.shaded.ahc.io.netty.handler.codec.compression.DecompressionException
import play.shaded.ahc.org.asynchttpclient.config.AsyncHttpClientConfigHelper

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import scala.concurrent.Await
import scala.concurrent.duration._

class PlayPekkoWSClientEngineProviderSpec extends AnyWordSpec with Matchers {

  implicit private val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private val expectedEngineId = "play-pekko"

  private val ping: HttpExchange => Unit = respond(_, 200, """{"status":"ok"}""")

  private lazy val keyStore = selfSignedKeyStore()

  private val AhcEnabledProtocolsKey = "play.shaded.ahc.org.asynchttpclient.enabledProtocols"

  private val AhcFollowRedirectKey = "play.shaded.ahc.org.asynchttpclient.followRedirect"

  private val AhcMaxDecompressedResponseSizeKey =
    "play.shaded.ahc.org.asynchttpclient.maxDecompressedResponseSize"

  // the shaded AHC caches its properties: reload after every change (and after the restore)
  private def withAhcProperty[T](
    key: String,
    value: Option[String]
  )(
    body: => T
  ): T =
    withSystemProperties(
      Seq(key -> value),
      AsyncHttpClientConfigHelper.reloadProperties()
    )(body)

  private def withAhcEnabledProtocols[T](value: Option[String])(body: => T): T =
    withAhcProperty(AhcEnabledProtocolsKey, value)(body)

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

    "keep no cookie jar: never send a cookie the site did not supply (site-stateless)" in {
      // the shaded AHC builder's default is ONE engine-wide cookie jar (Play's own
      // AhcConfigBuilder disables it) - it replayed a cookie one SiteBinding received on every
      // other SiteBinding's requests
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try {
        cookieIsolation(engine) shouldBe CookieIsolation.expected(streaming = false)

        // a copy builds its own client - from the same configuration
        val copy = engine.copy()
        try cookieIsolation(copy) shouldBe CookieIsolation.expected(streaming = false)
        finally copy.close()
      } finally engine.close()
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

    "never follow a redirect - not even with the shaded AHC's followRedirect property set" in {
      // the AHC builder's default comes from that JVM-wide property; following re-sent bodies
      // and custom secret headers (X-Api-Key; on AHC < 2.14.5 Authorization and Cookie too) to
      // whatever origin the Location named
      val stolen = new AtomicInteger(0)
      val steal: HttpExchange => Unit = { exchange =>
        stolen.incrementAndGet()
        respond(exchange, 200, "{}")
      }
      withServer("/" -> steal) { otherPort =>
        // 307 preserves the method and the body - the most dangerous redirect to follow
        val redirect: HttpExchange => Unit = { exchange =>
          exchange.getRequestBody.readAllBytes()
          exchange.getResponseHeaders.add("Location", s"http://localhost:$otherPort/steal")
          exchange.sendResponseHeaders(307, -1)
          exchange.close()
        }
        withServer("/" -> redirect) { port =>
          val site = SiteBinding(
            s"http://localhost:$port",
            WsRequestContext(authHeaders = Seq("X-Api-Key" -> "secret"))
          )
          Seq(None, Some("true")).foreach { followRedirect =>
            withAhcProperty(AhcFollowRedirectKey, followRedirect) {
              val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
              try {
                val response = Await.result(
                  engine
                    .execPOSTBodyRich(site, "upload", body = Json.obj("data" -> "private")),
                  30.seconds
                )
                withClue(s"followRedirect property = $followRedirect: ") {
                  response.status.code shouldBe 307
                  response.response shouldBe None
                  stolen.get() shouldBe 0
                }
              } finally engine.close()
            }
          }
        }
      }
    }

    "fail a response whose decompressed body exceeds the shaded AHC's limit" in {
      // AHC < 2.16.1 inflated gzip / deflate bodies without any limit - ~500 KB on the wire
      // exhausted a 768 MB heap (CVE-2026-85721)
      def gzip(bytes: Array[Byte]): Array[Byte] = {
        val out = new ByteArrayOutputStream()
        val gzipOut = new GZIPOutputStream(out)
        gzipOut.write(bytes)
        gzipOut.close()
        out.toByteArray
      }
      def serve(
        body: Array[Byte],
        gzipped: Boolean
      ): HttpExchange => Unit = {
        val bytes = if (gzipped) gzip(body) else body
        exchange => {
          if (gzipped) exchange.getResponseHeaders.add("Content-Encoding", "gzip")
          exchange.getResponseHeaders.add("Content-Type", "text/plain")
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
          exchange.close()
        }
      }
      val mebibyte = 1024 * 1024

      withServer(
        "/bomb" -> serve(new Array[Byte](4 * mebibyte), gzipped = true),
        "/compressed" -> serve(Array.fill(mebibyte / 2)('x'.toByte), gzipped = true),
        "/plain" -> serve(Array.fill(2 * mebibyte)('x'.toByte), gzipped = false)
      ) { port =>
        val site = SiteBinding(s"http://localhost:$port")
        def withEngine[T](maxDecompressedSize: Option[Int])(test: WSClientEngine => T): T =
          withAhcProperty(
            AhcMaxDecompressedResponseSizeKey,
            maxDecompressedSize.map(_.toString)
          ) {
            val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
            try test(engine)
            finally engine.close()
          }
        def bodyLength(
          engine: WSClientEngine,
          endPoint: String
        ) =
          Await
            .result(engine.execGETRich(site, endPoint), 30.seconds)
            .response
            .map(_.string.length)

        withEngine(Some(mebibyte)) { engine =>
          val failure = the[DecompressionException] thrownBy bodyLength(engine, "bomb")
          failure.getMessage should startWith(
            s"Decompressed response body exceeds the maximum of $mebibyte bytes"
          )
          // within the limit a compressed body is decompressed; an uncompressed one never counts
          bodyLength(engine, "compressed") shouldBe Some(mebibyte / 2)
          bodyLength(engine, "plain") shouldBe Some(2 * mebibyte)
        }
        // the default limit: 256 MiB
        withEngine(None)(bodyLength(_, "bomb")) shouldBe Some(4 * mebibyte)
      }
    }

    "create its client without initializing the JVM default SSLContext" in {
      // a client on SSLContext.getDefault (java.net.http, HttpsURLConnection - the jdk / sttp
      // engines) must still pick up a trust store configured AFTER this engine's first request:
      // the engine reads the JDK's protocol list from a throwaway context, never getDefault
      withServer("/ping" -> ping) { port =>
        val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
        try
          Await.result(
            engine
              .execGETRich(SiteBinding(s"http://localhost:$port"), "ping")
              .map(engine.getResponseOrError),
            30.seconds
          )
        finally
          engine.close()
      }

      withTlsServer(keyStore, Seq("TLSv1.3", "TLSv1.2"))("/" -> tlsProtocol) { port =>
        withDefaultTrustStore(keyStore) {
          val response = HttpClient
            .newHttpClient()
            .send(
              HttpRequest.newBuilder(URI.create(s"https://localhost:$port/tls")).build(),
              HttpResponse.BodyHandlers.ofString()
            )
          response.statusCode shouldBe 200
        }
      }
    }

    "speak TLS 1.3 (TLS-1.3-only hosts) and TLS 1.2, preferring 1.3" in {
      withTrustingEngine(keyStore, expectedEngineId) { engine =>
        negotiatedProtocol(engine, keyStore, Seq("TLSv1.3")) shouldBe "TLSv1.3"
        negotiatedProtocol(engine, keyStore, Seq("TLSv1.2")) shouldBe "TLSv1.2"
        negotiatedProtocol(engine, keyStore, Seq("TLSv1.3", "TLSv1.2")) shouldBe "TLSv1.3"
      }
    }

    "leave an explicitly configured AHC enabledProtocols list in force" in {
      // the no-code way to pin the protocols (also what enables TLS 1.3 on ws-client <= 1.1.1)
      withAhcEnabledProtocols(Some("TLSv1.2")) {
        withTrustingEngine(keyStore, expectedEngineId) { engine =>
          negotiatedProtocol(engine, keyStore, Seq("TLSv1.3", "TLSv1.2")) shouldBe "TLSv1.2"
        }
      }
    }

    "offer the JDK's default TLS client protocols unless AHC's enabledProtocols is configured" in {
      val jdkClientProtocols = SSLContext.getDefault.getDefaultSSLParameters.getProtocols.toSeq

      // AHC's shipped default is replaced (a changed shipped default would show up here)
      withAhcEnabledProtocols(None) {
        PlayWSClientEngine.tlsProtocols.map(_.toSeq) shouldBe Some(jdkClientProtocols)
      }
      // an explicit list stays in force: an empty one too (it means "Netty's defaults"), and
      // one that merely repeats AHC's shipped default
      Seq("TLSv1.2", "", "TLSv1.2, TLSv1.1, TLSv1").foreach { configured =>
        withAhcEnabledProtocols(Some(configured)) {
          PlayWSClientEngine.tlsProtocols shouldBe None
        }
      }
    }

    "treat an enabledProtocols key in the custom ahc.properties as configured, whatever its value" in {
      // the shaded AHC reads that resource relative to its config package - the lookup the
      // engine uses too, proven by finding AHC's own shipped defaults there
      PlayWSClientEngine
        .ahcConfigResource("ahc-default.properties")
        .map(_.getProperty(AhcEnabledProtocolsKey).replace(" ", "")) shouldBe
        Some("TLSv1.2,TLSv1.1,TLSv1")
      PlayWSClientEngine.customAhcProperties shouldBe None // none on this test classpath

      def properties(entries: (String, String)*) = {
        val result = new java.util.Properties
        entries.foreach { case (key, value) => result.setProperty(key, value) }
        result
      }

      withAhcEnabledProtocols(None) {
        // the key set to exactly the shipped list is still an explicit choice
        PlayWSClientEngine.tlsProtocols(
          Some(properties(AhcEnabledProtocolsKey -> "TLSv1.2,TLSv1.1,TLSv1"))
        ) shouldBe None
        // an unrelated ahc.properties is not
        PlayWSClientEngine
          .tlsProtocols(
            Some(properties("play.shaded.ahc.org.asynchttpclient.maxConnections" -> "5"))
          )
          .isDefined shouldBe true
      }
    }

    "reject a server certificate the JVM does not trust" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(expectedEngineId))
      try {
        val attempt = tlsAttempt(engine, keyStore, Seq("TLSv1.3", "TLSv1.2"))
        attempt.result match {
          case Left(failure) => failure should not be a[CequenceWSTimeoutException]
          case Right(protocol) =>
            fail(s"accepted an untrusted certificate (negotiated $protocol)")
        }
        attempt.served shouldBe 0
      } finally
        engine.close()
    }

    "reject a trusted certificate that does not name the host" in {
      // a certificate for localhost only (no IP SAN): fine via localhost, rejected via 127.0.0.1
      val localhostOnly = selfSignedKeyStore(Seq("dns:localhost"))

      withTrustingEngine(localhostOnly, expectedEngineId) { engine =>
        negotiatedProtocol(engine, localhostOnly, Seq("TLSv1.3")) shouldBe "TLSv1.3"

        val attempt =
          tlsAttempt(engine, localhostOnly, Seq("TLSv1.3", "TLSv1.2"), host = "127.0.0.1")
        attempt.result match {
          case Left(failure) => failure should not be a[CequenceWSTimeoutException]
          case Right(protocol) =>
            fail(s"accepted a certificate that does not name the host (negotiated $protocol)")
        }
        attempt.served shouldBe 0
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
