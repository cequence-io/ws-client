package io.cequence.wsclient.verification

import io.cequence.wsclient.domain.CequenceWSException
import io.cequence.wsclient.service.JsonStreamFrames
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import scala.util.{Random, Try}

/**
 * `JsonStreamFrames.parseFrame` against `SseFrames.parseFrame` of the Lean model. The model
 * stops at the string handed to `Json.parse`, so a payload is compared after parsing it with
 * the same parser: the Scala result's JSON, or both failing.
 *
 * Frames are lines from a pool - `data:` lines with and without the space, `[DONE]`, field
 * lines, comments, blank and white-space lines, raw JSON, `data:` mid-line, a JSON value split
 * over lines - joined by LF, CRLF, CR or nothing; item prefixes and prefix / suffix stripping
 * vary (the Gemini array framing strips `[`, `,` and `]`).
 */
object SseFrameConformance extends LeanConformance.Target {

  val name = "frame"

  private val pieces = Vector(
    "data: {}",
    "data:{\"a\":1}",
    "data: [DONE]",
    "data:[DONE]  ",
    " data: {}",
    "data:  {\"b\":[1]}",
    "event: message",
    "id: 7",
    ": keep-alive",
    ":",
    "",
    "  ",
    "\t",
    "{\"c\":2}",
    "[1,2]",
    "1",
    "data: {\"x\":",
    "\"y\"}",
    "xdata: {}",
    "data:",
    "data: ",
    "[DONE]",
    ",{\"d\":3}",
    "]",
    ": OPENROUTER PROCESSING",
    "retry: 10",
    "data: \"s\""
  )
  private val breaks = Vector("\n", "\r\n", "\r", "")
  private val itemPrefixes = Vector("data: ", "data: ", "data:", "event: ", "x", "data:  ")
  private val strips = Vector(None, None, Some("["), Some(","), Some("]"), Some(""))

  def generate(random: Random): (String, String) = {
    val frame = (0 until 1 + random.nextInt(4)).map { _ =>
      pieces(random.nextInt(pieces.size)) + breaks(random.nextInt(breaks.size))
    }.mkString
    val itemPrefix = itemPrefixes(random.nextInt(itemPrefixes.size))
    val stripPrefix = strips(random.nextInt(strips.size))
    val stripSuffix = strips(random.nextInt(strips.size))

    val expected =
      try {
        JsonStreamFrames.parseFrame(frame, stripPrefix, stripSuffix, itemPrefix) match {
          case JsonStreamFrames.Skip            => "skip"
          case JsonStreamFrames.EndOfStream     => "done"
          case JsonStreamFrames.JsonFrame(json) => s"json ${Json.stringify(json)}"
        }
      } catch { case _: CequenceWSException => "failure" }

    def text(value: Option[String]) = value.fold("-")(Texts.hex)
    val line =
      s"frame ${Texts.hex(frame)} ${Texts.hex(itemPrefix)} ${text(stripPrefix)} ${text(stripSuffix)}"
    (line, expected)
  }

  override def accepts(
    expected: String,
    actual: String
  ): Boolean =
    actual.split(" ", 2) match {
      case Array("skip") => expected == "skip"
      case Array("done") => expected == "done"
      case Array("payload", hexText) =>
        val bytes =
          if (hexText == ".") Array.emptyByteArray
          else hexText.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
        Try(Json.parse(new String(bytes, StandardCharsets.UTF_8)))
          .map(Json.stringify)
          .toOption match {
          case Some(json) => expected == s"json $json"
          case None       => expected == "failure"
        }
      case _ => false
    }
}
