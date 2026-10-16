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
   * A frame that carries no event and must be ignored: SSE comment lines only (a line starting
   * with `:`, e.g. OpenRouter's `: OPENROUTER PROCESSING` or OpenAI's bare `:` keep-alives) or
   * blank lines only.
   */
  case object Skip extends FrameResult

  // SSE: a line whose first character is a colon is a comment (keep-alive) and carries no data
  private def isComment(line: String): Boolean = line.startsWith(":")

  /**
   * Parses one delimited frame. A frame made of SSE comment lines (`:`-prefixed, see [[Skip]])
   * and/or blank lines only yields [[Skip]]; in an SSE event (a frame with a `data: ` line)
   * the comment lines are dropped. Otherwise the behavior is that of the historical per-engine
   * `jsonMarshaller`s: everything before (and including) the first `data: ` occurrence is
   * dropped; a bare `[DONE]` payload (surrounding whitespace, e.g. a CR from CRLF line
   * endings, ignored) signals end of stream; otherwise the payload - after optional
   * prefix/suffix stripping - must be valid JSON. A frame WITHOUT a `data: ` line is a raw
   * payload (e.g. the Gemini array framing) whose `:`-leading lines are its own, so it is
   * parsed as is.
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
      val content =
        if (isComment(frame) || frame.contains("\n:")) {
          val dataLines = frame.split("\r?\n", -1).filterNot(isComment)

          if (dataLines.forall(_.trim.isEmpty))
            "" // comment-only frame
          else if (dataLines.exists(_.contains(itemPrefix)))
            dataLines.mkString("\n") // SSE event: comment lines dropped
          else
            frame // raw payload: ':'-leading lines are part of it
        } else
          frame

      if (content.trim.isEmpty)
        Skip
      else {
        val itemStartIndex = content.indexOf(itemPrefix)

        val data =
          if (itemStartIndex > -1)
            content.substring(itemStartIndex + itemPrefix.length)
          else
            content

        if (data.trim.equals(EndOfStreamToken))
          EndOfStream
        else {
          val strippedData =
            data.stripPrefix(stripPrefix.getOrElse("")).stripSuffix(stripSuffix.getOrElse(""))
          JsonFrame(Json.parse(strippedData))
        }
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
