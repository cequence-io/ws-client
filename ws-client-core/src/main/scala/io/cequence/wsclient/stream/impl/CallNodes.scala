package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.concurrent.AsyncScheduler
import io.cequence.wsclient.stream.{AsyncCall, AsyncStream, NativeCall, Termination}

import java.util.concurrent.TimeoutException
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/*
 * The closed node set of the AsyncCall AST (initial encoding) - as for AsyncStream, every node
 * is a LEAF or an OPERATOR unary on its `upstream`; the calls a user function returns
 * (flatMap's, a recovery's, a hook's, traverse's elements) are lazy children a backend
 * compiles at run time. So the spine of a call is a linked list, walked iteratively by
 * `CallCompiler.compile` and by the neutral interpreter (`CallRunner`) - stack-safe at any
 * depth, and recursion through `flatMap` (a retry loop) runs in constant stack because every
 * child is compiled only when it is reached.
 *
 * Rules for compilers:
 *   - compiling is pure: no user thunk or function runs at compile time (only per run)
 *   - a node is dispatched through the `CallCompiler` visitor, so a compiler that misses a
 *     node does not compile
 *   - a compiler bridges (a hop between its effect type and a `Future`) only at a foreign
 *     leaf, and counts it in its `BridgeCounter` (`CallDiagnostics.counted`)
 */

/** A leaf: where a call's value comes from. */
private[wsclient] sealed abstract class CallLeaf[+A] extends AsyncCall[A] {
  def compileLeaf(c: CallCompiler): c.R[A]
}

/** An operator, unary on its upstream. */
private[wsclient] sealed abstract class CallOp[A, +B] extends AsyncCall[B] {
  def upstream: AsyncCall[A]
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[B]
}

// ---------------------------------------------------------------------------------- leaves

private[wsclient] final case class SucceedCall[+A](value: A) extends CallLeaf[A] {
  def compileLeaf(c: CallCompiler): c.R[A] = c.succeed(value)
}

private[wsclient] final case class FailedCall(error: Throwable) extends CallLeaf[Nothing] {
  def compileLeaf(c: CallCompiler): c.R[Nothing] = c.failed(error)
}

/** Evaluated once per run. */
private[wsclient] final case class DeferCall[+A](thunk: () => AsyncCall[A])
    extends CallLeaf[A] {
  def compileLeaf(c: CallCompiler): c.R[A] = c.defer(thunk)
}

/** `future`, `fromFuture`: `f` is called once per run, with the run's `ExecutionContext`. */
private[wsclient] final case class FromFutureCall[+A](f: ExecutionContext => Future[A])
    extends CallLeaf[A] {
  def compileLeaf(c: CallCompiler): c.R[A] = c.fromFuture(f)
}

/** A backend's native effect, plus a fallback run. */
private[wsclient] final case class NativeCallLeaf[+A](leaf: NativeCall[A])
    extends CallLeaf[A] {
  def compileLeaf(c: CallCompiler): c.R[A] = c.native(leaf)
}

/**
 * A run of `stream`, folding its elements (`AsyncStream.runFoldCall`): `zero` is evaluated
 * once per run, a throw in `zero` or `f` fails the call. A backend runs the stream with its
 * own compiler (ZIO: the ZIO stream itself); the neutral interpreter runs it on its own
 * interpreter, and cancelling the run cancels the stream.
 */
private[wsclient] final case class RunStream[A, S](
  stream: AsyncStream[A],
  zero: () => S,
  f: (S, A) => S
) extends CallLeaf[S] {
  def compileLeaf(c: CallCompiler): c.R[S] = c.runStream(stream, zero, f)
}

/**
 * `stream.preflightCall` of a stream that is not a native preflight: the neutral interpreter's
 * preflight, cancelled with the run (which aborts the request).
 */
private[wsclient] final case class RunPreflight[A](
  stream: AsyncStream[A]
) extends CallLeaf[AsyncStream[A]] {
  def compileLeaf(c: CallCompiler): c.R[AsyncStream[A]] = c.runPreflight(stream)
}

/** `scheduler` is the neutral interpreter's timer; a native backend uses its own clock. */
private[wsclient] final case class SleepCall(
  duration: FiniteDuration,
  scheduler: AsyncScheduler
) extends CallLeaf[Unit] {
  def compileLeaf(c: CallCompiler): c.R[Unit] = c.sleep(duration)
}

/**
 * At most `parallelism` element calls at once, results in input order; the first failure fails
 * the whole, and the element calls still running are cancelled.
 */
private[wsclient] final case class TraverseCall[A, B](
  items: immutable.Iterable[A],
  parallelism: Int,
  f: A => AsyncCall[B]
) extends CallLeaf[immutable.Seq[B]] {
  def compileLeaf(c: CallCompiler): c.R[immutable.Seq[B]] = c.traverse(items, parallelism, f)
}

// ------------------------------------------------------------------------------- operators

private[wsclient] final case class MapCall[A, +B](
  upstream: AsyncCall[A],
  f: A => B
) extends CallOp[A, B] {
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[B] = c.map(up, f)
}

private[wsclient] final case class FlatMapCall[A, +B](
  upstream: AsyncCall[A],
  f: A => AsyncCall[B]
) extends CallOp[A, B] {
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[B] = c.flatMap(up, f)
}

/**
 * On a failure: continues with `onFailure(e)`; a value passes through. `name` is for
 * [[AsyncCall.describe]] only (`mapError`, `recover`, `recoverWith`).
 */
private[wsclient] final case class RecoverCall[A](
  upstream: AsyncCall[A],
  onFailure: Throwable => AsyncCall[A],
  name: String
) extends CallOp[A, A] {
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[A] = c.recover(up, onFailure)
}

/** Continues with `f(outcome)` - a value or a failure, never an interruption. */
private[wsclient] final case class TransformCall[A, +B](
  upstream: AsyncCall[A],
  f: Try[A] => AsyncCall[B],
  name: String
) extends CallOp[A, B] {
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[B] = c.transform(up, f)
}

/**
 * The upstream, or a failure with `error()` (null: the default `TimeoutException`) once
 * `limit` passed - the upstream is then cancelled (interrupted). With `onLate`, an upstream
 * with a `Future` leaf in flight - which cannot be stopped - is left to finish instead, and
 * the outcome of an upstream that was not stopped goes to `onLate`. `scheduler` and
 * `onUnscheduled` are the neutral interpreter's: its timer, and what is told when the
 * scheduler refuses it (the upstream then runs unlimited); a native backend uses its own
 * clock.
 */
private[wsclient] final case class TimeoutCall[A](
  upstream: AsyncCall[A],
  limit: FiniteDuration,
  error: () => Throwable,
  onLate: Option[Try[A] => Unit],
  onUnscheduled: Throwable => Unit,
  scheduler: AsyncScheduler
) extends CallOp[A, A] {
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[A] = c.timeout(up, limit, error, onLate)
}

/**
 * `f(termination)` once per started run, after the upstream ended and before its outcome goes
 * on: a failing hook fails a completed call, and is added (suppressed) to a failure.
 */
private[wsclient] final case class GuaranteeCall[A](
  upstream: AsyncCall[A],
  f: Termination => AsyncCall[Unit],
  name: String
) extends CallOp[A, A] {
  def compileOn(c: CallCompiler)(up: c.R[A]): c.R[A] = c.guarantee(up, f)
}

/**
 * A backend's compiler for the AsyncCall AST (a visitor: one method per node, so a compiler
 * that misses a node does not compile). `R[+A]` is the backend's effect type - e.g. a ZIO
 * `Task`. The neutral interpreter (`CallRunner`) runs the nodes directly instead.
 *
 * Compiling must be PURE: no user thunk or function may run while compiling - only per run.
 * Lazy children are compiled at run time, through [[CallCompiler.compile]].
 *
 * The neutral interpreter (`CallFiber`) does not go through this visitor: it matches the two
 * sealed node sets directly, exhaustively - a new node does not compile there either.
 *
 * Version skew: a visitor method added after the 1.3.0 release gets a default implementation
 * (e.g. over `fromFuture` - one bridge), so an interop module built against an older core
 * keeps working. Every method of this version is abstract: `runStream` and `runPreflight` are
 * the nodes that run a stream, and a backend decides how (ZIO: natively, with its own fiber).
 */
private[wsclient] trait CallCompiler {

  type R[+A]

  // leaves

  def succeed[A](value: A): R[A]

  def failed[A](error: Throwable): R[A]

  def defer[A](thunk: () => AsyncCall[A]): R[A]

  def fromFuture[A](f: ExecutionContext => Future[A]): R[A]

  def native[A](leaf: NativeCall[A]): R[A]

  def sleep(duration: FiniteDuration): R[Unit]

  /**
   * The fold of `stream` (`zero` per run): a backend's own run, or the neutral interpreter's.
   */
  def runStream[A, S](
    stream: AsyncStream[A],
    zero: () => S,
    f: (S, A) => S
  ): R[S]

  /** The preflight of a stream that is not natively preflighted (`RunPreflight`). */
  def runPreflight[A](stream: AsyncStream[A]): R[AsyncStream[A]]

  def traverse[A, B](
    items: immutable.Iterable[A],
    parallelism: Int,
    f: A => AsyncCall[B]
  ): R[immutable.Seq[B]]

  // operators

  def map[A, B](
    up: R[A],
    f: A => B
  ): R[B]

  def flatMap[A, B](
    up: R[A],
    f: A => AsyncCall[B]
  ): R[B]

  def recover[A](
    up: R[A],
    onFailure: Throwable => AsyncCall[A]
  ): R[A]

  def transform[A, B](
    up: R[A],
    f: Try[A] => AsyncCall[B]
  ): R[B]

  def timeout[A](
    up: R[A],
    limit: FiniteDuration,
    error: () => Throwable,
    onLate: Option[Try[A] => Unit]
  ): R[A]

  def guarantee[A](
    up: R[A],
    f: Termination => AsyncCall[Unit]
  ): R[A]
}

private[wsclient] object CallCompiler {

  /**
   * What a timed-out call fails with, on every interpreter: `error()`; the default
   * `TimeoutException` if that is null; what `error()` throws, if it throws.
   */
  def timeoutFailure(
    limit: FiniteDuration,
    error: () => Throwable
  ): Throwable =
    try {
      val failure = error()
      if (failure == null) new TimeoutException(s"AsyncCall timed out after $limit")
      else failure
    } catch { case NonFatal(thrown) => thrown }

  /** The leaf of `call` and its operators, upstream first. */
  def spine[A](call: AsyncCall[A]): (CallLeaf[Any], List[CallOp[Any, Any]]) = {
    var ops: List[CallOp[Any, Any]] = Nil
    var current: AsyncCall[Any] = call

    while (current.isInstanceOf[CallOp[_, _]]) {
      val op = current.asInstanceOf[CallOp[Any, Any]]
      ops = op :: ops
      current = op.upstream
    }

    (current.asInstanceOf[CallLeaf[Any]], ops)
  }

  /**
   * Compiles `call` with `c`: an iterative walk down the spine to the leaf, then a fold of the
   * operators over the compiled leaf - stack-safe at any depth.
   */
  def compile[A](
    c: CallCompiler,
    call: AsyncCall[A]
  ): c.R[A] = {
    val (leaf, ops) = spine(call)
    var acc: c.R[Any] = leaf.compileLeaf(c)
    ops.foreach { op =>
      acc = op.compileOn(c)(acc)
    }
    acc.asInstanceOf[c.R[A]]
  }

  /** The plan of `call`, leaf first - never its values. */
  def describe(call: AsyncCall[_]): String = {
    val (leaf, ops) = spine(call)
    (describeLeaf(leaf) :: ops.map(describeOp)).mkString(" -> ")
  }

  private def describeLeaf(leaf: CallLeaf[Any]): String = leaf match {
    case _: SucceedCall[_]               => "succeed"
    case _: FailedCall                   => "failed"
    case _: DeferCall[_]                 => "defer"
    case _: FromFutureCall[_]            => "future"
    case NativeCallLeaf(native)          => s"native(${native.backend})"
    case _: RunStream[_, _]              => "runStream"
    case _: RunPreflight[_]              => "runPreflight"
    case SleepCall(duration, _)          => s"sleep($duration)"
    case TraverseCall(_, parallelism, _) => s"traverse(parallelism = $parallelism)"
  }

  private def describeOp(op: CallOp[Any, Any]): String = op match {
    case _: MapCall[_, _]                  => "map"
    case _: FlatMapCall[_, _]              => "flatMap"
    case RecoverCall(_, _, name)           => name
    case TransformCall(_, _, name)         => name
    case TimeoutCall(_, limit, _, _, _, _) => s"timeout($limit)"
    case GuaranteeCall(_, _, name)         => name
  }
}
