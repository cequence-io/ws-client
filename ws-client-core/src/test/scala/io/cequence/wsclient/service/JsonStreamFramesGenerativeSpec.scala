package io.cequence.wsclient.service

import io.cequence.wsclient.domain.CequenceWSException
import io.cequence.wsclient.service.JsonStreamFrames.{
  EndOfStream,
  FrameResult,
  JsonFrame,
  parseFrame
}
import io.cequence.wsclient.stream.StreamTransformers
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json._

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.util.Random

/**
 * Seeded (deterministic) generative checks of the SSE / JSON frame parsing:
 *
 *   - thousands of generated SSE bodies with KNOWN events through the core pipeline
 *     (`DelimiterFramer` + `JsonFrameParser`, the jdk / zio-http path; the akka / pekko
 *     engines share `parseFrame`) in random chunk sizes: LF and CRLF line endings, comment
 *     keep-alives, blank frames, `data:` with and without the space, field lines before the
 *     data line, trailing comment lines, `[DONE]` followed by junk, a truncated last event,
 *     and the raw-payload (Gemini array) mode
 *   - `parseFrame` against a verbatim copy of the 1.1.2 implementation on random single
 *     frames:
 * 1.1.3 may only ADD successes, and every other difference must be one of the documented ones
 * (the data field matched mid-line by 1.1.2, a `:`-leading line inside an event, a multi-line
 * `data:` event)
 */
class JsonStreamFramesGenerativeSpec extends AnyWordSpec with Matchers {

  private val Seed = 20261016L
  private val StreamCount = 1500
  private val FrameCount = 20000

  // ---------------------------------------------------------------------------------------
  // generators
  // ---------------------------------------------------------------------------------------

  // the characters and words the parser keys on, mixed into string values and comments
  private val Fragments = Seq(
    "data: ",
    "data:",
    ":",
    ": OPENROUTER PROCESSING",
    "[DONE]",
    "\\n",
    "\n",
    "\r",
    "\"",
    "{",
    "}",
    "[",
    "]",
    " ",
    "é",
    "日本",
    "x"
  )

  private def randomText(rnd: Random): String =
    (0 until rnd.nextInt(5)).map(_ => Fragments(rnd.nextInt(Fragments.length))).mkString

  // text that stays on ONE line (comments, field values)
  private def lineText(rnd: Random): String =
    randomText(rnd).replace("\r", "").replace("\n", "")

  private def jsonValue(
    rnd: Random,
    depth: Int = 0
  ): JsValue =
    rnd.nextInt(8) match {
      case 0     => JsNull
      case 1     => JsBoolean(rnd.nextBoolean())
      case 2     => JsNumber(BigDecimal(rnd.nextInt(100000) - 50000))
      case 3 | 4 => JsString(randomText(rnd))
      case 5 if depth < 3 =>
        JsArray((0 until rnd.nextInt(4)).map(_ => jsonValue(rnd, depth + 1)))
      case _ if depth < 3 =>
        JsObject((0 until rnd.nextInt(4)).map(i => s"k$i" -> jsonValue(rnd, depth + 1)))
      case _ => JsString("leaf")
    }

  private def dataPrefix(rnd: Random): String = if (rnd.nextInt(4) == 0) "data:" else "data: "

  private def commentLine(rnd: Random): String =
    rnd.nextInt(3) match {
      case 0 => ":"
      case 1 => ": OPENROUTER PROCESSING"
      case _ => ": " + lineText(rnd)
    }

  private def fieldLine(rnd: Random): String =
    rnd.nextInt(3) match {
      case 0 => "event: " + lineText(rnd)
      case 1 => "id: " + rnd.nextInt(1000)
      case _ => "retry: 3000"
    }

  /** One SSE event's lines (without terminators) carrying `json` as its single data line. */
  private def eventLines(
    rnd: Random,
    data: String
  ): Seq[String] = {
    val leading = (0 until rnd.nextInt(3)).map(_ => commentLine(rnd))
    val fields = (0 until rnd.nextInt(3)).map(_ => fieldLine(rnd))
    val trailing = (0 until rnd.nextInt(2)).map(_ => commentLine(rnd))
    leading ++ fields ++ Seq(dataPrefix(rnd) + data) ++ trailing
  }

  private final case class SseStream(
    body: String,
    delimiter: String,
    expected: Seq[JsValue]
  )

  /**
   * A whole SSE body: keep-alive comment frames and blank frames between events, an optional
   * `[DONE]` followed by junk that must not be emitted, or a truncated last event.
   */
  private def sseStream(rnd: Random): SseStream = {
    val crlf = rnd.nextBoolean()
    val eol = if (crlf) "\r\n" else "\n"
    val delimiter = eol + eol
    val sb = new StringBuilder
    val expected = Seq.newBuilder[JsValue]

    // an LF stream may end a line with CRLF before the "\n\n" delimiter -> a trailing CR in the frame
    def terminate(lines: Seq[String]): Unit = {
      val lastEol = if (!crlf && rnd.nextInt(5) == 0) "\r\n" else eol
      sb.append(lines.init.map(_ + eol).mkString)
        .append(lines.last)
        .append(lastEol)
        .append(eol)
    }

    def fillers(): Unit =
      (0 until rnd.nextInt(3)).foreach { _ =>
        rnd.nextInt(3) match {
          case 0 => terminate(Seq(commentLine(rnd))) // keep-alive frame
          case 1 => sb.append(delimiter) // blank frame
          case _ => terminate(Seq(commentLine(rnd), commentLine(rnd)))
        }
      }

    val events = (0 until rnd.nextInt(6)).map(_ => jsonValue(rnd))
    events.foreach { json =>
      fillers()
      terminate(eventLines(rnd, Json.stringify(json)))
      expected += json
    }
    fillers()

    rnd.nextInt(10) match {
      case n if n < 6 => // [DONE] (optionally without the space, after a comment) + junk
        val doneLines =
          (0 until rnd.nextInt(2)).map(_ => commentLine(rnd)) :+ (dataPrefix(rnd) + "[DONE]")
        terminate(doneLines)
        (0 until rnd.nextInt(3)).foreach { _ =>
          terminate(
            if (rnd.nextBoolean()) Seq("data: not json") else eventLines(rnd, "{\"junk\":1}")
          )
        }
      case n if n < 8 => // truncated last event: no terminator at all (allowTruncation)
        val json = jsonValue(rnd)
        sb.append(eventLines(rnd, Json.stringify(json)).mkString(eol))
        expected += json
      case _ => // just ends
    }

    SseStream(sb.toString, delimiter, expected.result())
  }

  /**
   * Gemini array style: raw multi-line payloads, a custom delimiter and prefix/suffix
   * stripping.
   */
  private def rawStream(rnd: Random): SseStream = {
    // objects only: the "[" / "]" stripping of the array framing is for its object elements
    val values = (1 to (1 + rnd.nextInt(5))).map(_ =>
      JsObject((0 until rnd.nextInt(4)).map(i => s"k$i" -> jsonValue(rnd, 1)))
    )
    val rendered =
      values.map(v => if (rnd.nextBoolean()) Json.prettyPrint(v) else Json.stringify(v))
    val leadingComments =
      if (rnd.nextInt(4) == 0)
        (0 until 1 + rnd.nextInt(2)).map(_ => commentLine(rnd) + "\n").mkString
      else ""
    val body =
      leadingComments + "[" + rendered.mkString("\n,\r\n") + "]" +
        (if (rnd.nextBoolean()) "\n,\r\n" else "")
    SseStream(body, "\n,\r\n", values)
  }

  // ---------------------------------------------------------------------------------------
  // the core pipeline, fed in random chunk sizes
  // ---------------------------------------------------------------------------------------

  private def runPipeline(
    rnd: Random,
    stream: SseStream,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): Either[Throwable, Seq[JsValue]] = {
    val bytes = stream.body.getBytes(StandardCharsets.UTF_8)
    val framer =
      new StreamTransformers.DelimiterFramer(
        stream.delimiter.getBytes(StandardCharsets.UTF_8),
        20000
      )
    val parser = new StreamTransformers.JsonFrameParser(stripPrefix, stripSuffix)
    val out = Seq.newBuilder[JsValue]
    var done = false

    def parse(frames: Seq[ByteBuffer]): Unit =
      frames.foreach { frame =>
        if (!done) {
          val output = parser.onInput(frame)
          out ++= output.elements
          done = output.done
        }
      }

    try {
      var offset = 0
      while (offset < bytes.length && !done) {
        val size = math.min(1 + rnd.nextInt(40), bytes.length - offset)
        parse(framer.onInput(ByteBuffer.wrap(bytes, offset, size)).elements)
        offset += size
      }
      if (!done) parse(framer.onUpstreamComplete())
      Right(out.result())
    } catch {
      case e: Throwable => Left(e)
    }
  }

  private def show(s: String): String = Json.stringify(JsString(s))

  "the SSE pipeline" should {

    s"emit exactly the generated events for $StreamCount generated SSE bodies (LF and CRLF)" in {
      val rnd = new Random(Seed)
      var withDone = 0
      var crlf = 0
      (1 to StreamCount).foreach { i =>
        val stream = sseStream(rnd)
        if (stream.delimiter == "\r\n\r\n") crlf += 1
        if (stream.body.contains("[DONE]")) withDone += 1
        runPipeline(rnd, stream) match {
          case Right(events) =>
            withClue(s"stream #$i ${show(stream.body)}: ") {
              events shouldBe stream.expected
            }
          case Left(e) =>
            fail(s"stream #$i ${show(stream.body)} failed: $e")
        }
      }
      info(s"$StreamCount streams, $crlf CRLF, $withDone with [DONE]")
    }

    s"emit exactly the generated values for raw (Gemini array) bodies" in {
      val rnd = new Random(Seed + 1)
      (1 to StreamCount / 3).foreach { i =>
        val stream = rawStream(rnd)
        runPipeline(rnd, stream, stripPrefix = Some("["), stripSuffix = Some("]")) match {
          case Right(events) =>
            withClue(s"raw stream #$i ${show(stream.body)}: ") {
              events shouldBe stream.expected
            }
          case Left(e) =>
            fail(s"raw stream #$i ${show(stream.body)} failed: $e")
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // differential: 1.1.3 parseFrame vs a verbatim copy of 1.1.2's
  // ---------------------------------------------------------------------------------------

  private def legacyParseFrame(
    frame: String,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None,
    itemPrefix: String = "data: "
  ): FrameResult =
    try {
      val itemStartIndex = frame.indexOf(itemPrefix)
      val data =
        if (itemStartIndex > -1) frame.substring(itemStartIndex + itemPrefix.length) else frame
      if (data.equals("[DONE]")) EndOfStream
      else {
        val strippedData =
          data.stripPrefix(stripPrefix.getOrElse("")).stripSuffix(stripSuffix.getOrElse(""))
        JsonFrame(Json.parse(strippedData))
      }
    } catch {
      case e: CequenceWSException => throw e
      case e: Throwable           => throw new CequenceWSException(s"legacy: ${e.getMessage}")
    }

  private def randomFrame(rnd: Random): String = {
    val eol = Seq("\n", "\r\n", "\r")(rnd.nextInt(3))
    val lines = (0 until 1 + rnd.nextInt(4)).map { _ =>
      rnd.nextInt(12) match {
        case 0 => commentLine(rnd)
        case 1 => "data: " + Json.stringify(jsonValue(rnd))
        case 2 => "data:" + Json.stringify(jsonValue(rnd))
        case 3 => Json.stringify(jsonValue(rnd))
        case 4 => Json.prettyPrint(jsonValue(rnd))
        case 5 => Seq("data: [DONE]", "data:[DONE]", "[DONE]")(rnd.nextInt(3))
        case 6 => ""
        case 7 => fieldLine(rnd)
        case 8 => Seq("not json", "data: not json", ":1}", "{\"a\"")(rnd.nextInt(4))
        case _ => "data: " + Json.stringify(jsonValue(rnd))
      }
    }
    lines.mkString(eol) + (if (rnd.nextInt(6) == 0) "\r" else "")
  }

  private sealed trait Outcome
  private final case class Ok(result: FrameResult) extends Outcome
  private final case class Failed(message: String) extends Outcome

  private def outcome(f: => FrameResult): Outcome =
    try Ok(f)
    catch { case e: CequenceWSException => Failed(e.getMessage) }

  private def splitLines(frame: String): Seq[String] = frame.split("\r\n|\r|\n", -1).toSeq

  // 1.1.2 cut the frame at a "data: " that is not at a line start (inside a value, a comment or
  // another field)
  private def legacyMatchedMidLine(frame: String): Boolean = {
    val i = frame.indexOf("data: ")
    i > 0 && frame.charAt(i - 1) != '\n' && frame.charAt(i - 1) != '\r'
  }

  // inside an SSE event a ':'-leading line is a comment (dropped by 1.1.3, payload for 1.1.2)
  private def colonLineInEvent(frame: String): Boolean = {
    val lines = splitLines(frame)
    lines.exists(_.startsWith("data:")) && lines.exists(_.startsWith(":"))
  }

  // a multi-line data: event is not joined by either version (documented limitation)
  private def multipleDataLines(frame: String): Boolean =
    splitLines(frame).count(_.startsWith("data:")) > 1

  // "data:" without the space was invisible to 1.1.2, which then parsed the whole frame (and
  // play-json silently drops whatever follows the first JSON value)
  private def spacelessDataLine(frame: String): Boolean =
    splitLines(frame).exists(l => l.startsWith("data:") && !l.startsWith("data: "))

  "parseFrame vs the 1.1.2 implementation" should {

    s"only add successes, and differ otherwise only in the documented ways ($FrameCount frames)" in {
      val rnd = new Random(Seed + 2)
      var same = 0
      var legacyFailed = 0
      var newlyAccepted = 0
      var explained = 0
      (1 to FrameCount).foreach { i =>
        val frame = randomFrame(rnd)
        val legacy = outcome(legacyParseFrame(frame))
        val current = outcome(parseFrame(frame))
        (legacy, current) match {
          case (Ok(a), Ok(b)) if a == b => same += 1
          case (Failed(_), Failed(_))   => legacyFailed += 1
          case (Failed(_), Ok(_))       => newlyAccepted += 1
          case _ =>
            val ok =
              legacyMatchedMidLine(frame) || colonLineInEvent(frame) ||
                multipleDataLines(frame) || spacelessDataLine(frame)
            withClue(s"frame #$i ${show(frame)}: legacy=$legacy current=$current: ") {
              ok shouldBe true
            }
            explained += 1
        }
      }
      info(
        s"$FrameCount frames: $same identical, $legacyFailed fail on both, $newlyAccepted newly accepted, $explained documented differences"
      )
      same should be > FrameCount / 10
      newlyAccepted should be > 0
    }

    "never throw anything but CequenceWSException and never return null" in {
      val rnd = new Random(Seed + 3)
      (1 to FrameCount / 4).foreach { _ =>
        val frame = randomFrame(rnd)
        try parseFrame(frame) should not be null
        catch { case _: CequenceWSException => () }
      }
    }
  }
}
