package io.cequence.wsclient.stream

import io.cequence.wsclient.service.JsonStreamFrames
import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.impl.Stateful
import play.api.libs.json.JsValue

/** The engines' JSON / SSE event framing, as pipes over any byte stream. */
object JsonStreamPipes {

  /**
   * JSON events from raw bytes, exactly as the engines' `execJsonStream`: frames split on
   * `framingDelimiter` (at most `maxFrameLength` bytes each - a longer one fails with a
   * [[io.cequence.wsclient.domain.CequenceWSFrameTooLongException]]), then parsed with the
   * shared [[io.cequence.wsclient.service.JsonStreamFrames.parseFrame]] semantics: the `data:`
   * prefix is stripped, SSE comments and blank frames are skipped, and the `[DONE]` sentinel
   * completes the stream (the upstream is cancelled - it ends structurally, so a JSON string
   * `"[DONE]"` is an ordinary event).
   */
  def jsonFrames(
    framingDelimiter: String = JsonStreamFrames.DefaultFramingDelimiter,
    maxFrameLength: Int = JsonStreamFrames.DefaultMaxFrameLength,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): AsyncPipe[Bytes, JsValue] =
    ByteFraming
      .delimiter(Bytes.utf8(framingDelimiter), maxFrameLength)
      .andThen(parsedFrames(stripPrefix, stripSuffix))

  /** Parses already delimited frames with the [[jsonFrames]] semantics. */
  def parsedFrames(
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): AsyncPipe[Bytes, JsValue] =
    AsyncPipe.fromFunction[Bytes, JsValue] { in =>
      Stateful[Bytes, Unit, JsValue](
        in,
        () => (),
        (
          _,
          frame
        ) =>
          JsonStreamFrames.parseFrame(frame.utf8String, stripPrefix, stripSuffix) match {
            case JsonStreamFrames.JsonFrame(json) => Step((), json :: Nil)
            case JsonStreamFrames.Skip            => Step(())
            case JsonStreamFrames.EndOfStream     => Step((), Nil, stop = true)
          },
        _ => Nil,
        "jsonFrames"
      )
    }
}
