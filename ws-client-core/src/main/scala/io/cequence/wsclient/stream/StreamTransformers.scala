package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.CequenceWSException
import io.cequence.wsclient.service.JsonStreamFrames
import play.api.libs.json.JsValue

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Backend-agnostic stream transformers used to assemble a JSON event stream from raw HTTP
 * response chunks - the neutral counterpart of `Framing.delimiter(...) + jsonMarshaller` in
 * the akka/pekko engines. Semantics mirror those engines exactly: frames are split on a byte
 * delimiter with a maximum frame length, a trailing unterminated frame is emitted on
 * completion (allowTruncation), and the `[DONE]` sentinel terminates the stream early.
 */
private[wsclient] object StreamTransformers {

  /** Flattens `java.net.http`'s `Publisher[java.util.List[ByteBuffer]]` chunk lists. */
  final class ByteBufferListFlatten
      extends StreamTransformer[java.util.List[ByteBuffer], ByteBuffer] {
    override def onInput(
      input: java.util.List[ByteBuffer]
    ): StreamTransformer.Output[ByteBuffer] = {
      val builder = Seq.newBuilder[ByteBuffer]
      input.forEach(buffer => builder += buffer)
      StreamTransformer.Output(builder.result())
    }
  }

  /**
   * Splits a raw byte stream into frames on `delimiter` (equivalent of akka's
   * `Framing.delimiter(delimiter, maxFrameLength, allowTruncation = true)`).
   */
  final class DelimiterFramer(
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

  /**
   * Parses delimited frames into JSON events with the shared [[JsonStreamFrames]] semantics;
   * terminates the stream early at the `[DONE]` sentinel.
   */
  final class JsonFrameParser(
    stripPrefix: Option[String],
    stripSuffix: Option[String]
  ) extends StreamTransformer[ByteBuffer, JsValue] {

    override def onInput(input: ByteBuffer): StreamTransformer.Output[JsValue] = {
      val bytes = new Array[Byte](input.remaining())
      input.get(bytes)
      val frame = new String(bytes, StandardCharsets.UTF_8)

      JsonStreamFrames.parseFrame(frame, stripPrefix, stripSuffix) match {
        case JsonStreamFrames.EndOfStream     => StreamTransformer.Output(Nil, done = true)
        case JsonStreamFrames.JsonFrame(json) => StreamTransformer.Output(Seq(json))
      }
    }
  }
}
