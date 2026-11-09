package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.stream.{AsyncStream, NativePipe, NativeStream, Termination}

import java.util.concurrent.Flow
import scala.collection.immutable
import scala.concurrent.Future

/*
 * The closed node set of the AsyncStream AST (initial encoding). Every node is either a LEAF
 * or an OPERATOR that is unary on its `upstream`; second branches (concat's next stream,
 * flatMapConcat's inner streams, recoverWith's recovery) are lazy children a backend compiles
 * at run time. So the spine of a stream is a linked list, and `StreamCompiler.compile` walks
 * it iteratively (stack-safe at any depth).
 *
 * Rules for compilers:
 *   - compiling is pure: no user thunk runs at compile time (only per run)
 *   - a node is dispatched through the `StreamCompiler` visitor, so a compiler that misses a
 *     node does not compile
 *   - a compiler bridges only at a foreign leaf (once) or a foreign native pipe (twice), and
 *     counts each in its `BridgeCounter` (`StreamDiagnostics.counted`)
 */

/** A leaf: the source of a stream. */
private[wsclient] sealed abstract class Leaf[+A] extends AsyncStream[A] {
  def compileLeaf(c: StreamCompiler): c.R[A]
}

/** An operator, unary on its upstream. */
private[wsclient] sealed abstract class Op[A, +B] extends AsyncStream[B] {
  def upstream: AsyncStream[A]
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B]
}

// ---------------------------------------------------------------------------------- leaves

/**
 * `empty`, `single`, `apply`, `fromIterable`, `fromIterator` (an iterable whose iterator is
 * created per run).
 */
private[wsclient] final case class Emits[+A](as: immutable.Iterable[A]) extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.emits(as)
}

private[wsclient] final case class FailedStream(error: Throwable) extends Leaf[Nothing] {
  def compileLeaf(c: StreamCompiler): c.R[Nothing] = c.failed(error)
}

/** Evaluated once per run. */
private[wsclient] final case class Defer[+A](thunk: () => AsyncStream[A]) extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.defer(thunk)
}

/** `future`, `futureStream`: the thunk is evaluated once per run. */
private[wsclient] final case class FutureStream[+A](thunk: () => Future[AsyncStream[A]])
    extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.futureStream(thunk)
}

/** One factory call per run. */
private[wsclient] final case class FromPublisher[+A](
  factory: () => Flow.Publisher[_ <: A],
  bufferSize: Int
) extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.fromPublisher[A](factory, bufferSize)
}

/**
 * `fromInputStream`, `fromBlockingIterator`: `open` and every read run on the blocking pool;
 * `close()` exactly once, after the last read returned.
 */
private[wsclient] final case class FromBlocking[+A](open: () => BlockingCursor[A])
    extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.fromBlocking(open)
}

/** A backend's native stream, plus a fallback publisher. */
private[wsclient] final case class Native[+A](leaf: NativeStream[A]) extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.native(leaf)
}

/** Internal only (hub sources, `preflight`): a run of the neutral interpreter. */
private[wsclient] final case class FromCursor[+A](
  open: RunContext => Cursor[A],
  name: String
) extends Leaf[A] {
  def compileLeaf(c: StreamCompiler): c.R[A] = c.fromCursor(open, name)
}

// ------------------------------------------------------------------------------- operators

private[wsclient] final case class Mapped[A, +B](
  upstream: AsyncStream[A],
  f: A => B
) extends Op[A, B] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B] = c.map(up, f)
}

/** `f` is evaluated eagerly when the element arrives. */
private[wsclient] final case class MapConcat[A, +B](
  upstream: AsyncStream[A],
  f: A => immutable.Iterable[B]
) extends Op[A, B] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B] = c.mapConcat(up, f)
}

/**
 * Per-run state: `init` once per run, `step` per element (eagerly, when it arrives), and
 * `onComplete` on NORMAL completion only (it may throw, which fails the stream). A step with
 * `stop = true` emits its elements, then completes the stream (the upstream is cancelled;
 * `onComplete` is not called). `name` is for [[AsyncStream.describe]] only.
 */
private[wsclient] final case class Stateful[A, S, +B](
  upstream: AsyncStream[A],
  init: () => S,
  step: (S, A) => AsyncStream.Step[S, B],
  onComplete: S => immutable.Iterable[B],
  name: String
) extends Op[A, B] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B] =
    c.stateful(up, init, step, onComplete, name)
}

/** The next stream is created (and run) only after the upstream completed. */
private[wsclient] final case class Concat[A](
  upstream: AsyncStream[A],
  next: () => AsyncStream[A]
) extends Op[A, A] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[A] = c.concat(up, next)
}

/** Sequential: one inner stream at a time. */
private[wsclient] final case class FlatMapConcat[A, +B](
  upstream: AsyncStream[A],
  f: A => AsyncStream[B]
) extends Op[A, B] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B] = c.flatMapConcat(up, f)
}

/** `mapAsync(1)`: ordered and sequential. */
private[wsclient] final case class EvalMap[A, +B](
  upstream: AsyncStream[A],
  f: A => Future[B]
) extends Op[A, B] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B] = c.evalMap(up, f)
}

private[wsclient] final case class MapError[A](
  upstream: AsyncStream[A],
  pf: PartialFunction[Throwable, Throwable]
) extends Op[A, A] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[A] = c.mapError(up, pf)
}

/** Switches to the recovery stream once (a failure of the recovery stream is final). */
private[wsclient] final case class RecoverWith[A](
  upstream: AsyncStream[A],
  pf: PartialFunction[Throwable, AsyncStream[A]]
) extends Op[A, A] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[A] = c.recoverWith(up, pf)
}

/**
 * `f` exactly once per started run. On `Completed` / `Failed` the downstream signal waits for
 * `f`'s future (a failing `f` fails a completed stream, and is added as suppressed to a
 * failure); on `Cancelled`, `f` is called after the upstream was cancelled.
 */
private[wsclient] final case class OnTerminate[A](
  upstream: AsyncStream[A],
  f: Termination => Future[Unit]
) extends Op[A, A] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[A] = c.onTerminate(up, f)
}

/**
 * `task()` starts once per run, no later than the first demand. Its failure before the stream
 * ended cancels the upstream and fails the stream; with `awaitCompletion` a normal completion
 * waits for the task (and fails if it fails).
 */
private[wsclient] final case class Concurrently[A](
  upstream: AsyncStream[A],
  task: () => Future[Any],
  awaitCompletion: Boolean
) extends Op[A, A] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[A] =
    c.concurrently(up, task, awaitCompletion)
}

private[wsclient] final case class ViaNative[A, +B](
  upstream: AsyncStream[A],
  pipe: NativePipe[A, B]
) extends Op[A, B] {
  def compileOn(c: StreamCompiler)(up: c.R[A]): c.R[B] = c.viaNative(up, pipe)
}

private[wsclient] object Nodes {

  /**
   * The probe leaf: a pipe applied to it shows whether it is exactly one native pipe (see the
   * interop modules' `toFlow` peephole). Never run - its run fails.
   */
  val Hole: AsyncStream[Nothing] =
    FromCursor[Nothing](
      _ => new FailedCursor(new IllegalStateException("The probe stream cannot be run")),
      "hole"
    )

  val Empty: AsyncStream[Nothing] = Emits(Nil)
}
