package io.cequence.wsclient.service.ws.stream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.{Flow, Framing, Source}
import akka.util.ByteString
import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.domain.{
  CequenceWSException,
  CequenceWSTimeoutException,
  CequenceWSUnknownHostException,
  SiteBinding
}
import io.cequence.wsclient.service.{
  JsonStreamFrames,
  SourcePublishersAkka,
  WSClientOutputStreamExtraAkka
}
import io.cequence.wsclient.service.spi.TransportSettings
import io.cequence.wsclient.service.ws.PlayWSClientEngine
import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue
import play.api.libs.ws.JsonBodyWritables._

import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext

/**
 * The Play WS (Akka) streaming flavor of the site-stateless engine - a strict superset of
 * [[PlayWSClientEngine]] that additionally supports SSE/JSON response streaming
 * (`execJsonStream`) and raw byte streaming (`execRawStream`). Every call takes a
 * [[SiteBinding]] first, so ONE engine instance (one client, one connection pool) serves any
 * number of sites/providers.
 *
 * @since Feb
 *   2023
 */
class PlayWSStreamClientEngine(
  transportSettings: TransportSettings = TransportSettings(),
  ownedSystem: Option[ActorSystem] = None,
  newExecEnv: Option[() => ActorSystem] = None
)(
  implicit materializer: Materializer,
  ec: ExecutionContext
) extends PlayWSClientEngine(transportSettings, ownedSystem, newExecEnv)
    with WSClientOutputStreamExtraAkka {

  private val logger = LoggerFactory.getLogger("PlayWSStreamClientEngine")

  // narrows PlayWSClientEngine.copy: a Source-typed streaming engine copies as one (see
  // `WSClientEngine.copy` for the full contract - reuse vs. own-execution-environment copies)
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): PlayWSStreamClientEngine =
    if (reuseExecContext)
      new PlayWSStreamClientEngine(transportSettings, ownedSystem = None, newExecEnv)(
        materializer,
        ec
      )
    else {
      val mkSystem = newExecEnv.getOrElse(
        throw new CequenceWSException(
          "This engine's execution environment was caller-supplied - create a new engine " +
            "via the explicit factory or the discovery registry."
        )
      )
      val system = mkSystem()

      new PlayWSStreamClientEngine(transportSettings, ownedSystem = Some(system), newExecEnv)(
        Materializer(system),
        system.dispatcher
      )
    }

  protected val defaultMaxFrameLength = JsonStreamFrames.DefaultMaxFrameLength

  // frame semantics (data:-prefix, [DONE], strip, error mapping) live in the shared,
  // backend-agnostic JsonStreamFrames - this marshaller only adapts them to akka-http.
  // The [DONE] sentinel stays a STRUCTURAL FrameResult (never a JsValue element), so a
  // legitimate JSON-string payload "[DONE]" cannot be mistaken for it.
  private def frameMarshaller(
    stripPrefix: Option[String],
    stripSuffix: Option[String]
  ): Unmarshaller[ByteString, JsonStreamFrames.FrameResult] =
    Unmarshaller.strict[ByteString, JsonStreamFrames.FrameResult] { byteString =>
      val string = byteString.utf8String

      logger.debug(s"Unmarshalling JSON: $string")

      JsonStreamFrames.parseFrame(string, stripPrefix, stripSuffix)
    }

  override def execJsonStream(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    framingDelimiter: String = "\n\n",
    maxFrameLength: Option[Int] = None,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): Source[JsValue, NotUsed] = {
    implicit val unmarshaller =
      frameMarshaller(stripPrefix = stripPrefix, stripSuffix = stripSuffix)

    execFramedStream[JsonStreamFrames.FrameResult](
      site,
      endPoint,
      method,
      endPointParam,
      params,
      bodyParams,
      extraHeaders,
      Framing.delimiter(
        ByteString(framingDelimiter),
        maxFrameLength.getOrElse(defaultMaxFrameLength),
        allowTruncation = true
      )
    ).recover(handleException(site, endPoint))
      // take until the end of stream marked with the structural [DONE] sentinel
      .takeWhile(_ != JsonStreamFrames.EndOfStream)
      .collect { case JsonStreamFrames.JsonFrame(json) => json }
  }

  protected def handleException[T](
    site: SiteBinding,
    endPoint: String
  ): PartialFunction[Throwable, T] = {
    val prefix = serviceAndEndpoint(site, Some(endPoint))

    {
      case e: JsonParseException =>
        val message = s"$prefix: Response is not a JSON. ${e.getMessage}."
        logger.error(message)
        throw new CequenceWSException(message)
      case e: FramingException =>
        val message = s"$prefix: Stream framing problem occurred. ${e.getMessage}."
        logger.error(message)
        throw new CequenceWSException(message)
      case e: TimeoutException =>
        val message = s"$prefix: Time out. ${e.getMessage}."
        logger.error(message)
        throw new CequenceWSTimeoutException(message)
      case e: UnknownHostException =>
        val message = s"$prefix: Host name cannot be resolved. ${e.getMessage}."
        logger.error(message)
        throw new CequenceWSUnknownHostException(message)
      case e: Throwable =>
        val message = s"$prefix: Fatal problem! ${e.getMessage}."
        logger.error(message)
        throw new CequenceWSException(message)
    }
  }

  protected def execFramedStream[T](
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    framing: Flow[ByteString, ByteString, NotUsed]
  )(
    implicit um: Unmarshaller[ByteString, T],
    materializer: Materializer
  ): Source[T, NotUsed] =
    execRawStream(
      site,
      endPoint,
      method,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).via(framing).mapAsync(1)(bytes => Unmarshal(bytes).to[T]) // unmarshal one by one

  override def execJsonStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    framingDelimiter: String = JsonStreamFrames.DefaultFramingDelimiter,
    maxFrameLength: Option[Int] = None,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): java.util.concurrent.Flow.Publisher[JsValue] =
    SourcePublishersAkka.deferred(
      execJsonStream(
        site,
        endPoint,
        method,
        endPointParam,
        params,
        bodyParams,
        extraHeaders,
        framingDelimiter,
        maxFrameLength,
        stripPrefix,
        stripSuffix
      )
    )

  override def execRawStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): java.util.concurrent.Flow.Publisher[java.nio.ByteBuffer] =
    SourcePublishersAkka.deferred(
      execRawStream(site, endPoint, method, endPointParam, params, bodyParams, extraHeaders)
        // normalize transport failures to the Cequence taxonomy on the PUBLISHER path only,
        // matching the jdk engine (the Source-typed execRawStream keeps its raw errors)
        .recover(handleException(site, endPoint))
        .map(_.asByteBuffer)
    )

  override def execRawStream(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): Source[ByteString, NotUsed] = {
    val prefix = serviceAndEndpoint(site, Some(endPoint))
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)

    val requestWithBody = if (bodyParams.nonEmpty) {
      request.withBody(toJsBodyObject(bodyParams))
    } else
      request

    val source =
      requestWithBody.withMethod(method).stream().map { response =>
        response.bodyAsSource
      }

    // keep it like this because of older version of akka-stream (futureSource vs fromFutureSource)
    Source
      .fromFutureSource(source)
      .log(s"$prefix: execStreamRequestAux failed")
      .recover { case e: Throwable =>
        logger.error(s"$prefix: execStreamRequestAux failed: ${e.getMessage}.")
        throw e
      }
      .mapMaterializedValue(_ => NotUsed)
  }
}

object PlayWSStreamClientEngine {

  def apply(
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): PlayWSStreamClientEngine = new PlayWSStreamClientEngine(transportSettings)
}
