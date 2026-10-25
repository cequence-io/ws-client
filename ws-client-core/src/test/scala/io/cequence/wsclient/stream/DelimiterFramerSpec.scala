package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.CequenceWSException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.util.{Random, Try}

/**
 * `DelimiterFramer` against a verbatim copy of its 1.1.2 implementation (which compacted its
 * buffer once per FRAME - quadratic in the frames per chunk): seeded random delimiters, frame
 * limits, bodies and chunkings must yield identical frames and identical failures; plus the
 * linear-cost regression.
 */
class DelimiterFramerSpec extends AnyWordSpec with Matchers {

  private val Seed = 20261023L
  private val Trials = 50000

  // ---------------------------------------------------------------------------------------
  // the 1.1.2 implementation, verbatim
  // ---------------------------------------------------------------------------------------

  final class PreviousDelimiterFramer(
    delimiter: Array[Byte],
    maxFrameLength: Int
  ) extends StreamTransformer[ByteBuffer, ByteBuffer] {

    require(delimiter.nonEmpty, "framing delimiter must not be empty")

    private var buffer = Array.emptyByteArray
    private var scanFrom = 0

    override def onInput(input: ByteBuffer): StreamTransformer.Output[ByteBuffer] = {
      val incoming = new Array[Byte](input.remaining())
      input.get(incoming)
      buffer = buffer ++ incoming

      val frames = Seq.newBuilder[ByteBuffer]
      var delimiterIndex = indexOfDelimiter()
      while (delimiterIndex >= 0) {
        // bound EVERY frame, not just the unterminated remainder below - akka's
        // Framing.delimiter fails an oversized frame even when its delimiter arrives
        if (delimiterIndex > maxFrameLength)
          throw new CequenceWSException(
            s"Stream framing problem occurred. A frame of $delimiterIndex bytes " +
              s"exceeds the maximum frame length of $maxFrameLength."
          )
        frames += ByteBuffer.wrap(java.util.Arrays.copyOfRange(buffer, 0, delimiterIndex))
        buffer = java.util.Arrays
          .copyOfRange(buffer, delimiterIndex + delimiter.length, buffer.length)
        scanFrom = 0
        delimiterIndex = indexOfDelimiter()
      }

      if (buffer.length > maxFrameLength)
        throw new CequenceWSException(
          s"Stream framing problem occurred. Read ${buffer.length} bytes " +
            s"which is more than $maxFrameLength without seeing a line terminator."
        )

      StreamTransformer.Output(frames.result())
    }

    // a trailing frame without a terminating delimiter is still emitted (allowTruncation)
    override def onUpstreamComplete(): Seq[ByteBuffer] =
      if (buffer.nonEmpty) Seq(ByteBuffer.wrap(buffer)) else Nil

    private def indexOfDelimiter(): Int = {
      var i = math.max(scanFrom, 0)
      val limit = buffer.length - delimiter.length
      while (i <= limit) {
        var j = 0
        while (j < delimiter.length && buffer(i + j) == delimiter(j)) j += 1
        if (j == delimiter.length) return i
        i += 1
      }
      // next scan can skip what has already been ruled out (minus a possible partial match)
      scanFrom = math.max(0, buffer.length - delimiter.length + 1)
      -1
    }
  }

  // ---------------------------------------------------------------------------------------
  // a run: every onInput result (frames, or the failure that ends the run), then the
  // completion frames
  // ---------------------------------------------------------------------------------------

  private def text(buffer: ByteBuffer): String = {
    val bytes = new Array[Byte](buffer.remaining())
    buffer.duplicate().get(bytes)
    new String(bytes, StandardCharsets.ISO_8859_1)
  }

  private def run(
    framer: StreamTransformer[ByteBuffer, ByteBuffer],
    chunks: Seq[Array[Byte]]
  ): Seq[Either[String, Seq[String]]] = {
    val results = Seq.newBuilder[Either[String, Seq[String]]]
    val failed = chunks.exists { chunk =>
      Try(framer.onInput(ByteBuffer.wrap(chunk))).fold(
        e => { results += Left(s"${e.getClass.getName}: ${e.getMessage}"); true },
        output => { results += Right(output.elements.map(text)); false }
      )
    }
    if (!failed) results += Right(framer.onUpstreamComplete().map(text))
    results.result()
  }

  private val Delimiters = Seq("\n\n", "\r\n\r\n", "\n,\r\n", "\n", "aa", "aba", "abab")

  "DelimiterFramer" should {

    s"frame exactly like the 1.1.2 implementation ($Trials seeded trials)" in {
      val rnd = new Random(Seed)
      var failures = 0
      (1 to Trials).foreach { trial =>
        val delimiter = Delimiters(rnd.nextInt(Delimiters.size))
        val alphabet = (delimiter + "xb,\r\n").distinct
        val body = Array.fill(rnd.nextInt(120))(alphabet(rnd.nextInt(alphabet.length)).toByte)
        val maxFrameLength = Seq(0, 1, 2, 3, 5, 8, 13, 20, 20000)(rnd.nextInt(9))

        val chunks = Seq.newBuilder[Array[Byte]]
        var offset = 0
        while (offset < body.length) {
          val size = math.min(rnd.nextInt(20), body.length - offset) // empty chunks too
          chunks += java.util.Arrays.copyOfRange(body, offset, offset + size)
          offset += size
        }

        val delimiterBytes = delimiter.getBytes(StandardCharsets.ISO_8859_1)
        val expected =
          run(new PreviousDelimiterFramer(delimiterBytes, maxFrameLength), chunks.result())
        val actual = run(
          new StreamTransformers.DelimiterFramer(delimiterBytes, maxFrameLength),
          chunks.result()
        )
        if (expected.exists(_.isLeft)) failures += 1

        withClue(
          s"trial #$trial (delimiter ${delimiter.map(_.toInt)}, limit $maxFrameLength, " +
            s"body ${new String(body, StandardCharsets.ISO_8859_1).map(_.toInt)}): "
        ) {
          actual shouldBe expected
        }
      }
      // both outcomes are exercised
      failures should be > Trials / 10
      failures should be < Trials * 9 / 10
    }

    "frame a chunk of many tiny frames in linear time" in {
      // 1.1.2 copied the whole remainder per frame: ~C^2 / (2 * frame size) bytes per chunk
      // (this input: minutes)
      val blank = Array.fill[Byte](1024 * 1024)('\n'.toByte)
      val events = ("data: {}\n\n" * 100000).getBytes(StandardCharsets.UTF_8)

      val start = System.nanoTime()
      val blankFrames = new StreamTransformers.DelimiterFramer("\n\n".getBytes, 20000)
        .onInput(ByteBuffer.wrap(blank))
        .elements
      val eventFrames = new StreamTransformers.DelimiterFramer("\n\n".getBytes, 20000)
        .onInput(ByteBuffer.wrap(events))
        .elements
      val elapsedSeconds = (System.nanoTime() - start) / 1e9

      blankFrames.size shouldBe blank.length / 2
      blankFrames.forall(_.remaining() == 0) shouldBe true
      eventFrames.size shouldBe 100000
      eventFrames.map(text).distinct shouldBe Seq("data: {}")
      elapsedSeconds should be < 5.0
    }
  }
}
