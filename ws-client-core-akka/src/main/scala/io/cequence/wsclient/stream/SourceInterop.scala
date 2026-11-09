package io.cequence.wsclient.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString

/**
 * Conversions between [[AsyncStream]] / [[AsyncPipe]] and Akka `Source` / `Flow` (since
 * 1.2.0), as extension methods: `import io.cequence.wsclient.stream.SourceInterop._`.
 *
 *   - `stream.toSource` compiles the stream natively: Akka leaves stay themselves, every
 *     operator is an Akka stage; `source.toAsyncStream.toSource` gives back the same `source`
 *   - `source.toAsyncStream` keeps the source native; the implicit `Materializer` is used only
 *     when the stream is consumed outside Akka (the fallback publisher)
 *   - `ByteString` sources and `Bytes` streams convert without copying (`toAsyncByteStream` /
 *     `toByteStringSource`)
 *   - `pipe.toFlow` / `flow.toAsyncPipe` likewise for transformations
 *
 * See `SourceConversions` for opt-in implicit conversions.
 */
object SourceInterop {

  implicit final class AsyncStreamToSource[A](private val stream: AsyncStream[A])
      extends AnyVal {
    def toSource: Source[A, NotUsed] = SourceCompiler.toSource(stream)
  }

  implicit final class AsyncBytesToSource(private val stream: AsyncStream[Bytes])
      extends AnyVal {
    def toByteStringSource: Source[ByteString, NotUsed] =
      SourceCompiler.toByteStringSource(stream)
  }

  implicit final class SourceToAsyncStream[A](private val source: Source[A, NotUsed])
      extends AnyVal {
    def toAsyncStream(
      implicit materializer: Materializer
    ): AsyncStream[A] =
      AsyncStream.fromNative(new SourceLeaf(source, () => materializer))
  }

  implicit final class ByteStringSourceToAsync(private val source: Source[ByteString, NotUsed])
      extends AnyVal {
    def toAsyncByteStream(
      implicit materializer: Materializer
    ): AsyncStream[Bytes] =
      byteStringLeaf(source, () => materializer)
  }

  implicit final class AsyncPipeToFlow[A, B](private val pipe: AsyncPipe[A, B])
      extends AnyVal {
    def toFlow: Flow[A, B, NotUsed] = SourceCompiler.toFlow(pipe)
  }

  implicit final class FlowToAsyncPipe[A, B](private val flow: Flow[A, B, NotUsed])
      extends AnyVal {
    def toAsyncPipe(
      implicit materializer: Materializer
    ): AsyncPipe[A, B] =
      AsyncPipe.fromNative(new FlowPipe(flow, () => materializer))
  }

  /** An engine's native stream (its fallback runs on the engine's own materializer). */
  private[wsclient] def engineLeaf[A](
    source: Source[A, NotUsed]
  )(
    materializer: Materializer
  ): AsyncStream[A] =
    AsyncStream.fromNative(new SourceLeaf(source, () => materializer))

  /** An engine's native byte stream - `toByteStringSource` gives back `source` itself. */
  private[wsclient] def byteStringEngineLeaf(
    source: Source[ByteString, NotUsed]
  )(
    materializer: Materializer
  ): AsyncStream[Bytes] =
    byteStringLeaf(source, () => materializer)

  private def byteStringLeaf(
    source: Source[ByteString, NotUsed],
    materializer: () => Materializer
  ): AsyncStream[Bytes] =
    AsyncStream
      .fromNative(new SourceLeaf(source, materializer))
      .map(SourceCompiler.FromByteString)
}

/**
 * Opt-in implicit conversions between [[AsyncStream]] / [[AsyncPipe]] and Akka `Source` /
 * `Flow`: `import io.cequence.wsclient.stream.SourceConversions._` - then e.g.
 * `stream.runWith(Sink.foreach(println))` compiles as it did when the stream was a `Source`.
 * Written as `implicit def`s (never Scala 3 `given Conversion`s), so they compile warning-free
 * under `-feature` on Scala 2 and 3. Prefer the explicit `SourceInterop` methods in new code.
 */
object SourceConversions {

  import scala.language.implicitConversions

  implicit def asyncStreamToSource[A](stream: AsyncStream[A]): Source[A, NotUsed] =
    SourceCompiler.toSource(stream)

  implicit def sourceToAsyncStream[A](
    source: Source[A, NotUsed]
  )(
    implicit materializer: Materializer
  ): AsyncStream[A] =
    AsyncStream.fromNative(new SourceLeaf(source, () => materializer))

  implicit def asyncPipeToFlow[A, B](pipe: AsyncPipe[A, B]): Flow[A, B, NotUsed] =
    SourceCompiler.toFlow(pipe)

  implicit def flowToAsyncPipe[A, B](
    flow: Flow[A, B, NotUsed]
  )(
    implicit materializer: Materializer
  ): AsyncPipe[A, B] =
    AsyncPipe.fromNative(new FlowPipe(flow, () => materializer))
}
