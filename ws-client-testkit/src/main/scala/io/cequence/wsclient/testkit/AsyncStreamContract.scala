package io.cequence.wsclient.testkit

import com.sun.net.httpserver.HttpExchange
import io.cequence.wsclient.domain.{CequenceWSHttpStatusException, SiteBinding}
import io.cequence.wsclient.service.{
  WSClientEngine,
  WSClientOutputStreamCore,
  WSClientWithEngineAsyncStreamingBase
}
import io.cequence.wsclient.stream.{AsyncStream, Bytes, StreamDiagnostics}
import play.api.libs.json.{JsValue, Json}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * What an engine's `AsyncStream` methods did against the contract server (see
 * [[AsyncStreamContract.run]]); each engine spec compares it with
 * [[AsyncStreamContract.expected]] (adjusting what its HTTP library legitimately differs in).
 */
final case class AsyncStreamContract(
  requestsBeforeRun: Int,
  requestsAfterTwoRuns: Int,
  events: Seq[JsValue],
  rawRequestsBeforeRun: Int,
  rawRequestsAfterTwoRuns: Int,
  statusFailure: Option[String],
  takeOneAbortedRequest: Boolean,
  rawBytesIntact: Boolean,
  downloadBytesIntact: Boolean,
  postDownloadBytesIntact: Boolean,
  streamedDownloadIntact: Boolean,
  notFoundDownload: Option[String],
  // the plan (`describe`) of a streamed download: its 404 preflight replays a neutral stream,
  // or - on an engine whose streams are native Akka / Pekko / ZIO leaves - stays native
  downloadPlan: String,
  // the bridges of the neutral runs of the engine's JSON and raw streams
  neutralBridges: Long,
  wireUrlMatchesRequest: Boolean,
  endpointQueryVerbatim: Boolean,
  jsonBodyMatchesWire: Boolean
)

object AsyncStreamContract {

  val expected: AsyncStreamContract = AsyncStreamContract(
    requestsBeforeRun = 0,
    requestsAfterTwoRuns = 2,
    events = Seq(Json.obj("n" -> 1), Json.obj("n" -> 2), Json.obj("n" -> 3)),
    rawRequestsBeforeRun = 0,
    rawRequestsAfterTwoRuns = 2,
    statusFailure = Some("CequenceWSHttpStatusException: 503"),
    takeOneAbortedRequest = true,
    rawBytesIntact = true,
    downloadBytesIntact = true,
    postDownloadBytesIntact = true,
    streamedDownloadIntact = true,
    notFoundDownload = Some("None"),
    downloadPlan = "preflight -> mapError",
    neutralBridges = 0,
    wireUrlMatchesRequest = true,
    endpointQueryVerbatim = true,
    jsonBodyMatchesWire = true
  )

  // comment frames, CRLF line breaks inside a frame, a quoted "[DONE]" is NOT the end; the
  // structural [DONE] is - the event after it must never arrive
  private val Sse =
    ": keep-alive\n\n" +
      "data: {\"n\":1}\n\n" +
      "event: delta\r\ndata: {\"n\":2}\r\n\n" +
      ": another comment\n\n" +
      "data: {\"n\":3}\n\n" +
      "data: [DONE]\n\n" +
      "data: {\"n\":4}\n\n"

  // every byte value, invalid UTF-8 sequences included
  private val AllBytes: Array[Byte] = (0 until 256).map(_.toByte).toArray ++
    Array[Byte](0xc3.toByte, 0x28, 0xa0.toByte, 0xa1.toByte, 0xe2.toByte, 0x28, 0xa1.toByte)

  // an endpoint string carrying its own (pre-encoded) query - sent byte for byte by an engine
  // that does not re-render URLs
  private val EndpointQuery = "q=a%2Fb%20c&sig=x+y%3D"

  private val Pad = "x" * 1024

  // a body with emoji (outside the BMP), CJK text and an escaped quote
  private val JsonBody = Json.obj(
    "text" -> ("call " + new String(
      Character.toChars(0x1f4de)
    ) + " " + 0x65e5.toChar + 0x672c.toChar),
    "quote" -> "a\"b"
  )

  private final class ContractService(
    override protected val engine: WSClientEngine with WSClientOutputStreamCore,
    override protected val site: SiteBinding
  )(
    implicit override protected val ec: ExecutionContext
  ) extends WSClientWithEngineAsyncStreamingBase[
        WSClientEngine with WSClientOutputStreamCore
      ] {
    override protected type PEP = String
    override protected type PT = String
    override protected def ownsEngine: Boolean = false
  }

  /**
   * A streamed GET download of `endPoint`, as a service on `engine` returns it
   * (`execGETStream` of `WSClientWithEngineAsyncStreamingBase`): `None` for a 404, else the
   * body as a single-run stream.
   */
  def streamedDownload(
    engine: WSClientEngine with WSClientOutputStreamCore,
    site: SiteBinding,
    endPoint: String
  )(
    implicit ec: ExecutionContext
  ): Future[Option[AsyncStream[Bytes]]] =
    new ContractService(engine, site).execGETStream(endPoint)

  /** Runs the contract on `engine` (which must stream), against an embedded server. */
  def run(engine: WSClientEngine with WSClientOutputStreamCore): AsyncStreamContract = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

    val sseRequests, rawRequests = new AtomicInteger()
    val streamAborted = new AtomicBoolean(false)
    val streamFinished = new CountDownLatch(1)
    val received = new AtomicReference[(String, Array[Byte])](("", Array.emptyByteArray))

    def sendBytes(
      exchange: HttpExchange,
      status: Int,
      bytes: Array[Byte],
      contentType: String
    ): Unit = {
      exchange.getRequestBody.readAllBytes()
      exchange.getResponseHeaders.add("Content-Type", contentType)
      exchange.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
      val out = exchange.getResponseBody
      out.write(bytes)
      out.close()
    }

    val contexts: Seq[(String, HttpExchange => Unit)] = Seq(
      "/sse" -> { exchange =>
        sseRequests.incrementAndGet()
        sendBytes(exchange, 200, Sse.getBytes("UTF-8"), "text/event-stream")
      },
      "/raw" -> { exchange =>
        rawRequests.incrementAndGet()
        sendBytes(exchange, 200, AllBytes, "application/octet-stream")
      },
      "/unavailable" -> { exchange =>
        sendBytes(
          exchange,
          503,
          "{\"error\":\"overloaded\"}".getBytes("UTF-8"),
          "application/json"
        )
      },
      "/missing" -> { exchange =>
        sendBytes(
          exchange,
          404,
          "{\"error\":\"no such file\"}".getBytes("UTF-8"),
          "application/json"
        )
      },
      // one event, then more every 20 ms until the client goes away (or 10 s pass)
      "/endless" -> { exchange =>
        exchange.getRequestBody.readAllBytes()
        exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.getResponseBody
        try {
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
          var n = 0
          while (System.nanoTime() < deadline) {
            val event = "data: " + Json.obj("n" -> n, "pad" -> Pad).toString + "\n\n"
            out.write(event.getBytes("UTF-8"))
            out.flush()
            n += 1
            Thread.sleep(20)
          }
        } catch {
          case _: java.io.IOException => streamAborted.set(true)
        } finally {
          Try(out.close())
          streamFinished.countDown()
        }
      },
      // records the request target and body as received
      "/echo" -> { exchange =>
        val body = exchange.getRequestBody.readAllBytes()
        received.set(
          (
            exchange.getRequestURI.getRawPath + Option(exchange.getRequestURI.getRawQuery)
              .map("?" + _)
              .getOrElse(""),
            body
          )
        )
        sendBytes(exchange, 200, "{}".getBytes("UTF-8"), "application/json")
      }
    )

    TestServers.withServer(contexts: _*) { port =>
      val site = SiteBinding(s"http://127.0.0.1:$port")
      val service = new ContractService(engine, site)
      // cold and re-runnable (the bridges of these neutral runs are counted)
      val events = engine.execJsonAsyncStream(site, "sse", "GET")
      Thread.sleep(100)
      val requestsBeforeRun = sseRequests.get()
      val (firstRunFuture, bridges) = StreamDiagnostics.counted(events.runToSeq)
      val firstRun = await(firstRunFuture)
      val (rawRunFuture, rawBridges) =
        StreamDiagnostics.counted(engine.execRawAsyncStream(site, "raw", "GET").runDrain)
      await(rawRunFuture)
      await(events.runToSeq)
      val requestsAfterTwoRuns = sseRequests.get()

      val raw = engine.execRawAsyncStream(site, "raw", "GET")
      Thread.sleep(100)
      val rawRequestsBeforeRun = rawRequests.get() - 1 // the counted run above
      val rawBytes = await(raw.runToBytes())
      await(raw.runDrain)
      val rawRequestsAfterTwoRuns = rawRequests.get() - 1

      // a non-2xx status fails the run
      val statusFailure =
        Try(await(engine.execJsonAsyncStream(site, "unavailable", "GET").runToSeq)) match {
          case Failure(e: CequenceWSHttpStatusException) =>
            Some(s"CequenceWSHttpStatusException: ${e.statusCode}")
          case Failure(e) => Some(s"${e.getClass.getSimpleName}: ${e.getMessage}")
          case Success(_) => None
        }

      // take(1) aborts the request
      await(engine.execJsonAsyncStream(site, "endless", "GET").take(1).runToSeq)
      streamFinished.await(15, TimeUnit.SECONDS)
      val takeOneAborted = streamAborted.get()

      // binary-safe downloads
      val download = await(service.execGETBytes("raw"))
      val postDownload =
        await(service.execPOSTBytes("raw", bodyParams = Seq("a" -> Some(Json.toJson(1)))))
      val downloadStream = await(service.execGETStream("raw"))
      val downloadPlan = downloadStream.map(_.describe).getOrElse("None")
      val streamedDownload =
        downloadStream.map(s => await(s.runToBytes())).map(_.toArray.toSeq)
      val notFound = await(service.execGETStream("missing")).map(_ => "Some").getOrElse("None")

      val neutralBridges = bridges.get + rawBridges.get

      // the wire format: the URL and body the engine reports are the ones the server receives
      val wireUrl =
        engine.wireUrl(site, s"echo?$EndpointQuery", params = Seq("p" -> Some("x y/z")))
      engine.getResponseOrError(
        await(
          engine.execPOSTBodyRich(
            site,
            s"echo?$EndpointQuery",
            params = Seq("p" -> Some("x y/z")),
            body = JsonBody
          )
        )
      )
      val (target, body) = received.get()
      val wireTarget = wireUrl.substring(wireUrl.indexOf("/", wireUrl.indexOf("//") + 2))

      AsyncStreamContract(
        requestsBeforeRun = requestsBeforeRun,
        requestsAfterTwoRuns = requestsAfterTwoRuns,
        events = firstRun,
        rawRequestsBeforeRun = rawRequestsBeforeRun,
        rawRequestsAfterTwoRuns = rawRequestsAfterTwoRuns,
        statusFailure = statusFailure,
        takeOneAbortedRequest = takeOneAborted,
        rawBytesIntact = rawBytes.toArray.toSeq == AllBytes.toSeq,
        downloadBytesIntact = download == Bytes(AllBytes),
        postDownloadBytesIntact = postDownload == Bytes(AllBytes),
        streamedDownloadIntact = streamedDownload.contains(AllBytes.toSeq),
        notFoundDownload = Some(notFound),
        downloadPlan = downloadPlan,
        neutralBridges = neutralBridges,
        wireUrlMatchesRequest = wireTarget == target,
        endpointQueryVerbatim = target.startsWith(s"/echo?$EndpointQuery&"),
        jsonBodyMatchesWire =
          body.toSeq == engine.renderJsonBody(JsonBody).getBytes("UTF-8").toSeq
      )
    }
  }
}
