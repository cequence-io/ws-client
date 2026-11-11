package io.cequence.wsclient.domain

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.stream.Bytes
import com.fasterxml.jackson.databind.JsonMappingException
import play.api.libs.json.JsValue
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.StandaloneWSRequest
import scala.util.control.NonFatal

case class PlayWsRichResponse(
  playWsResponse: Option[PlayWsResponse],
  status: StatusData,
  headers: Map[String, Seq[String]]
) extends RichResponse {
  override def response: Option[Response] = playWsResponse
}

final case class PlayWsResponse(
  rawResponse: StandaloneWSRequest#Response,
  serviceNameForLogging: String,
  endpointForLogging: Option[String]
) extends StreamedResponse {

  // a remote value never enters a message whole: the first 500 characters of the body
  override def json: JsValue =
    try {
      rawResponse.body[JsValue]
    } catch {
      case e: JsonParseException =>
        throw new CequenceWSException(
          s"${serviceNameForLogging} - ${endpointForLogging.getOrElse("N/A")}: '${bodyExcerpt}' is not a JSON.",
          e
        )
      case e: JsonMappingException =>
        throw new CequenceWSException(
          s"${serviceNameForLogging} - ${endpointForLogging.getOrElse("N/A")}: '${bodyExcerpt}' is an unmappable JSON.",
          e
        )
      case e: CequenceWSException => throw e
      // e.g. Jackson's StreamConstraintsException (nesting / length limits) - as the other
      // engines' responses wrap it
      case NonFatal(e) =>
        throw new CequenceWSException(
          s"${serviceNameForLogging} - ${endpointForLogging
              .getOrElse("N/A")}: '${bodyExcerpt}' is not a JSON: ${e.getMessage}",
          e
        )
    }

  private def bodyExcerpt: String =
    string.take(500) // `string`: typed (Scala 3 picks body[JsValue] here)

  override def string: String = rawResponse.body

  // the raw body, binary-safe (since 1.2.0)
  override def bytes: Bytes = Bytes.unsafeWrap(rawResponse.bodyAsBytes.toArrayUnsafe())

  override def source: Source[ByteString, _] = rawResponse.bodyAsSource
}
