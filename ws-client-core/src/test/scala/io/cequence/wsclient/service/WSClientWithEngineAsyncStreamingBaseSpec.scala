package io.cequence.wsclient.service

import io.cequence.wsclient.domain.{
  BytesBackedResponse,
  CequenceWSException,
  CequenceWSHttpStatusException,
  RichResponse,
  SimpleRichResponse,
  SiteBinding,
  StatusData
}
import io.cequence.wsclient.service.spi.{DummyEngine, TransportSettings}
import io.cequence.wsclient.stream.{AsyncStream, Bytes}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsValue, Json}

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class WSClientWithEngineAsyncStreamingBaseSpec extends AnyWordSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  private final class RateLimited(message: String) extends CequenceWSException(message)

  // raw streams by endpoint: "ok" -> 3 binary chunks, "missing" -> 404, "busy" -> 429
  private final class StreamingDummyEngine extends DummyEngine with WSClientOutputStreamCore {
    val calls = new ConcurrentLinkedQueue[(String, String, Seq[(String, Option[JsValue])])]()
    val chunks: Seq[Array[Byte]] =
      Seq(Array[Byte](0, 1, 2), Array[Byte](-1, -2), Array.fill[Byte](10)(7))

    override def copy(
      transportSettings: TransportSettings = this.transportSettings,
      reuseExecContext: Boolean = true
    ): StreamingDummyEngine = new StreamingDummyEngine

    override def execJsonStreamPublisher(
      site: SiteBinding,
      endPoint: String,
      method: String,
      endPointParam: Option[String],
      params: Seq[(String, Option[Any])],
      bodyParams: Seq[(String, Option[JsValue])],
      extraHeaders: Seq[(String, String)],
      framingDelimiter: String,
      maxFrameLength: Option[Int],
      stripPrefix: Option[String],
      stripSuffix: Option[String]
    ): java.util.concurrent.Flow.Publisher[JsValue] =
      status(endPoint)
        .map(AsyncStream.failed[JsValue](_))
        .getOrElse(AsyncStream(Json.obj("a" -> 1)))
        .toPublisher

    override def execRawStreamPublisher(
      site: SiteBinding,
      endPoint: String,
      method: String,
      endPointParam: Option[String],
      params: Seq[(String, Option[Any])],
      bodyParams: Seq[(String, Option[JsValue])],
      extraHeaders: Seq[(String, String)]
    ): java.util.concurrent.Flow.Publisher[ByteBuffer] = {
      calls.add((endPoint, method, bodyParams))
      status(endPoint)
        .map(AsyncStream.failed[ByteBuffer](_))
        .getOrElse(AsyncStream.fromIterable(chunks.toList).map(ByteBuffer.wrap))
        .toPublisher
    }

    private def status(endPoint: String): Option[Throwable] = endPoint match {
      case "missing" =>
        Some(new CequenceWSHttpStatusException("svc: HTTP 404 - gone", 404, "gone"))
      case "busy" =>
        Some(new CequenceWSHttpStatusException("svc: HTTP 429 - slow down", 429, "slow down"))
      case _ => None
    }
  }

  private final class Service(
    override protected val engine: StreamingDummyEngine
  )(
    implicit override protected val ec: ExecutionContext
  ) extends WSClientWithEngineAsyncStreamingBase[StreamingDummyEngine] {
    override protected type PEP = String
    override protected type PT = String
    override protected val site: SiteBinding = SiteBinding("http://svc")

    override protected def handleErrorCodes(
      httpCode: Int,
      message: String
    ): Nothing =
      if (httpCode == 429) throw new RateLimited(s"rate limited: $message")
      else super.handleErrorCodes(httpCode, message)
  }

  private val allBytes = Bytes(Array[Byte](0, 1, 2, -1, -2) ++ Array.fill[Byte](10)(7))

  "WSClientWithEngineAsyncStreamingBase" should {

    "classify a stream's status failure through handleErrorCodes, as a stream operator" in {
      val service = new Service(new StreamingDummyEngine)
      val failure = Try(await(service.execJsonStream("busy", "POST").runToSeq)).failed.get
      failure shouldBe a[RateLimited]
      failure.getSuppressed.head shouldBe a[CequenceWSHttpStatusException]
      service.execJsonStream("busy", "POST").describe should endWith("mapError")

      // an unclassified status keeps the original failure
      Try(await(service.execRawStream("missing", "GET").runToSeq)).failed.get shouldBe a[
        CequenceWSHttpStatusException
      ]
      await(service.execJsonStream("ok", "POST").runToSeq) shouldBe Seq(Json.obj("a" -> 1))
    }

    "download bytes binary-safe, bounded by maxBytes, with GET and POST" in {
      val engine = new StreamingDummyEngine
      val service = new Service(engine)
      await(service.execGETBytes("ok")) shouldBe allBytes
      await(
        service.execPOSTBytes("ok", bodyParams = Seq("text" -> Some(Json.toJson("speak"))))
      ) shouldBe allBytes
      engine.calls.asScala.toList
        .map(c => (c._1, c._2)) shouldBe List(("ok", "GET"), ("ok", "POST"))
      engine.calls.asScala.last._3 shouldBe Seq("text" -> Some(Json.toJson("speak")))

      val tooLarge = Try(await(service.execGETBytes("ok", maxBytes = 4))).failed.get
      tooLarge shouldBe a[CequenceWSException]
      tooLarge.getMessage should include("4 bytes")
      Try(await(service.execGETBytes("busy"))).failed.get shouldBe a[RateLimited]
    }

    "stream a download: None for a 404, a classified failure, else a single-run body" in {
      val service = new Service(new StreamingDummyEngine)
      await(service.execGETStream("missing")) shouldBe None
      Try(await(service.execGETStream("busy"))).failed.get shouldBe a[RateLimited]

      val body = await(service.execPOSTStream("ok")).get
      await(body.runToBytes()) shouldBe allBytes
      Try(await(body.runToSeq)).failed.get shouldBe an[IllegalStateException] // single run
    }
  }

  // a NON-streaming engine: buffered responses only
  private final class BufferedDummyEngine extends DummyEngine {
    private def respond(endPoint: String): Future[RichResponse] =
      Future.successful(endPoint match {
        case "missing" => SimpleRichResponse(None, StatusData(404, "gone"), Map.empty)
        case "busy"    => SimpleRichResponse(None, StatusData(429, "slow down"), Map.empty)
        case _ =>
          SimpleRichResponse(
            Some(BytesBackedResponse(allBytes, java.nio.charset.StandardCharsets.UTF_8)),
            StatusData(200, "ok"),
            Map.empty
          )
      })

    override def execGETRich(
      site: SiteBinding,
      endPoint: String,
      endPointParam: Option[String],
      params: Seq[(String, Option[Any])],
      extraHeaders: Seq[(String, String)],
      acceptableStatusCodes: Seq[Int]
    ): Future[RichResponse] = respond(endPoint)

    override def execPOSTRich(
      site: SiteBinding,
      endPoint: String,
      endPointParam: Option[String],
      params: Seq[(String, Option[Any])],
      bodyParams: Seq[(String, Option[JsValue])],
      extraHeaders: Seq[(String, String)],
      acceptableStatusCodes: Seq[Int]
    ): Future[RichResponse] = respond(endPoint)
  }

  private final class BufferedService(
    override protected val engine: WSClientEngine
  )(
    implicit override protected val ec: ExecutionContext
  ) extends WSClientWithEngineBase[WSClientEngine] {
    override protected type PEP = String
    override protected type PT = String
    override protected val site: SiteBinding = SiteBinding("http://svc")

    override protected def handleErrorCodes(
      httpCode: Int,
      message: String
    ): Nothing =
      if (httpCode == 429) throw new RateLimited(s"rate limited: $message")
      else super.handleErrorCodes(httpCode, message)

    def bytes(
      endPoint: String,
      maxBytes: Long = Bytes.DefaultMaxBytes
    ): Future[Bytes] =
      execBytesDownload(endPoint, "GET", maxBytes = maxBytes)

    def stream(endPoint: String): Future[Option[AsyncStream[Bytes]]] =
      execStreamDownload(endPoint, "POST")

    def download(
      endPoint: String,
      method: String,
      body: Seq[(String, Option[JsValue])]
    ): Future[Bytes] =
      execBytesDownload(endPoint, method, bodyParams = body)

    // a service-level override: the download helpers go straight to the engine on every engine
    override def execGETRich(
      endPoint: String,
      endPointParam: Option[String],
      params: Seq[(String, Option[Any])],
      extraHeaders: Seq[(String, String)],
      acceptableStatusCodes: Seq[Int]
    ): Future[RichResponse] = Future.failed(new AssertionError("service-level override used"))
  }

  "the download helpers of WSClientWithEngineBase" should {

    "serve binary-safe downloads on a non-streaming engine from the buffered body" in {
      val service = new BufferedService(new BufferedDummyEngine)
      await(service.bytes("ok")) shouldBe allBytes
      await(service.stream("missing")) shouldBe None
      await(await(service.stream("ok")).get.runToBytes()) shouldBe allBytes
      Try(await(service.stream("busy"))).failed.get shouldBe a[RateLimited]
      Try(await(service.bytes("busy"))).failed.get shouldBe a[RateLimited]
      Try(await(service.bytes("ok", maxBytes = 4))).failed.get.getMessage should include(
        "4 bytes"
      )
    }

    "read the raw stream on a streaming engine" in {
      val service = new BufferedService(new StreamingDummyEngine)
      await(service.bytes("ok")) shouldBe allBytes
      await(service.stream("missing")) shouldBe None
    }

    "send body parameters on POST / PUT / PATCH only, and reject a negative limit" in {
      val engine = new StreamingDummyEngine
      val service = new BufferedService(engine)
      val body = Seq("q" -> Some(Json.toJson("x")))
      await(service.download("ok", "GET", body)) shouldBe allBytes
      await(service.download("ok", "delete", body)) shouldBe allBytes
      await(service.download("ok", "POST", body)) shouldBe allBytes
      // the method normalized once: the same wire method on every engine
      engine.calls.asScala.toList.map { case (_, method, bodyParams) =>
        (method, bodyParams)
      } shouldBe
        List("GET" -> Nil, "DELETE" -> Nil, "POST" -> body)
      Try(await(service.download("ok", "HEAD", Nil))).failed.get.getMessage should include(
        "Unsupported download method: HEAD"
      )

      // a Future-returning method never throws: the invalid limit is a failed future
      val negative = service.bytes("ok", maxBytes = -1)
      Try(await(negative)).failed.get shouldBe an[IllegalArgumentException]
      Try(await(AsyncStream.single(allBytes).runToBytes(-1))).failed.get shouldBe
        an[IllegalArgumentException]
    }
  }

  "PollingHelperCore" should {

    "poll until done, without an actor system" in {
      val calls = new AtomicInteger()
      final class Poller extends PollingHelperCore {
        override protected val pollingMs = 10
        def poll: Future[Int] = pollUntilDone[Int](_ >= 3)(Future(calls.incrementAndGet()))
      }
      val helper = new Poller
      await(helper.poll) shouldBe 3
      calls.get() shouldBe 3
    }
  }
}
