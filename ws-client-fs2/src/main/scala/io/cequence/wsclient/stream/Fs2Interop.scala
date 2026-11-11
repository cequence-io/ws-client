package io.cequence.wsclient.stream

import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime

import java.util.concurrent.Flow

/**
 * Runs fs2 streams as `Flow.Publisher`s - the fallback of an fs2 stream consumed outside fs2
 * (each subscription a fresh run).
 */
trait Fs2Runner[F[_]] {
  def publisher[A](stream: fs2.Stream[F, A]): Flow.Publisher[A]
}

object Fs2Runner {

  /** For `IO`, on the given runtime (e.g. `cats.effect.unsafe.implicits.global`). */
  implicit def io(
    implicit runtime: IORuntime
  ): Fs2Runner[IO] =
    new Fs2Runner[IO] {
      override def publisher[A](stream: fs2.Stream[IO, A]): Flow.Publisher[A] =
        fs2.interop.flow.unsafeToPublisher(stream)
    }

  /** For any `F`, through `dispatcher` (which must outlive the streams it runs). */
  def fromDispatcher[F[_]](
    dispatcher: Dispatcher[F]
  )(
    implicit F: Async[F]
  ): Fs2Runner[F] =
    new Fs2Runner[F] {
      override def publisher[A](stream: fs2.Stream[F, A]): Flow.Publisher[A] =
        new Flow.Publisher[A] {
          override def subscribe(subscriber: Flow.Subscriber[_ >: A]): Unit = {
            if (subscriber == null)
              throw new NullPointerException(
                "Subscriber must not be null (Reactive Streams rule 1.9)"
              )
            dispatcher.unsafeRunAndForget(
              fs2.interop.flow
                .subscribeStream(stream, subscriber.asInstanceOf[Flow.Subscriber[A]])
            )
          }
        }
    }

  // the fallback of a leaf that only ever runs natively (a pipe's probe input)
  private[wsclient] def none[F[_]]: Fs2Runner[F] =
    new Fs2Runner[F] {
      override def publisher[A](stream: fs2.Stream[F, A]): Flow.Publisher[A] =
        throw new IllegalStateException("This fs2 stream can only be run as an fs2 stream")
    }
}

/**
 * An fs2 stream kept native inside an [[AsyncStream]]: compiled for fs2 with the same `Async`
 * instance it is `stream` itself - no bridge; any other consumer runs it through `runner`.
 */
private[wsclient] final class Fs2Leaf[F[_], A] private[wsclient] (
  val stream: fs2.Stream[F, A],
  val async: Async[F],
  runner: Fs2Runner[F]
) extends NativeStream[A] {
  override def openPublisher(): Flow.Publisher[_ <: A] = runner.publisher(stream)
  override def backend: String = "fs2"
}

/** An fs2 pipe kept native inside an [[AsyncPipe]]. */
private[wsclient] final class Fs2Pipe[F[_], A, B] private[wsclient] (
  val pipe: fs2.Pipe[F, A, B],
  val async: Async[F],
  runner: Fs2Runner[F]
) extends NativePipe[A, B] {

  override def transform(upstream: Flow.Publisher[_ <: A]): Flow.Publisher[_ <: B] =
    runner.publisher(
      Fs2Compiler
        .publisherStream[F, A](
          upstream,
          Fs2Compiler.BridgeChunkSize,
          impl.BridgeCounter.Global
        )(
          async
        )
        .through(pipe)
    )

  override def backend: String = "fs2"
}

/**
 * Conversions between [[AsyncStream]] / [[AsyncPipe]] and fs2 (since 1.2.0): `import
 * io.cequence.wsclient.stream.Fs2Interop._`.
 *
 *   - `stream.toFs2[F]` compiles the stream natively for fs2: fs2 leaves (of the same `Async`
 *     instance) stay themselves, every operator is an fs2 operator
 *   - `fs2Stream.toAsyncStream` keeps the stream native; the implicit [[Fs2Runner]] is used
 *     only when the stream is consumed outside fs2
 *   - `pipe.toFs2Pipe[F]` / `Fs2Interop.fromFs2Pipe(pipe)` likewise for transformations
 */
object Fs2Interop {

  implicit final class AsyncStreamToFs2[A](private val stream: AsyncStream[A]) extends AnyVal {
    def toFs2[F[_]](
      implicit F: Async[F]
    ): fs2.Stream[F, A] = Fs2Compiler.toFs2(stream)
  }

  implicit final class Fs2ToAsyncStream[F[_], A](private val stream: fs2.Stream[F, A])
      extends AnyVal {
    def toAsyncStream(
      implicit F: Async[F],
      runner: Fs2Runner[F]
    ): AsyncStream[A] =
      AsyncStream.fromNative(new Fs2Leaf(stream, F, runner))
  }

  implicit final class AsyncPipeToFs2[A, B](private val pipe: AsyncPipe[A, B]) extends AnyVal {
    def toFs2Pipe[F[_]](
      implicit F: Async[F]
    ): fs2.Pipe[F, A, B] = Fs2Compiler.toFs2Pipe(pipe)
  }

  def fromFs2Pipe[F[_], A, B](
    pipe: fs2.Pipe[F, A, B]
  )(
    implicit F: Async[F],
    runner: Fs2Runner[F]
  ): AsyncPipe[A, B] =
    AsyncPipe.fromNative(new Fs2Pipe(pipe, F, runner))
}

/**
 * Opt-in implicit conversions between [[AsyncStream]] / [[AsyncPipe]] and fs2: `import
 * io.cequence.wsclient.stream.Fs2Conversions._`. Prefer the explicit `Fs2Interop` methods in
 * new code.
 */
object Fs2Conversions {

  import scala.language.implicitConversions

  implicit def asyncStreamToFs2[F[_], A](
    stream: AsyncStream[A]
  )(
    implicit F: Async[F]
  ): fs2.Stream[F, A] =
    Fs2Compiler.toFs2(stream)

  implicit def fs2ToAsyncStream[F[_], A](
    stream: fs2.Stream[F, A]
  )(
    implicit F: Async[F],
    runner: Fs2Runner[F]
  ): AsyncStream[A] =
    AsyncStream.fromNative(new Fs2Leaf(stream, F, runner))
}
