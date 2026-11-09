package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.stream.{AsyncStream, NativePipe, NativeStream, Termination}

import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicLong
import scala.collection.immutable
import scala.concurrent.Future

/**
 * A backend's compiler for the AsyncStream AST (a visitor: one method per node, so a compiler
 * that misses a node does not compile). `R[+A]` is the backend's stream type - the neutral
 * interpreter's [[Opener]], an Akka `Source`, an fs2 or ZIO stream.
 *
 * Compiling must be PURE: no user thunk may run while compiling - only per run. Lazy children
 * (a concat's next stream, flatMapConcat's inner streams, recoverWith's recovery) are compiled
 * at run time, through [[StreamCompiler.compile]].
 *
 * Version skew: a visitor method added after 1.2.0 gets a default implementation - a new
 * OPERATOR as a neutral [[NativePipe]] through `viaNative` (two bridges), a new LEAF as a
 * neutral [[NativeStream]] through `native` (or a neutral run through `fromCursor`; one
 * bridge)
 *   - so an interop module built against an older core keeps working with new nodes. That
 *     covers new visitor methods only: the interop modules use other `private[wsclient]` core
 *     internals too, so every ws-client artifact on a classpath must have the same version.
 */
private[wsclient] trait StreamCompiler {

  type R[+A]

  // leaves

  def emits[A](as: immutable.Iterable[A]): R[A]

  def failed[A](error: Throwable): R[A]

  def defer[A](thunk: () => AsyncStream[A]): R[A]

  def futureStream[A](thunk: () => Future[AsyncStream[A]]): R[A]

  def fromPublisher[A](
    factory: () => Flow.Publisher[_ <: A],
    bufferSize: Int
  ): R[A]

  def fromBlocking[A](open: () => BlockingCursor[A]): R[A]

  def native[A](leaf: NativeStream[A]): R[A]

  def fromCursor[A](
    open: RunContext => Cursor[A],
    name: String
  ): R[A]

  // operators

  def map[A, B](
    up: R[A],
    f: A => B
  ): R[B]

  def mapConcat[A, B](
    up: R[A],
    f: A => immutable.Iterable[B]
  ): R[B]

  def stateful[A, S, B](
    up: R[A],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B],
    name: String
  ): R[B]

  def concat[A](
    up: R[A],
    next: () => AsyncStream[A]
  ): R[A]

  def flatMapConcat[A, B](
    up: R[A],
    f: A => AsyncStream[B]
  ): R[B]

  def evalMap[A, B](
    up: R[A],
    f: A => Future[B]
  ): R[B]

  def mapError[A](
    up: R[A],
    pf: PartialFunction[Throwable, Throwable]
  ): R[A]

  def recoverWith[A](
    up: R[A],
    pf: PartialFunction[Throwable, AsyncStream[A]]
  ): R[A]

  def onTerminate[A](
    up: R[A],
    f: Termination => Future[Unit]
  ): R[A]

  def concurrently[A](
    up: R[A],
    task: () => Future[Any],
    awaitCompletion: Boolean
  ): R[A]

  def viaNative[A, B](
    up: R[A],
    pipe: NativePipe[A, B]
  ): R[B]
}

private[wsclient] object StreamCompiler {

  /** The leaf of `s` and its operators, upstream first. */
  def spine[A](s: AsyncStream[A]): (Leaf[Any], List[Op[Any, Any]]) = {
    var ops: List[Op[Any, Any]] = Nil
    var current: AsyncStream[Any] = s

    while (current.isInstanceOf[Op[_, _]]) {
      val op = current.asInstanceOf[Op[Any, Any]]
      ops = op :: ops
      current = op.upstream
    }

    (current.asInstanceOf[Leaf[Any]], ops)
  }

  /**
   * Compiles `s` with `c`: an iterative walk down the spine to the leaf, then a fold of the
   * operators over the compiled leaf - stack-safe at any depth.
   */
  def compile[A](
    c: StreamCompiler,
    s: AsyncStream[A]
  ): c.R[A] = {
    val (leaf, ops) = spine(s)
    var acc: c.R[Any] = leaf.compileLeaf(c)
    ops.foreach { op =>
      acc = op.compileOn(c)(acc)
    }
    acc.asInstanceOf[c.R[A]]
  }

  /** The plan of `s`, leaf first - never its elements. */
  def describe(s: AsyncStream[_]): String = {
    val (leaf, ops) = spine(s)
    (describeLeaf(leaf) :: ops.map(describeOp)).mkString(" -> ")
  }

  private def describeLeaf(leaf: Leaf[Any]): String = leaf match {
    case _: Emits[_]         => "emits"
    case _: FailedStream     => "failed"
    case _: Defer[_]         => "defer"
    case _: FutureStream[_]  => "futureStream"
    case _: FromPublisher[_] => "fromPublisher"
    case _: FromBlocking[_]  => "fromBlocking"
    case Native(native)      => s"native(${native.backend})"
    case FromCursor(_, name) => name
  }

  private def describeOp(op: Op[Any, Any]): String = op match {
    case _: Mapped[_, _]                     => "map"
    case _: MapConcat[_, _]                  => "mapConcat"
    case Stateful(_, _, _, _, name)          => name
    case _: Concat[_]                        => "concat"
    case _: FlatMapConcat[_, _]              => "flatMapConcat"
    case _: EvalMap[_, _]                    => "evalMap"
    case _: MapError[_]                      => "mapError"
    case _: RecoverWith[_]                   => "recoverWith"
    case _: OnTerminate[_]                   => "onTerminate"
    case Concurrently(_, _, awaitCompletion) => s"concurrently(await = $awaitCompletion)"
    case ViaNative(_, pipe)                  => s"viaNative(${pipe.backend})"
  }
}

/**
 * Counts the bridges of one compilation or run - and of its lazy children, which a compiler
 * compiles at run time with the same counter (an Akka compiler instance captures it; a neutral
 * run carries it in its [[RunContext]]). Only scoped counters count
 * (`StreamDiagnostics.counted`); `Global` - outside any scope - counts nothing.
 */
private[wsclient] final class BridgeCounter private (scoped: Boolean) {

  private val count = new AtomicLong()

  def add(n: Long): Unit =
    if (scoped) {
      count.addAndGet(n)
      ()
    }

  def get: Long = count.get()
}

private[wsclient] object BridgeCounter {

  /** Outside any `counted` scope: counts nothing. */
  val Global: BridgeCounter = new BridgeCounter(scoped = false)

  def scoped(): BridgeCounter = new BridgeCounter(scoped = true)

  private val currentCounter = new ThreadLocal[BridgeCounter]

  /**
   * The counter of the scope this thread is in (see `StreamDiagnostics.counted`) - read when a
   * compilation or a run STARTS; [[Global]] outside a scope.
   */
  def current(): BridgeCounter = {
    val counter = currentCounter.get()
    if (counter == null) Global else counter
  }

  def withCounter[T](counter: BridgeCounter)(body: => T): T = {
    val previous = currentCounter.get()
    currentCounter.set(counter)
    try body
    finally currentCounter.set(previous)
  }
}
