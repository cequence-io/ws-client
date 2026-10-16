package io.cequence.wsclient.service

import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.domain.CequenceWSException
import play.api.libs.json.{JsValue, Json}

import java.util.regex.Pattern

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

  // SSE line breaks: CRLF, CR or LF
  private val LineBreak = Pattern.compile("\r\n|\r|\n")

  /**
   * Parses one delimited frame.
   *
   *   - SSE comment lines (first character `:`, see [[Skip]]) are dropped; a frame left with
   *     blank lines only yields [[Skip]]. CR and CRLF line breaks inside a frame are
   *     understood (the frame delimiter itself is the engines' concern - `\n\n` by default,
   *     see [[DefaultFramingDelimiter]]).
   *   - An SSE event is a frame with a line STARTING with the item prefix (`data:` with or
   *     without the one space the SSE spec allows after the colon; the field is never matched
   *     mid-line): the payload is everything after that prefix, including any following lines;
   *     field lines before it (`event:`, `id:`) are dropped. A payload that trims to `[DONE]`
   *     signals the end of the stream; otherwise it must be valid JSON after the optional
   *     prefix/suffix stripping.
   *   - A frame without such a line is a raw payload (e.g. the Gemini array framing with its
   *     own delimiter and prefix/suffix): leading comment lines are dropped (a JSON value
   *     never starts with `:`), the rest is parsed as is - its interior `:`-leading lines are
   *     part of it.
   *
   * Not a full SSE parser: a multi-line `data:` event is not joined, and field lines AFTER the
   * data line are not dropped.
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
      // the SSE value may follow the field's colon with or without one space ("data: " / "data:")
      val fieldPrefix = itemPrefix.stripSuffix(" ")

      // line-level work only for frames with a comment line or a CR; plain LF frames are used as is
      val needsLineScan = isComment(frame) || frame.contains("\n:") || frame.indexOf('\r') > -1
      val allLines = if (needsLineScan) LineBreak.split(frame, -1) else Array(frame)
      val lines = if (needsLineScan) allLines.filterNot(isComment) else allLines

      if (lines.forall(_.trim.isEmpty))
        Skip // comment lines and/or blank lines only
      else {
        val content =
          if (!needsLineScan) frame
          else if (lines.exists(_.startsWith(fieldPrefix)))
            lines.mkString("\n") // SSE event: comment lines dropped, line breaks normalized
          else if (isComment(frame))
            allLines.dropWhile(isComment).mkString("\n") // raw payload after leading comments
          else
            frame // raw payload: its interior ':'-leading lines are part of it

        // the field counts only at the start of a line
        val dataStart =
          if (content.startsWith(fieldPrefix)) 0
          else {
            val i = content.indexOf("\n" + fieldPrefix)
            if (i > -1) i + 1 else -1
          }

        val data =
          if (dataStart > -1) {
            val value = content.substring(dataStart + fieldPrefix.length)
            // one optional space after the colon (only when the configured prefix has one)
            if (fieldPrefix.length < itemPrefix.length && value.startsWith(" "))
              value.substring(1)
            else value
          } else
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
