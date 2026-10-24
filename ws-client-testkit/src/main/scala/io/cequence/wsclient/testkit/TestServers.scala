package io.cequence.wsclient.testkit

import com.sun.net.httpserver.{
  HttpExchange,
  HttpHandler,
  HttpServer,
  HttpsConfigurator,
  HttpsExchange,
  HttpsParameters,
  HttpsServer
}
import io.cequence.wsclient.domain.{SiteBinding, WsRequestContext}
import io.cequence.wsclient.service.{WSClientEngine, WSClientOutputStreamCore}
import io.cequence.wsclient.service.spi.{TransportSettings, WSClientEngineRegistry}
import play.api.libs.json.{JsValue, Json}

import java.io.File
import java.net.{InetSocketAddress, ServerSocket}
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.{Executors, ThreadFactory, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/**
 * Embedded servers and TLS fixtures for the engine specs (test-only module, never published).
 * Each server helper binds an ephemeral port, runs the test with it, and stops the server
 * afterwards.
 */
object TestServers {

  /**
   * A server with the given `path -> handler` contexts; returns the test's result.
   */
  def withServer[T](
    contexts: (String, HttpExchange => Unit)*
  )(
    test: Int => T
  ): T =
    serve(HttpServer.create(new InetSocketAddress(0), 0), contexts)(test)

  // registers the contexts, runs `test` with the bound port and stops the server. Handlers run
  // on a daemon thread pool: the JDK default runs them one at a time on the dispatcher thread,
  // so a slow handler (e.g. serving a request its client has already timed out) would stall
  // the next request
  private def serve[T](
    server: HttpServer,
    contexts: Seq[(String, HttpExchange => Unit)]
  )(
    test: Int => T
  ): T = {
    val executor = Executors.newCachedThreadPool(new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = {
        val thread = new Thread(runnable, "ws-client-test-server")
        thread.setDaemon(true)
        thread
      }
    })
    server.setExecutor(executor)
    contexts.foreach { case (path, handler) =>
      server.createContext(
        path,
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = handler(exchange)
        }
      )
    }
    server.start()
    try
      test(server.getAddress.getPort)
    finally {
      server.stop(0)
      executor.shutdownNow()
    }
  }

  def respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
    contentType: String = "application/json"
  ): Unit = {
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  /**
   * Echoes the request as JSON: `status` ("ok"), `method`, `contentType`, `contentTypes` (all
   * values comma-joined - catches duplicated headers), `transferEncoding`, `contentLength`,
   * `query` (raw), `auth` (Authorization), `apiKey` (X-Api-Key), `body`.
   */
  def withEchoServer[T](test: Int => T): T =
    withServer("/" -> echo)(test)

  val echo: HttpExchange => Unit = exchange => {
    def header(name: String) =
      Option(exchange.getRequestHeaders.getFirst(name)).getOrElse[String]("")

    val requestBody = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
    val response = Json.obj(
      "status" -> "ok",
      "method" -> exchange.getRequestMethod,
      "contentType" -> header("Content-Type"),
      "contentTypes" -> Option(exchange.getRequestHeaders.get("Content-Type"))
        .map(String.join(",", _))
        .getOrElse[String](""),
      "transferEncoding" -> header("Transfer-encoding"),
      "contentLength" -> header("Content-length"),
      "query" -> Option(exchange.getRequestURI.getRawQuery).getOrElse[String](""),
      "auth" -> header("Authorization"),
      "apiKey" -> header("X-Api-Key"),
      "body" -> requestBody
    )
    respond(exchange, 200, response.toString)
  }

  /**
   * Serves a fixed SSE body (`text/event-stream`), counting the requests it receives.
   */
  def sse(
    body: String,
    requestCount: java.util.concurrent.atomic.AtomicInteger =
      new java.util.concurrent.atomic.AtomicInteger(0)
  ): HttpExchange => Unit = exchange => {
    requestCount.incrementAndGet()
    exchange.getRequestBody.readAllBytes()
    respond(exchange, 200, body, "text/event-stream")
  }

  /**
   * A single-connection-at-a-time HTTP proxy stub: captures the first request line
   * (absolute-form `GET http://host/path` for proxied plain-http requests, `CONNECT host:port`
   * for tunnelling clients) and answers every connection with `{"status":"proxied"}`.
   */
  def withDumbProxy(test: (Int, () => String) => Unit): Unit = {
    val server = new ServerSocket(0)
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
    thread.setDaemon(true)
    thread.start()

    try
      test(server.getLocalPort, () => requestLine)
    finally
      server.close()
  }

  final case class TestKeyStore(
    file: File,
    password: String
  )

  /**
   * A fresh PKCS12 keystore with a self-signed certificate for the given subject alternative
   * names - by default `localhost` and `127.0.0.1` (generated by the JDK's keytool) - the key
   * for [[withTlsServer]], and usable as a trust store.
   */
  def selfSignedKeyStore(
    subjectAltNames: Seq[String] = Seq("dns:localhost", "ip:127.0.0.1")
  ): TestKeyStore = {
    val dir = Files.createTempDirectory("ws-client-tls-").toFile
    val file = new File(dir, "localhost.p12")
    // deleteOnExit runs in REVERSE registration order: register the directory first so its file
    // is gone by the time it is deleted (and both before keytool runs, in case keytool fails)
    dir.deleteOnExit()
    file.deleteOnExit()
    val password = "test-password"
    val keytool = new File(new File(System.getProperty("java.home"), "bin"), "keytool")

    val process = new ProcessBuilder(
      keytool.getPath,
      "-genkeypair",
      "-alias",
      "localhost",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-dname",
      "CN=localhost",
      "-ext",
      s"SAN=${subjectAltNames.mkString(",")}",
      "-validity",
      "2",
      "-storetype",
      "PKCS12",
      "-keystore",
      file.getPath,
      "-storepass",
      password,
      "-keypass",
      password
    ).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), "UTF-8")
    require(process.waitFor() == 0, s"keytool failed: $output")

    TestKeyStore(file, password)
  }

  /**
   * An HTTPS server (certificate from `keyStore`) that accepts ONLY the given TLS `protocols`
   * (e.g. `Seq("TLSv1.3")`), with the given `path -> handler` contexts; returns the test's
   * result.
   */
  def withTlsServer[T](
    keyStore: TestKeyStore,
    protocols: Seq[String]
  )(
    contexts: (String, HttpExchange => Unit)*
  )(
    test: Int => T
  ): T = {
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagers.init(
      KeyStore.getInstance(keyStore.file, keyStore.password.toCharArray),
      keyStore.password.toCharArray
    )
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagers.getKeyManagers, null, null)

    val server = HttpsServer.create(new InetSocketAddress(0), 0)
    server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
      override def configure(params: HttpsParameters): Unit = {
        val sslParameters = sslContext.getDefaultSSLParameters
        sslParameters.setProtocols(protocols.toArray)
        params.setSSLParameters(sslParameters)
      }
    })
    serve(server, contexts)(test)
  }

  /**
   * Responds with the TLS protocol the connection negotiated - `{"protocol": "TLSv1.3"}`.
   */
  val tlsProtocol: HttpExchange => Unit = exchange => {
    exchange.getRequestBody.readAllBytes()
    val protocol = exchange.asInstanceOf[HttpsExchange].getSSLSession.getProtocol
    respond(exchange, 200, Json.obj("protocol" -> protocol).toString)
  }

  /**
   * Runs `body` with the given system properties set (`None` clears one), restoring the
   * previous values afterwards; `afterChange` runs after every change - the initial one and
   * the restore - for consumers that cache the properties (e.g. the shaded AHC's config).
   * JVM-GLOBAL: use only in a forked test JVM whose suites run sequentially.
   */
  def withSystemProperties[T](
    properties: Seq[(String, Option[String])],
    afterChange: => Unit = ()
  )(
    body: => T
  ): T = {
    def set(values: Seq[(String, Option[String])]): Unit = {
      values.foreach {
        case (key, Some(value)) => System.setProperty(key, value)
        case (key, None)        => System.clearProperty(key)
      }
      afterChange
    }

    val previous = properties.map { case (key, _) => key -> Option(System.getProperty(key)) }
    set(properties)
    try body
    finally set(previous)
  }

  /**
   * Runs `body` with the JVM's default trust store (`javax.net.ssl.trustStore`) pointed at
   * `keyStore`, restoring the previous settings afterwards - for clients with no hook for a
   * custom trust store. TLS clients capture the trust store when they build their SSL context,
   * so create (and first use) the client under test INSIDE `body`. JVM-GLOBAL: use only in a
   * forked test JVM whose suites run sequentially.
   */
  def withDefaultTrustStore[T](keyStore: TestKeyStore)(body: => T): T =
    withSystemProperties(
      Seq(
        "javax.net.ssl.trustStore" -> Some(keyStore.file.getPath),
        "javax.net.ssl.trustStorePassword" -> Some(keyStore.password),
        "javax.net.ssl.trustStoreType" -> Some("PKCS12")
      )
    )(body)

  /**
   * Runs `body` with a fresh discovery-created engine (`engineId`) whose client trusts the
   * certificate in `keyStore`, and closes the engine afterwards. The client captures the trust
   * store when it is created - on the first request - so the engine is created and first used
   * inside [[withDefaultTrustStore]]. That works for clients building their own `SSLContext`
   * from the `javax.net.ssl.*` properties (the Play engines' shaded AHC), not for clients on
   * the `SSLContext.getDefault` singleton (jdk / sttp), which is initialized once per JVM.
   */
  def withTrustingEngine[T](
    keyStore: TestKeyStore,
    engineId: String
  )(
    body: WSClientEngine => T
  ): T =
    withDefaultTrustStore(keyStore) {
      val engine = WSClientEngineRegistry(TransportSettings(), Some(engineId))
      try body(engine)
      finally engine.close()
    }

  final case class TlsAttempt(
    result: Either[Throwable, String],
    served: Int
  )

  /**
   * One GET through `engine` to `https://$host:$port` of a [[withTlsServer]] (certificate from
   * `keyStore`, accepting only `serverProtocols`): the negotiated protocol or the failure,
   * plus how many requests reached the server - none when TLS rejects the connection
   * (untrusted certificate, host name mismatch), which tells a rejection from a later failure.
   * A request that does not complete within 30 s fails the caller with a `TimeoutException`.
   */
  def tlsAttempt(
    engine: WSClientEngine,
    keyStore: TestKeyStore,
    serverProtocols: Seq[String],
    host: String = "localhost"
  )(
    implicit ec: ExecutionContext
  ): TlsAttempt = {
    val served = new AtomicInteger(0)
    val countingTlsProtocol: HttpExchange => Unit = { exchange =>
      served.incrementAndGet()
      tlsProtocol(exchange)
    }

    withTlsServer(keyStore, serverProtocols)("/" -> countingTlsProtocol) { port =>
      val request = engine
        .execGETRich(SiteBinding(s"https://$host:$port"), "tls")
        .map(engine.getResponseOrError)
      val result = Await
        .ready(request, 30.seconds)
        .value
        .get
        .map(response => (response.json \ "protocol").as[String])
        .toEither
      TlsAttempt(result, served.get)
    }
  }

  /**
   * The TLS protocol `engine` negotiates with a [[withTlsServer]] (certificate from
   * `keyStore`) that accepts only `serverProtocols` - e.g. `"TLSv1.3"`; throws the failure
   * otherwise.
   */
  def negotiatedProtocol(
    engine: WSClientEngine,
    keyStore: TestKeyStore,
    serverProtocols: Seq[String]
  )(
    implicit ec: ExecutionContext
  ): String =
    tlsAttempt(engine, keyStore, serverProtocols).result.fold(throw _, identity)

  /**
   * What the requests of the [[cookieIsolation]] scenario carried in their `Cookie` header (""
   * for none), and whether the caller still saw the `Set-Cookie` header of the cookie-setting
   * response.
   */
  final case class CookieIsolation(
    setCookieSurfaced: Boolean,
    sameSite: String,
    otherTenant: String,
    otherPort: String,
    domainPlanted: String,
    siteSupplied: String,
    streamed: Option[String]
  )

  object CookieIsolation {

    /**
     * A site-stateless engine keeps NO cookie jar: it surfaces `Set-Cookie` to the caller, but
     * only ever sends the cookies a site supplies itself. `streamed` is checked (`Some`) on
     * streaming engines only.
     */
    def expected(streaming: Boolean): CookieIsolation =
      CookieIsolation(
        setCookieSurfaced = true,
        sameSite = "",
        otherTenant = "",
        otherPort = "",
        domainPlanted = "",
        siteSupplied = "site=supplied",
        streamed = if (streaming) Some("") else None
      )
  }

  /**
   * The cookie-isolation scenario on `engine`. Tenant A (`Bearer tenant-a`) logs in at
   * `http://127.0.0.1:A`, whose server answers with a session `Set-Cookie`. Then each of these
   * sends a request that records the `Cookie` header it carried: tenant A again; tenant B (own
   * credentials, same host and port); a site on another port of the same host; a `localhost`
   * site, after that other-port server set a cookie with `Domain=localhost`; a site supplying
   * its own `Cookie` header; and, on streaming engines, tenant B's SSE request. A request that
   * does not complete within 30 s fails the caller with a `TimeoutException`.
   */
  def cookieIsolation(engine: WSClientEngine): CookieIsolation = {
    def cookieOf(exchange: HttpExchange) =
      Option(exchange.getRequestHeaders.getFirst("Cookie")).getOrElse[String]("")

    def setCookie(value: String): HttpExchange => Unit = { exchange =>
      exchange.getRequestBody.readAllBytes()
      exchange.getResponseHeaders.add("Set-Cookie", value)
      respond(exchange, 200, """{"status":"ok"}""")
    }

    val cookieEcho: HttpExchange => Unit = { exchange =>
      exchange.getRequestBody.readAllBytes()
      respond(exchange, 200, Json.obj("cookie" -> cookieOf(exchange)).toString)
    }

    val cookieEchoEvents: HttpExchange => Unit = { exchange =>
      exchange.getRequestBody.readAllBytes()
      val event = Json.obj("cookie" -> cookieOf(exchange))
      respond(exchange, 200, s"data: $event\n\ndata: [DONE]\n\n", "text/event-stream")
    }

    withServer(
      "/login" -> setCookie("session=tenant-a-session; Path=/"),
      "/data" -> cookieEcho,
      "/events" -> cookieEchoEvents
    ) { portA =>
      withServer(
        "/plant" -> setCookie("planted=other-port-cookie; Domain=localhost; Path=/"),
        "/data" -> cookieEcho
      ) { portB =>
        def binding(
          coreUrl: String,
          headers: (String, String)*
        ) =
          SiteBinding(coreUrl, WsRequestContext(authHeaders = headers))

        val tenantA = binding(s"http://127.0.0.1:$portA", "Authorization" -> "Bearer tenant-a")
        val tenantB = binding(s"http://127.0.0.1:$portA", "Authorization" -> "Bearer tenant-b")
        val otherPortSite = binding(s"http://127.0.0.1:$portB")
        val localhostSite = binding(s"http://localhost:$portA")
        val supplyingSite = binding(s"http://127.0.0.1:$portA", "Cookie" -> "site=supplied")

        def get(
          site: SiteBinding,
          endPoint: String
        ) =
          Await.result(engine.execGETRich(site, endPoint), 30.seconds)

        def cookieSentBy(site: SiteBinding) =
          (engine.getResponseOrError(get(site, "data")).json \ "cookie").as[String]

        val login = get(tenantA, "login")
        val setCookieSurfaced = login.headers.exists { case (name, values) =>
          name.equalsIgnoreCase("Set-Cookie") && values.exists(_.contains("tenant-a-session"))
        }
        val sameSite = cookieSentBy(tenantA)
        val otherTenant = cookieSentBy(tenantB)
        val otherPort = cookieSentBy(otherPortSite)
        get(otherPortSite, "plant")
        val domainPlanted = cookieSentBy(localhostSite)
        val siteSupplied = cookieSentBy(supplyingSite)

        val streamed = engine match {
          case streaming: WSClientOutputStreamCore =>
            val subscriber = new LatchedSubscriber[JsValue]
            streaming.execJsonStreamPublisher(tenantB, "events", "GET").subscribe(subscriber)
            if (!subscriber.awaitDone(30))
              throw new TimeoutException("The SSE request did not complete within 30 s.")
            subscriber.error.foreach(error => throw error)
            subscriber.received match {
              case Seq(event) => Some((event \ "cookie").as[String])
              case events =>
                throw new IllegalStateException(s"Expected one SSE event, got: $events")
            }

          case _ => None
        }

        CookieIsolation(
          setCookieSurfaced = setCookieSurfaced,
          sameSite = sameSite,
          otherTenant = otherTenant,
          otherPort = otherPort,
          domainPlanted = domainPlanted,
          siteSupplied = siteSupplied,
          streamed = streamed
        )
      }
    }
  }
}
