package io.cequence.wsclient.domain

import io.cequence.wsclient.stream.Bytes
import play.api.libs.json.{JsValue, Json}

import java.nio.charset.{Charset, StandardCharsets}

/**
 * A [[Response]] backed by an already-materialized body string - reusable by any backend that
 * does not need streaming access to the raw response (JDK HttpClient, sttp, pekko-http, ...).
 */
case class StringBackedResponse(
  string: String,
  serviceNameForLogging: String = "",
  endpointForLogging: Option[String] = None
) extends Response {

  override lazy val json: JsValue =
    try
      Json.parse(string)
    catch {
      case e: Exception =>
        throw new CequenceWSException(
          s"$serviceNameForLogging${endpointForLogging.map("." + _).getOrElse("")}: Response is not a JSON: ${e.getMessage}. Response: ${string
              .take(500)}"
        )
    }
}

/**
 * A [[Response]] backed by the raw body bytes (since 1.2.0): `bytes` is binary-safe; `string`
 * is decoded with `charset` (typically the Content-Type's charset, else UTF-8), `json` parsed
 * from it. A plain class (not a case class), so it can gain fields compatibly.
 */
final class BytesBackedResponse(
  override val bytes: Bytes,
  val charset: Charset,
  val serviceNameForLogging: String,
  val endpointForLogging: Option[String]
) extends Response {

  override lazy val string: String = bytes.decodeString(charset)

  override lazy val json: JsValue =
    try
      Json.parse(string)
    catch {
      case e: Exception =>
        throw new CequenceWSException(
          s"$serviceNameForLogging${endpointForLogging.map("." + _).getOrElse("")}: Response is not a JSON: ${e.getMessage}. Response: ${string
              .take(500)}"
        )
    }

  override def toString: String =
    s"BytesBackedResponse(${bytes.length} bytes, $serviceNameForLogging${endpointForLogging.map("." + _).getOrElse("")})"
}

object BytesBackedResponse {

  def apply(
    bytes: Bytes,
    charset: Charset,
    serviceNameForLogging: String = "",
    endpointForLogging: Option[String] = None
  ): BytesBackedResponse =
    new BytesBackedResponse(bytes, charset, serviceNameForLogging, endpointForLogging)
}

/**
 * The response of a buffered call, from its raw bytes (internal): the text decoded with
 * `charset`; a body that is valid UTF-8 (decoded without replacement characters) keeps only
 * the text - `Response.bytes` re-encodes it to exactly the received bytes - any other body
 * keeps its raw bytes too (binary-safe `bytes`, at the cost of holding the body twice).
 */
private[wsclient] object ResponseBodies {

  def apply(
    raw: Array[Byte],
    charset: Charset,
    serviceNameForLogging: String,
    endpointForLogging: Option[String]
  ): StringBackedResponse = {
    val text = new String(raw, charset)
    if (charset == StandardCharsets.UTF_8 && text.indexOf('\uFFFD') < 0)
      StringBackedResponse(text, serviceNameForLogging, endpointForLogging)
    else
      new TextAndBytesResponse(
        text,
        Bytes.unsafeWrap(raw),
        serviceNameForLogging,
        endpointForLogging
      )
  }
}

/**
 * A body whose text was decoded already, kept together with the raw bytes (internal) - a
 * [[StringBackedResponse]] like every buffered non-Play body, so code matching on that type
 * (as in 1.1.x) keeps working; its `copy` gives a plain StringBackedResponse.
 */
private[wsclient] final class TextAndBytesResponse(
  decoded: String,
  override val bytes: Bytes,
  service: String,
  endpoint: Option[String]
) extends StringBackedResponse(decoded, service, endpoint) {

  // equal only to a TextAndBytesResponse with the same text AND raw bytes (two bodies that
  // decode to the same text with replacement characters are different bodies)
  override def canEqual(other: Any): Boolean = other.isInstanceOf[TextAndBytesResponse]

  override def equals(other: Any): Boolean =
    other match {
      case that: TextAndBytesResponse =>
        (that eq this) ||
        (string == that.string && serviceNameForLogging == that.serviceNameForLogging &&
          endpointForLogging == that.endpointForLogging && bytes == that.bytes)
      case _ => false
    }

  override def hashCode: Int = (string, serviceNameForLogging, endpointForLogging, bytes).##

  override def toString: String =
    s"TextAndBytesResponse(${bytes.length} bytes, $serviceNameForLogging${endpointForLogging.map("." + _).getOrElse("")})"
}

/**
 * A plain [[RichResponse]] carrier - reusable by non-Play backends.
 */
case class SimpleRichResponse(
  response: Option[Response],
  status: StatusData,
  headers: Map[String, Seq[String]]
) extends RichResponse
