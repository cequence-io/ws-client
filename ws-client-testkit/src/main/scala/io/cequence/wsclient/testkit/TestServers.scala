package io.cequence.wsclient.testkit

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import play.api.libs.json.Json

import java.net.{InetSocketAddress, ServerSocket}

/**
 * Embedded servers for the engine specs (test-only module, never published). Each helper binds
 * an ephemeral port, runs the test with it, and stops the server afterwards.
 */
object TestServers {

  /**
   * A server with the given `path -> handler` contexts.
   */
  def withServer(
    contexts: (String, HttpExchange => Unit)*
  )(
    test: Int => Unit
  ): Unit = {
    val server = HttpServer.create(new InetSocketAddress(0), 0)
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
    finally
      server.stop(0)
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
  def withEchoServer(test: Int => Unit): Unit =
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
}
