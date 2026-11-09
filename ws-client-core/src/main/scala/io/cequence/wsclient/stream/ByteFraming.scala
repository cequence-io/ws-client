package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.{CequenceWSException, CequenceWSFrameTooLongException}
import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.impl.Stateful

/**
 * Frames a byte stream (named `ByteFraming`, not `Framing`, so a wildcard import never clashes
 * with Akka's / Pekko's `Framing`). The framers are `stateful` stages, so they run natively in
 * every backend; their buffer grows geometrically and is compacted once per chunk - linear in
 * the input, whatever the chunking.
 */
object ByteFraming {

  /**
   * Splits the bytes on `delimiter` (not included in the frames; consecutive delimiters yield
   * empty frames) - the semantics of the engines' SSE framing and, nearly, of Akka's
   * `Framing.delimiter` (unlike Akka, a frame within `delimiter.length - 1` bytes of
   * `maxFrameLength` fails when a chunk boundary splits its delimiter; Akka waits for the
   * rest). A frame longer than `maxFrameLength` fails the stream with a
   * [[io.cequence.wsclient.domain.CequenceWSFrameTooLongException]] - also one whose delimiter
   * arrives, and an unterminated rest that outgrows the limit. At the end, an unterminated
   * non-empty rest is emitted as the last frame (`allowTruncation`) or fails the stream.
   * Unlike Akka's `Framing.delimiter`, `allowTruncation` defaults to `true` (as the engines
   * frame); pass `false` for Akka's behavior.
   */
  def delimiter(
    delimiter: Bytes,
    maxFrameLength: Int,
    allowTruncation: Boolean = true
  ): AsyncPipe[Bytes, Bytes] = {
    require(delimiter.nonEmpty, "The framing delimiter must not be empty")
    require(maxFrameLength > 0, s"maxFrameLength must be positive: $maxFrameLength")
    val delimiterBytes = delimiter.toArray

    AsyncPipe.fromFunction[Bytes, Bytes] { in =>
      Stateful[Bytes, DelimiterFramer, Bytes](
        in,
        () => new DelimiterFramer(delimiterBytes, maxFrameLength),
        (
          framer,
          chunk
        ) => Step(framer, framer.push(chunk)),
        framer => framer.finish(allowTruncation),
        "delimiter"
      )
    }
  }

  /**
   * Splits the bytes into lines on `\n` (a trailing `\r` is dropped with `stripCR`), decoded
   * as UTF-8. A line longer than `maxLineLength` bytes fails the stream; an unterminated last
   * line is emitted.
   */
  def lines(
    maxLineLength: Int,
    stripCR: Boolean = true
  ): AsyncPipe[Bytes, String] =
    delimiter(Newline, maxLineLength).map { line =>
      val stripped =
        if (stripCR && line.nonEmpty && line(line.length - 1) == '\r'.toByte)
          line.take(line.length - 1)
        else
          line
      stripped.utf8String
    }

  private val Newline = Bytes.utf8("\n")

  // per-run framing state (allocated in the stage's init)
  private final class DelimiterFramer(
    delimiter: Array[Byte],
    maxFrameLength: Int
  ) {
    private var buffer = new Array[Byte](256)
    private var start = 0 // first byte not yet framed
    private var end = 0 // end of the buffered bytes
    private var scanFrom = 0 // no delimiter starts before this index (in buffer coordinates)

    def push(chunk: Bytes): List[Bytes] = {
      append(chunk)

      var frames = List.empty[Bytes]
      var delimiterAt = indexOfDelimiter()
      while (delimiterAt >= 0) {
        val frameLength = delimiterAt - start
        if (frameLength > maxFrameLength)
          throw new CequenceWSFrameTooLongException(
            s"Stream framing problem occurred. A frame of $frameLength bytes " +
              s"exceeds the maximum frame length of $maxFrameLength.",
            maxFrameLength
          )
        frames =
          Bytes.unsafeWrap(java.util.Arrays.copyOfRange(buffer, start, delimiterAt)) :: frames
        start = delimiterAt + delimiter.length
        scanFrom = start
        delimiterAt = indexOfDelimiter()
      }

      if (end - start > maxFrameLength)
        throw new CequenceWSFrameTooLongException(
          s"Stream framing problem occurred. Read ${end - start} bytes " +
            s"which is more than $maxFrameLength without seeing a line terminator.",
          maxFrameLength
        )

      frames.reverse
    }

    def finish(allowTruncation: Boolean): List[Bytes] =
      if (end == start)
        Nil
      else if (allowTruncation)
        List(Bytes.unsafeWrap(java.util.Arrays.copyOfRange(buffer, start, end)))
      else
        throw new CequenceWSException(
          "Stream finished but there was a truncated final frame in the buffer"
        )

    // appends, compacting the framed prefix away first and growing geometrically
    private def append(chunk: Bytes): Unit = {
      val length = chunk.length
      if (length > 0) {
        val buffered = end - start
        if (start > 0 && end + length > buffer.length) {
          System.arraycopy(buffer, start, buffer, 0, buffered)
          scanFrom -= start
          start = 0
          end = buffered
        }
        if (end + length > buffer.length) {
          val needed = end.toLong + length
          if (needed > Int.MaxValue - 8)
            throw new CequenceWSFrameTooLongException(
              s"Stream framing problem occurred. Read $needed bytes without a frame end.",
              maxFrameLength
            )
          val grown = math.max(needed, math.min(buffer.length.toLong * 2, Int.MaxValue - 8L))
          buffer = java.util.Arrays.copyOf(buffer, grown.toInt)
        }
        System.arraycopy(chunk.unsafeArray, chunk.unsafeOffset, buffer, end, length)
        end += length
      }
    }

    private def indexOfDelimiter(): Int = {
      var i = math.max(start, scanFrom)
      val limit = end - delimiter.length
      while (i <= limit) {
        var j = 0
        while (j < delimiter.length && buffer(i + j) == delimiter(j)) j += 1
        if (j == delimiter.length) return i
        i += 1
      }
      // the next scan skips what is ruled out (minus a possible partial match at the end)
      scanFrom = math.max(start, end - delimiter.length + 1)
      -1
    }
  }
}
