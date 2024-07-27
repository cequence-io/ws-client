package io.cequence.wsclient.service.ws.stream

import akka.NotUsed
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
  RichResponse,
  WsRequestContext
}
import io.cequence.wsclient.service.{WSClientEngine, WSClientEngineStreamExtra}
import io.cequence.wsclient.service.ws.PlayWSClientEngine
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.libs.ws.JsonBodyWritables._

import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext

/**
 * Stream request support specifically tailored for OpenAI API.
 *
 * @since Feb
 *   2023
 */
private trait PlayWSStreamClientEngine
    extends PlayWSClientEngine
    with WSClientEngineStreamExtra {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val itemPrefix = "data: "
  private val endOfStreamToken = "[DONE]"
  protected val maxFrameLength = 5000

  private implicit val jsonMarshaller: Unmarshaller[ByteString, JsValue] =
    Unmarshaller.strict[ByteString, JsValue] { byteString =>
      val string = byteString.utf8String

      try {
        val itemStartIndex = string.indexOf(itemPrefix)
        val data =
          if (itemStartIndex > -1)
            string.substring(itemStartIndex + itemPrefix.length)
          else
            string

        if (data.equals(endOfStreamToken)) JsString(endOfStreamToken) else Json.parse(data)
      } catch {
        case e: JsonParseException =>
          val message =
            s"JSON marshaller problem - response is not a JSON: ${e.getMessage}. Unmarshalled string: $string."
          logger.error(message)
          throw new CequenceWSException(message)

        case e: Throwable =>
          val message =
            s"JSON marshaller problem - reason: ${e.getMessage}: Unmarshalled string: $string."
          throw new CequenceWSException(message)
      }
    }

  override def execJsonStream(
    endPoint: String,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil
  ): Source[JsValue, NotUsed] = {
    val source = execStreamRequestAux[JsValue](
      endPoint,
      method,
      endPointParam,
      params,
      bodyParams,
      Framing.delimiter(ByteString("\n\n"), maxFrameLength, allowTruncation = true),
      {
        case e: JsonParseException =>
          val message = s"${serviceAndEndpoint(Some(endPoint))}: Response is not a JSON. ${e.getMessage}."
          logger.error(message)
          throw new CequenceWSException(message)
        case e: FramingException =>
          val message = s"${serviceAndEndpoint(Some(endPoint))}: Stream framing problem occurred. ${e.getMessage}."
          logger.error(message)
          throw new CequenceWSException(message)
        case e: TimeoutException =>
          val message = s"${serviceAndEndpoint(Some(endPoint))}: Time out. ${e.getMessage}."
          logger.error(message)
          throw new CequenceWSTimeoutException(message)
        case e: UnknownHostException =>
          val message = s"${serviceAndEndpoint(Some(endPoint))}: Host name cannot be resolved. ${e.getMessage}."
          logger.error(message)
          throw new CequenceWSUnknownHostException(message)
        case e: Throwable =>
          val message = s"${serviceAndEndpoint(Some(endPoint))}: Fatal problem! ${e.getMessage}."
          logger.error(message)
          throw new CequenceWSException(message)
      }
    )

    // take until you encounter the end of stream marked with DONE
    source.takeWhile(_ != JsString(endOfStreamToken))
  }

  protected def execStreamRequestAux[T](
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    framing: Flow[ByteString, ByteString, NotUsed],
    recoverBlock: PartialFunction[Throwable, T]
  )(
    implicit um: Unmarshaller[ByteString, T],
    materializer: Materializer
  ): Source[T, NotUsed] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)

    val requestWithBody = if (bodyParams.nonEmpty) {
      val bodyParamsX = bodyParams.collect { case (fieldName, Some(jsValue)) => (fieldName, jsValue) }
      request.withBody(JsObject(bodyParamsX))
    } else
      request

    val source =
      requestWithBody.withMethod(method).stream().map { response =>
        response.bodyAsSource
          .via(framing)
          .mapAsync(1)(bytes => Unmarshal(bytes).to[T]) // unmarshal one by one
          .recover(recoverBlock)
      }

    // keep it like this because of older version of akka-stream (futureSource vs fromFutureSource)
    Source
      .fromFutureSource(source)
      .log(s"${serviceAndEndpoint(Some(endPoint))}: execStreamRequestAux failed")
      .recover { case e: Throwable =>
        logger.error(
          s"${serviceAndEndpoint(Some(endPoint))}: execStreamRequestAux failed: ${e.getMessage}."
        )
        throw e
      }
      .mapMaterializedValue(_ => NotUsed)
  }
}

object PlayWSStreamClientEngine {

  def apply(
    coreUrl: String,
    requestContext: WsRequestContext = WsRequestContext(),
    recoverErrors: String => PartialFunction[Throwable, RichResponse] = defaultRecoverErrors
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): WSClientEngine with WSClientEngineStreamExtra =
    new PlayWSStreamClientEngineImpl(coreUrl, requestContext, recoverErrors)

  private final class PlayWSStreamClientEngineImpl(
    override protected val coreUrl: String,
    override protected val requestContext: WsRequestContext,
    override protected val recoverErrors: String => PartialFunction[Throwable, RichResponse]
  )(
    override protected implicit val materializer: Materializer,
    override protected implicit val ec: ExecutionContext
  ) extends PlayWSStreamClientEngine

  private def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
    (serviceEndPointName: String) =>
      {
        case e: TimeoutException =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}."
          )
        case e: UnknownHostException =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}."
          )
      }
  }
}
