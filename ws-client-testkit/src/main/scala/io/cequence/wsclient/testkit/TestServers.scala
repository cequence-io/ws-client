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
import io.cequence.wsclient.service.ws.Timeouts
import play.api.libs.json.{JsValue, Json}

import java.io.File
import java.net.{InetSocketAddress, ServerSocket, URLDecoder}
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.{Executors, ThreadFactory, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

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

  /**
   * One part of a multipart body as a receiver parses it: the number of `Content-Disposition`
   * headers, the names of any other part headers than `Content-Disposition` / `Content-Type`
   * (lower-cased), the `Content-Disposition` parameters (quoted-strings unquoted, sorted by
   * name - their order carries no meaning; a duplicate stays visible) and the content.
   */
  final case class ReceivedPart(
    dispositions: Int,
    otherHeaders: Seq[String],
    dispositionParams: Seq[(String, String)],
    content: String
  )

  object MultipartInjection {

    // caller-supplied names that try to leave their quoted-string: an extra parameter, extra
    // part headers and (via the blank line) a content prefix
    val DataName = "k\"\r\nY-Injected: 2"
    val FileKey = "f\"; filename=\"evil.sh\r\nZ-Injected: 3"
    val DisplayName = "a\"; name=\"x\r\nX-Injected: 1\r\n\r\nP"
    val DataValue = "data-value"
    val FileContent = "file-content"
    val FileName = "upload.txt"

    // the WHATWG / RFC 7578 4.2 encoding of a Content-Disposition parameter value
    def html5Escaped(value: String): String =
      value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

    /**
     * The parts the scenario posts (one data part, a file under a display name, a file under
     * its base name), each staying ONE part with one `Content-Disposition` header and its own
     * content - the names HTML5-encoded (the Play, jdk, sttp and zio-http engines).
     */
    val expected: Seq[ReceivedPart] = Seq(
      ReceivedPart(1, Nil, Seq("name" -> html5Escaped(DataName)), DataValue),
      ReceivedPart(
        1,
        Nil,
        Seq("filename" -> html5Escaped(DisplayName), "name" -> "file"),
        FileContent
      ),
      ReceivedPart(
        1,
        Nil,
        Seq("filename" -> FileName, "name" -> html5Escaped(FileKey)),
        FileContent
      )
    )
  }

  /**
   * Posts a multipart body whose data-part name, file key and display file name (see
   * [[MultipartInjection]]) carry quotes and CR/LF, and returns its parts as the server
   * received them.
   */
  def multipartInjection(
    engine: WSClientEngine,
    useInMemoryBody: Boolean
  ): Seq[ReceivedPart] = {
    import MultipartInjection._

    val dir = Files.createTempDirectory("ws-client-multipart")
    val file = dir.resolve(FileName).toFile
    Files.write(file.toPath, FileContent.getBytes("UTF-8"))

    try
      withEchoServer { port =>
        val response = engine.getResponseOrError(
          Await.result(
            engine.execPOSTMultipartRich(
              SiteBinding(s"http://localhost:$port"),
              "upload",
              fileParams = Seq(("file", file, Some(DisplayName)), (FileKey, file, None)),
              bodyParams = Seq(DataName -> Some(DataValue)),
              useInMemoryBody = useInMemoryBody
            ),
            30.seconds
          )
        )
        val contentType = (response.json \ "contentType").as[String]
        val boundary = contentType
          .split(";")
          .map(_.trim)
          .collectFirst {
            case param if param.startsWith("boundary=") =>
              param.stripPrefix("boundary=").stripPrefix("\"").stripSuffix("\"")
          }
          .getOrElse(throw new IllegalStateException(s"No boundary in: $contentType"))

        parseMultipart((response.json \ "body").as[String], boundary)
      }
    finally {
      Files.deleteIfExists(file.toPath)
      Files.deleteIfExists(dir)
    }
  }

  private def parseMultipart(
    body: String,
    boundary: String
  ): Seq[ReceivedPart] =
    body
      .split(java.util.regex.Pattern.quote("--" + boundary), -1)
      .toSeq
      .drop(1) // the (empty) preamble
      .takeWhile(!_.startsWith("--")) // the close delimiter and epilogue
      .map { part =>
        val withoutCrlf = part.stripPrefix("\r\n").stripSuffix("\r\n")
        val headerEnd = withoutCrlf.indexOf("\r\n\r\n")
        val (headerBlock, content) =
          if (headerEnd < 0) (withoutCrlf, "")
          else (withoutCrlf.substring(0, headerEnd), withoutCrlf.substring(headerEnd + 4))
        val headers = headerBlock.split("\r\n").toSeq.filter(_.nonEmpty).map { line =>
          val colon = line.indexOf(':')
          if (colon < 0) (line.toLowerCase, "")
          else (line.substring(0, colon).trim.toLowerCase, line.substring(colon + 1).trim)
        }
        val dispositions = headers.filter(_._1 == "content-disposition")
        ReceivedPart(
          dispositions = dispositions.size,
          otherHeaders = headers
            .map(_._1)
            .filterNot(
              Set("content-disposition", "content-type")
            ),
          dispositionParams = dispositions.headOption
            .map(header => dispositionParams(header._2).sortBy(_._1))
            .getOrElse(Nil),
          content = content
        )
      }

  /**
   * The outcome of calls to a server that sends complete response headers and then stalls:
   * `Left(error message)` or `Right(what was received)`, plus the milliseconds each took.
   */
  final case class StalledBody(
    nonStreamed: Either[String, String],
    nonStreamedMs: Long,
    streamed: Option[Either[String, String]],
    streamedMs: Option[Long]
  )

  /**
   * The stalled-body scenario on an engine built by `newEngine` with `requestTimeout = 1500`
   * and `readTimeout = 1000`: a non-streamed GET whose response promises 100 body bytes and
   * sends 6, and (on engines implementing `WSClientOutputStreamCore`, if `checkStream`) an SSE
   * call that gets one event and then nothing. The server keeps every connection open. Each
   * call is given 15 s - an engine whose deadline stops at the response headers never
   * finishes.
   */
  def stalledBody(
    checkStream: Boolean = true
  )(
    newEngine: TransportSettings => WSClientEngine
  ): StalledBody = {
    val server = new ServerSocket(0)
    val sockets = new java.util.concurrent.ConcurrentLinkedQueue[java.net.Socket]()
    val acceptor = new Thread(new Runnable {
      override def run(): Unit =
        try
          while (true) {
            val socket = server.accept()
            sockets.add(socket)
            val in = new java.io.BufferedReader(
              new java.io.InputStreamReader(socket.getInputStream, "UTF-8")
            )
            val requestLine = Option(in.readLine()).getOrElse("")
            var line = in.readLine()
            while (line != null && line.nonEmpty) line = in.readLine()
            val response =
              if (requestLine.contains("/sse"))
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
                  "f\r\ndata: {\"n\":1}\n\n\r\n"
              else
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"a\":1"
            socket.getOutputStream.write(response.getBytes("UTF-8"))
            socket.getOutputStream.flush()
            // ... and nothing more: the connection stays open until the scenario ends
          }
        catch {
          case _: Throwable => // server closed by the scenario
        }
    })
    acceptor.setDaemon(true)
    acceptor.start()

    val engine = newEngine(
      TransportSettings(Timeouts(requestTimeout = Some(1500), readTimeout = Some(1000)))
    )
    try {
      val site = SiteBinding(s"http://127.0.0.1:${server.getLocalPort}")

      val start = System.nanoTime()
      val nonStreamed =
        Try(Await.result(engine.execGETRich(site, "after-headers"), 15.seconds)) match {
          case Success(rich) => Right(s"HTTP ${rich.status.code}: ${rich.status.message}")
          case Failure(e)    => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
        }
      val nonStreamedMs = (System.nanoTime() - start) / 1000000L

      val streamed = engine match {
        case streaming: WSClientOutputStreamCore if checkStream =>
          val streamStart = System.nanoTime()
          val subscriber = new LatchedSubscriber[JsValue]
          streaming.execJsonStreamPublisher(site, "sse", "GET").subscribe(subscriber)
          val outcome =
            if (!subscriber.awaitDone(15))
              Right(s"no terminal signal, events: ${subscriber.received}")
            else
              subscriber.error match {
                case Some(e) => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
                case None    => Right(s"completed, events: ${subscriber.received}")
              }
          Some((outcome, (System.nanoTime() - streamStart) / 1000000L))
        case _ => None
      }

      StalledBody(nonStreamed, nonStreamedMs, streamed.map(_._1), streamed.map(_._2))
    } finally {
      engine.close()
      server.close()
      sockets.forEach(socket => socket.close())
    }
  }

  /**
   * A real (if minimal) tunnelling proxy: answers `CONNECT host:port` with `200 Connection
   * established` and relays bytes both ways to that target. Passes the count of tunnels it
   * opened to the test.
   */
  def withConnectProxy[T](test: (Int, () => Int) => T): T = {
    val server = new ServerSocket(0)
    val tunnels = new AtomicInteger(0)

    def pump(
      from: java.io.InputStream,
      to: java.io.OutputStream
    ): Thread = {
      val t = new Thread(new Runnable {
        override def run(): Unit =
          try {
            val buffer = new Array[Byte](8192)
            var n = from.read(buffer)
            while (n >= 0) {
              to.write(buffer, 0, n)
              to.flush()
              n = from.read(buffer)
            }
          } catch {
            case _: Throwable => // either side closed
          } finally
            try to.close()
            catch { case _: Throwable => }
      })
      t.setDaemon(true)
      t.start()
      t
    }

    val acceptor = new Thread(new Runnable {
      override def run(): Unit =
        try
          while (true) {
            val client = server.accept()
            val in = client.getInputStream
            // the request head, byte by byte - nothing of the tunnelled stream may be consumed
            val head = new StringBuilder
            while (!head.endsWith("\r\n\r\n")) {
              val b = in.read()
              if (b < 0) throw new java.io.EOFException("client closed before CONNECT")
              head.append(b.toChar)
            }
            val target = head.toString.split(" ")(1)
            val Array(host, port) = target.split(":")
            val upstream = new java.net.Socket(host, port.toInt)
            tunnels.incrementAndGet()
            client.getOutputStream.write(
              "HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8")
            )
            client.getOutputStream.flush()
            pump(in, upstream.getOutputStream)
            pump(upstream.getInputStream, client.getOutputStream)
          }
        catch {
          case _: Throwable => // server closed by the test
        }
    })
    acceptor.setDaemon(true)
    acceptor.start()

    try test(server.getLocalPort, () => tunnels.get())
    finally server.close()
  }

  /**
   * A complete https call through [[withConnectProxy]] to a TLS test server whose certificate
   * the engine (built by `newEngine` with `proxyURL` set, created and first used under that
   * trust store) trusts: the status received and the number of tunnels the proxy opened.
   */
  def connectTunnel(
    keyStore: TestKeyStore
  )(
    newEngine: TransportSettings => WSClientEngine
  ): (Int, Int) =
    withTlsServer(keyStore, Seq("TLSv1.3", "TLSv1.2"))("/" -> echo) { tlsPort =>
      withConnectProxy {
        (
          proxyPort,
          tunnels
        ) =>
          withDefaultTrustStore(keyStore) {
            val engine = newEngine(TransportSettings(proxyURL = Some(s"127.0.0.1:$proxyPort")))
            try {
              val rich = Await.result(
                engine.execGETRich(SiteBinding(s"https://localhost:$tlsPort"), "ping"),
                30.seconds
              )
              (rich.status.code, tunnels())
            } finally engine.close()
          }
      }
    }

  /**
   * What a caller observed through a forward proxy that answers every CONNECT with a non-200
   * reply carrying a body ([[ProxyConnectReply.ForgedBody]]): whether the proxy saw a CONNECT,
   * and the outcome of a non-streamed GET and (on engines implementing
   * `WSClientOutputStreamCore`) of an SSE call - `Left(error message)` or `Right("HTTP
   * <status>: <body>" / "events: ...")`.
   */
  final case class ProxyConnectReply(
    proxySawConnect: Boolean,
    nonStreamed: Either[String, String],
    streamed: Option[Either[String, String]]
  )

  object ProxyConnectReply {
    // a 202 is in the default acceptable statuses: accepted as the https site's own response,
    // the proxy (or anyone on the plain-HTTP hop to it) authored an "HTTPS" response
    val ForgedBody = "data: {\"id\":\"forged-by-proxy\"}\n\n"
  }

  /**
   * The proxy-CONNECT-reply scenario on an engine built by `newEngine` with `proxyURL` set: an
   * https site (never connected to directly - the proxy is the only server) is called through
   * a loopback proxy that answers CONNECT with `HTTP/1.1 202` and
   * [[ProxyConnectReply.ForgedBody]]. A tunnelling engine must fail both calls: only a 200
   * reply to CONNECT establishes a tunnel.
   */
  def proxyConnectReply(newEngine: TransportSettings => WSClientEngine): ProxyConnectReply = {
    import ProxyConnectReply._

    val server = new ServerSocket(0)
    @volatile var sawConnect = false
    val thread = new Thread(new Runnable {
      override def run(): Unit =
        try
          while (true) {
            val socket = server.accept()
            try {
              val in = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream, "UTF-8")
              )
              val firstLine = Option(in.readLine()).getOrElse("")
              if (firstLine.startsWith("CONNECT ")) sawConnect = true
              var line = in.readLine()
              while (line != null && line.nonEmpty) line = in.readLine()

              val body = ForgedBody.getBytes("UTF-8")
              val head =
                s"HTTP/1.1 202 Accepted\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
              socket.getOutputStream.write(head.getBytes("UTF-8") ++ body)
              socket.getOutputStream.flush()
            } finally socket.close()
          }
        catch {
          case _: Throwable => // server closed by the scenario
        }
    })
    thread.setDaemon(true)
    thread.start()

    val engine = newEngine(
      TransportSettings(proxyURL = Some(s"127.0.0.1:${server.getLocalPort}"))
    )
    try {
      // a closed port: a tunnelling engine never connects to the site itself
      val site = SiteBinding("https://localhost:1/v1")

      val nonStreamed =
        Try(Await.result(engine.execGETRich(site, "models"), 30.seconds)) match {
          case Success(rich) =>
            Right(s"HTTP ${rich.status.code}: ${rich.response.map(_.string).getOrElse("")}")
          case Failure(e) => Left(String.valueOf(e.getMessage))
        }

      val streamed = engine match {
        case streaming: WSClientOutputStreamCore =>
          val subscriber = new LatchedSubscriber[JsValue]
          streaming
            .execJsonStreamPublisher(site, "chat/completions", "POST")
            .subscribe(subscriber)
          if (!subscriber.awaitDone(30)) throw new TimeoutException("SSE call did not finish")
          Some(
            subscriber.error match {
              case Some(e) => Left(String.valueOf(e.getMessage))
              case None    => Right(s"events: ${subscriber.received.mkString(", ")}")
            }
          )
        case _ => None
      }

      ProxyConnectReply(sawConnect, nonStreamed, streamed)
    } finally {
      engine.close()
      server.close()
    }
  }

  /**
   * The query as the server received it: the endpoint string's own query (raw, as many pairs
   * as the endpoint wrote), then the parameters - each `&`-separated pair split at its first
   * `=`, both sides form-decoded (`+` is a space, as most servers read it; a malformed escape
   * comes back as `<undecodable:...>`).
   */
  final case class ReceivedQuery(
    endpointQuery: String,
    params: Seq[(String, String)]
  )

  object QueryEncoding {

    // a query the endpoint string carries itself (Azure's api-version) - not a parameter
    val EndpointQuery = "api-version=2024-02-01"

    /**
     * Raw values (and keys) holding characters that are reserved in a query or need encoding:
     * a `#` used to start a fragment, so the rest of the query was lost (Pinecone's
     * `fetch(ids)` with `doc#0`); `&` / `=` split a value; `+` reads as a space; a bare `%`;
     * an encoded-looking value that is still a raw value; non-ASCII; a key that needs
     * encoding.
     */
    val Params: Seq[(String, String)] = Seq(
      "ids" -> "doc#0",
      "ids" -> "doc#1",
      "q" -> "a b&c=d",
      "expr" -> "1+1",
      "pct" -> "100%",
      "pre" -> "a%20b",
      "text" -> ("na" + 0xef.toChar + "ve " + 0x65e5.toChar + 0x672c.toChar),
      "odd key&x=" -> "v"
    )

    // a base64-style API key in WsRequestContext.extraParams
    val ExtraParam: (String, String) = "key" -> "k+/="

    val expected: ReceivedQuery = ReceivedQuery(EndpointQuery, Params :+ ExtraParam)
  }

  /**
   * GETs `items?`[[QueryEncoding.EndpointQuery]] with [[QueryEncoding.Params]] and an extra
   * param from the site's request context, and returns the query as the server received it.
   */
  def queryEncoding(engine: WSClientEngine): ReceivedQuery = {
    import QueryEncoding._

    def decode(value: String) =
      try URLDecoder.decode(value, "UTF-8")
      catch { case _: IllegalArgumentException => s"<undecodable:$value>" }

    withEchoServer { port =>
      val site = SiteBinding(
        s"http://localhost:$port",
        WsRequestContext(extraParams = Seq(ExtraParam))
      )
      val response = engine.getResponseOrError(
        Await.result(
          engine.execGETRich(
            site,
            s"items?$EndpointQuery",
            params = Params.map { case (key, value) => key -> Some(value) }
          ),
          30.seconds
        )
      )

      val pairs = (response.json \ "query").as[String].split("&").toSeq
      val (endpointPairs, paramPairs) = pairs.splitAt(EndpointQuery.split("&").length)

      ReceivedQuery(
        endpointPairs.mkString("&"),
        paramPairs.filter(_.nonEmpty).map { pair =>
          pair.split("=", 2) match {
            case Array(key, value) => decode(key) -> decode(value)
            case Array(key)        => decode(key) -> ""
          }
        }
      )
    }
  }

  // `form-data; key=token; key="quoted \" string"` -> the (key, value) pairs
  private def dispositionParams(value: String): Seq[(String, String)] = {
    val params = Seq.newBuilder[(String, String)]
    var i = value.indexOf(';')
    while (i >= 0 && i < value.length) {
      i += 1
      while (i < value.length && value.charAt(i) == ' ') i += 1
      val eq = value.indexOf('=', i)
      if (eq < 0) i = -1
      else {
        val key = value.substring(i, eq).trim.toLowerCase
        val result = new StringBuilder
        i = eq + 1
        if (i < value.length && value.charAt(i) == '"') {
          i += 1
          while (i < value.length && value.charAt(i) != '"') {
            if (value.charAt(i) == '\\' && i + 1 < value.length) i += 1
            result += value.charAt(i)
            i += 1
          }
          i += 1 // the closing quote
        } else
          while (i < value.length && value.charAt(i) != ';') {
            result += value.charAt(i)
            i += 1
          }
        params += key -> result.toString.trim
        i = value.indexOf(';', i)
      }
    }
    params.result()
  }
}
