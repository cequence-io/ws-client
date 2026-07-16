package io.cequence.wsclient.service

import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.domain.CequenceWSException
import play.api.libs.json.{JsValue, Json}

/**
 * Pure, backend-agnostic parsing of SSE/JSON stream frames - the single source of truth for
 * the `data: ` item prefix, the `[DONE]` end-of-stream sentinel, prefix/suffix stripping, and
 * the JSON parse-error mapping shared by every streaming engine (Play/Akka, pekko-http, JDK).
 */
object JsonStreamFrames {

  val DefaultItemPrefix = "data: "
  val EndOfStreamToken = "[DONE]"
  val DefaultFramingDelimiter = "\n\n"
  val DefaultMaxFrameLength = 20000

  sealed trait FrameResult
  case object EndOfStream extends FrameResult
  final case class JsonFrame(json: JsValue) extends FrameResult

  /**
   * Parses one delimited frame. Behavior is identical to the historical per-engine
   * `jsonMarshaller`s: everything before (and including) the first `data: ` occurrence is
   * dropped; a bare `[DONE]` payload signals end of stream; otherwise the payload - after
   * optional prefix/suffix stripping - must be valid JSON.
   *
   * @throws CequenceWSException
   *   on a non-JSON payload or any other parse failure
   */
  def parseFrame(
    frame: String,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None,
    itemPrefix: String = DefaultItemPrefix
  ): FrameResult =
    try {
      val itemStartIndex = frame.indexOf(itemPrefix)

      val data =
        if (itemStartIndex > -1)
          frame.substring(itemStartIndex + itemPrefix.length)
        else
          frame

      if (data.equals(EndOfStreamToken))
        EndOfStream
      else {
        val strippedData =
          data.stripPrefix(stripPrefix.getOrElse("")).stripSuffix(stripSuffix.getOrElse(""))
        JsonFrame(Json.parse(strippedData))
      }
    } catch {
      case e: JsonParseException =>
        throw new CequenceWSException(
          s"JSON marshaller problem - response is not a JSON: ${e.getMessage}. Unmarshalled string: $frame."
        )
      case e: CequenceWSException =>
        throw e
      case e: Throwable =>
        throw new CequenceWSException(
          s"JSON marshaller problem - reason: ${e.getMessage}: Unmarshalled string: $frame."
        )
    }
}
