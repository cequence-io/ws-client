package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl.ViaNative

import scala.collection.immutable
import scala.concurrent.Future

/**
 * A reusable stream transformation `AsyncStream[A] => AsyncStream[B]`. Applying a pipe just
 * extends the stream's plan, so it runs natively wherever the stream runs; a native pipe (e.g.
 * an Akka `Flow`, see [[AsyncPipe.fromNative]]) is applied natively by its own backend.
 *
 * Per-run state belongs inside the pipe's function, in `stateful` / `statefulMapConcat` /
 * `AsyncStream.defer` - never in a value captured when the pipe is built (it would be shared
 * by every run).
 *
 * The builder methods mirror the stream operators:
 * `AsyncPipe.of[ChatChunk].map(...).filter(...)`.
 */
final class AsyncPipe[-A, +B] private (private val f: AsyncStream[A] => AsyncStream[B]) {

  def apply(in: AsyncStream[A]): AsyncStream[B] = f(in)

  def andThen[C](next: AsyncPipe[B, C]): AsyncPipe[A, C] = new AsyncPipe(in => next(f(in)))

  private def extend[C](g: AsyncStream[B] => AsyncStream[C]): AsyncPipe[A, C] =
    new AsyncPipe(in => g(f(in)))

  // the builder mirror of the stream operators (see AsyncStream for their semantics)

  def map[C](g: B => C): AsyncPipe[A, C] = extend(_.map(g))

  def mapConcat[C](g: B => immutable.Iterable[C]): AsyncPipe[A, C] = extend(_.mapConcat(g))

  def filter(p: B => Boolean): AsyncPipe[A, B] = extend(_.filter(p))

  def filterNot(p: B => Boolean): AsyncPipe[A, B] = extend(_.filterNot(p))

  def collect[C](pf: PartialFunction[B, C]): AsyncPipe[A, C] = extend(_.collect(pf))

  def evalMap[C](g: B => Future[C]): AsyncPipe[A, C] = extend(_.evalMap(g))

  def statefulMapConcat[C](create: () => B => immutable.Iterable[C]): AsyncPipe[A, C] =
    extend(_.statefulMapConcat(create))

  def stateful[S, C](
    init: () => S
  )(
    step: (S, B) => AsyncStream.Step[S, C],
    onComplete: S => immutable.Iterable[C] = (_: S) => Nil
  ): AsyncPipe[A, C] =
    extend(_.stateful(init)(step, onComplete))

  def scan[S](zero: S)(g: (S, B) => S): AsyncPipe[A, S] = extend(_.scan(zero)(g))

  def fold[S](zero: S)(g: (S, B) => S): AsyncPipe[A, S] = extend(_.fold(zero)(g))

  def zipWithIndex: AsyncPipe[A, (B, Long)] = extend(_.zipWithIndex)

  def take(n: Long): AsyncPipe[A, B] = extend(_.take(n))

  def takeWhile(
    p: B => Boolean,
    inclusive: Boolean = false
  ): AsyncPipe[A, B] =
    extend(_.takeWhile(p, inclusive))

  def drop(n: Long): AsyncPipe[A, B] = extend(_.drop(n))

  def concat[C >: B](that: => AsyncStream[C]): AsyncPipe[A, C] = extend(_.concat(that))

  def flatMapConcat[C](g: B => AsyncStream[C]): AsyncPipe[A, C] = extend(_.flatMapConcat(g))

  def via[C](next: AsyncPipe[B, C]): AsyncPipe[A, C] = andThen(next)

  def zipVia[X, Y, C](
    project: B => X,
    pipe: AsyncPipe[X, Y]
  )(
    combine: (B, Y) => C
  ): AsyncPipe[A, C] =
    extend(_.zipVia(project, pipe)(combine))

  def mapError(pf: PartialFunction[Throwable, Throwable]): AsyncPipe[A, B] =
    extend(_.mapError(pf))

  def recover[C >: B](pf: PartialFunction[Throwable, C]): AsyncPipe[A, C] =
    extend(_.recover(pf))

  def recoverWith[C >: B](pf: PartialFunction[Throwable, AsyncStream[C]]): AsyncPipe[A, C] =
    extend(_.recoverWith(pf))

  def onTerminate(g: Termination => Unit): AsyncPipe[A, B] = extend(_.onTerminate(g))

  def guaranteeCase(g: Termination => Future[Unit]): AsyncPipe[A, B] =
    extend(_.guaranteeCase(g))

  def concurrently(
    task: () => Future[Any],
    awaitCompletion: Boolean = false
  ): AsyncPipe[A, B] =
    extend(_.concurrently(task, awaitCompletion))

  override def toString: String = "AsyncPipe"
}

object AsyncPipe {

  private val Identity: AsyncPipe[Any, Any] = new AsyncPipe[Any, Any](in => in)

  def identity[A]: AsyncPipe[A, A] = Identity.asInstanceOf[AsyncPipe[A, A]]

  /** The start of a builder: `AsyncPipe.of[A].map(...)`. */
  def of[A]: AsyncPipe[A, A] = identity[A]

  def fromFunction[A, B](f: AsyncStream[A] => AsyncStream[B]): AsyncPipe[A, B] = new AsyncPipe(
    f
  )

  /** A transformation in its producer's native representation - see [[NativePipe]]. */
  def fromNative[A, B](pipe: NativePipe[A, B]): AsyncPipe[A, B] =
    new AsyncPipe[A, B](in => ViaNative(in, pipe))
}
