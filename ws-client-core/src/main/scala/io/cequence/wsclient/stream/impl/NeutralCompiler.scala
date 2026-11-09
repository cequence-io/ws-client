package io.cequence.wsclient.stream.impl

import com.typesafe.scalalogging.Logger
import io.cequence.wsclient.stream.{AsyncStream, NativePipe, NativeStream, Termination}
import org.slf4j.LoggerFactory

import java.util.ArrayDeque
import java.util.concurrent.Flow
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.immutable
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The neutral interpreter: compiles a stream into an [[Opener]] whose runs are pull-based
 * [[Cursor]]s. Operators are sequential code; back-pressure is the pull itself, and
 * cancellation is one call travelling upstream.
 *
 * Performance: an already-completed upstream future is consumed synchronously (no executor
 * hop, no recursion); consecutive `map` / `mapConcat` / `stateful` nodes fuse into one cursor;
 * consecutive concats into one multi-concat cursor, which also flattens a next stream that is
 * itself a concat (so `def loop = single(1) ++ loop` runs in constant stack and memory).
 */
private[wsclient] object NeutralCompiler extends StreamCompiler {

  type R[+A] = Opener[A]

  // ------------------------------------------------------------------------------ leaves

  def emits[A](as: immutable.Iterable[A]): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] =
        try new IteratorCursor(as.iterator)
        catch { case NonFatal(e) => new FailedCursor(e) }
    }

  def failed[A](error: Throwable): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = new FailedCursor(error)
    }

  def defer[A](thunk: () => AsyncStream[A]): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] =
        try {
          // a chain of defers resolves in a loop, not by recursion
          var stream = thunk()
          while (stream.isInstanceOf[Defer[_]])
            stream = stream.asInstanceOf[Defer[A]].thunk()
          Cursors.open(stream, ctx)
        } catch { case NonFatal(e) => new FailedCursor(e) }
    }

  def futureStream[A](thunk: () => Future[AsyncStream[A]]): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = new FutureStreamCursor(thunk, ctx)
    }

  def fromPublisher[A](
    factory: () => Flow.Publisher[_ <: A],
    bufferSize: Int
  ): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = SubscriberCursor[A](factory, bufferSize, ctx)
    }

  def fromBlocking[A](open: () => BlockingCursor[A]): Opener[A] = {
    val openBlocking = open
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = new BlockingReadCursor(openBlocking, ctx)
    }
  }

  // a foreign native leaf: its fallback publisher (one bridge)
  def native[A](leaf: NativeStream[A]): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = {
        ctx.bridges.add(1) // per run
        SubscriberCursor[A](() => leaf.openPublisher(), Cursors.BridgeBufferSize, ctx)
      }
    }

  def fromCursor[A](
    open: RunContext => Cursor[A],
    name: String
  ): Opener[A] = {
    val openCursor = open
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] =
        try openCursor(ctx)
        catch { case NonFatal(e) => new FailedCursor(e) }
    }
  }

  // --------------------------------------------------------------------------- operators

  def map[A, B](
    up: Opener[A],
    f: A => B
  ): Opener[B] =
    FusedOpener.append(up, MapStage(f.asInstanceOf[Any => Any]))

  def mapConcat[A, B](
    up: Opener[A],
    f: A => immutable.Iterable[B]
  ): Opener[B] =
    FusedOpener.append(up, MapConcatStage(f.asInstanceOf[Any => immutable.Iterable[Any]]))

  def stateful[A, S, B](
    up: Opener[A],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B],
    name: String
  ): Opener[B] =
    FusedOpener.append(
      up,
      StatefulStage(
        init,
        step.asInstanceOf[(Any, Any) => AsyncStream.Step[Any, Any]],
        onComplete.asInstanceOf[Any => immutable.Iterable[Any]]
      )
    )

  def concat[A](
    up: Opener[A],
    next: () => AsyncStream[A]
  ): Opener[A] =
    up match {
      case concatOpener: ConcatOpener[A @unchecked] => concatOpener.append(next)
      case other                                    => new ConcatOpener(other, Vector(next))
    }

  def flatMapConcat[A, B](
    up: Opener[A],
    f: A => AsyncStream[B]
  ): Opener[B] =
    new Opener[B] {
      def open(ctx: RunContext): Cursor[B] = new FlatMapConcatCursor(up.open(ctx), f, ctx)
    }

  def evalMap[A, B](
    up: Opener[A],
    f: A => Future[B]
  ): Opener[B] =
    new Opener[B] {
      def open(ctx: RunContext): Cursor[B] = new EvalMapCursor(up.open(ctx), f, ctx)
    }

  def mapError[A](
    up: Opener[A],
    pf: PartialFunction[Throwable, Throwable]
  ): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = new MapErrorCursor(up.open(ctx), pf)
    }

  def recoverWith[A](
    up: Opener[A],
    pf: PartialFunction[Throwable, AsyncStream[A]]
  ): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = new RecoverWithCursor(up.open(ctx), pf, ctx)
    }

  def onTerminate[A](
    up: Opener[A],
    f: Termination => Future[Unit]
  ): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = new OnTerminateCursor(up.open(ctx), f, ctx)
    }

  def concurrently[A](
    up: Opener[A],
    task: () => Future[Any],
    awaitCompletion: Boolean
  ): Opener[A] =
    new Opener[A] {
      def open(ctx: RunContext): Cursor[A] = {
        // the task starts with the run, before the upstream is opened
        val cursor = new ConcurrentlyCursor[A](task, awaitCompletion, ctx)
        cursor.start(up.open(ctx))
        cursor
      }
    }

  // a foreign native pipe: the upstream as a publisher into the pipe, its output back (two bridges)
  def viaNative[A, B](
    up: Opener[A],
    pipe: NativePipe[A, B]
  ): Opener[B] =
    new Opener[B] {
      def open(ctx: RunContext): Cursor[B] = {
        ctx.bridges.add(2) // per run
        SubscriberCursor[B](
          () => pipe.transform(new CursorPublisher[A](up, ctx)),
          Cursors.BridgeBufferSize,
          ctx
        )
      }
    }

}

private[wsclient] object Cursors {

  val NoneFuture: Future[Option[Nothing]] = Future.successful(None)

  // demand of a bridge into the neutral interpreter (a foreign leaf, a foreign pipe's output)
  val BridgeBufferSize = 16

  /** Compiles and opens `stream` on the neutral interpreter; never throws. */
  def open[A](
    stream: AsyncStream[A],
    ctx: RunContext
  ): Cursor[A] =
    try StreamCompiler.compile(NeutralCompiler, stream).open(ctx)
    catch { case NonFatal(e) => new FailedCursor(e) }

  def nonNull[T](
    value: T,
    what: String
  ): T =
    if (value == null)
      throw new NullPointerException(s"$what returned null")
    else
      value
}

// ---------------------------------------------------------------------------------- leaves

private[wsclient] final class FailedCursor[A](error: Throwable) extends Cursor[A] {
  def next(): Future[Option[A]] = Future.failed(error)
  def cancel(): Unit = ()
}

private[wsclient] final class IteratorCursor[A](iterator: Iterator[A]) extends Cursor[A] {
  @volatile private var cancelled = false

  def next(): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else
      try {
        if (iterator.hasNext)
          Future.successful(Some(iterator.next()))
        else
          Cursors.NoneFuture
      } catch { case NonFatal(e) => Future.failed(e) }

  def cancel(): Unit = cancelled = true
}

/** `futureStream`: the future is created at open; its stream is opened when it completes. */
private[wsclient] final class FutureStreamCursor[A](
  thunk: () => Future[AsyncStream[A]],
  ctx: RunContext
) extends Cursor[A] {

  @volatile private var cancelled = false
  @volatile private var inner: Cursor[A] = _
  @volatile private var pending: Promise[Option[A]] = _

  private val source: Future[AsyncStream[A]] =
    try Cursors.nonNull(thunk(), "The futureStream thunk")
    catch { case NonFatal(e) => Future.failed(e) }

  def next(): Future[Option[A]] = {
    val current = inner
    if (current != null)
      current.next()
    else if (cancelled)
      Cursors.NoneFuture
    else
      source.value match {
        case Some(Success(stream)) => openInner(stream).next()
        case Some(Failure(e))      => Future.failed(e)
        case None =>
          val promise = Promise[Option[A]]()
          pending = promise
          if (cancelled) promise.trySuccess(None)
          source.onComplete {
            case Success(stream) =>
              if (!promise.isCompleted) {
                val cursor = openInner(stream)
                if (cancelled)
                  promise.trySuccess(None)
                else
                  promise.completeWith(cursor.next())
              }
            case Failure(e) =>
              promise.tryFailure(e)
          }(ctx.ec)
          promise.future
      }
  }

  private def openInner(stream: AsyncStream[A]): Cursor[A] = {
    val cursor = Cursors.open(stream, ctx)
    inner = cursor
    if (cancelled) cursor.cancel()
    cursor
  }

  def cancel(): Unit = {
    cancelled = true
    val current = inner
    if (current != null) current.cancel()
    val promise = pending
    if (promise != null) promise.trySuccess(None)
  }
}

/**
 * A blocking source: `open` and every read run on the blocking pool, and their results are
 * handed to the run's `ec`. `close()` runs exactly once - after the last read, on failure, or
 * on cancel (after a read in progress returned) - and never concurrently with a read.
 */
private[wsclient] final class BlockingReadCursor[A](
  openBlocking: () => BlockingCursor[A],
  ctx: RunContext
) extends Cursor[A] {

  import BlockingReadCursor._

  private val state = new AtomicInteger(Idle)
  @volatile private var cancelled = false
  @volatile private var pending: Promise[Option[A]] = _
  // accessed only by the (serialized) reads and the single close
  private var underlying: BlockingCursor[A] = _

  def next(): Future[Option[A]] =
    if (cancelled || !state.compareAndSet(Idle, Reading))
      Cursors.NoneFuture
    else {
      val promise = Promise[Option[A]]()
      pending = promise
      if (cancelled) {
        // a cancel between the CAS and `pending` could neither complete this next() nor close
        // (no read was in progress): close here and end
        state.set(Closed)
        closeOnBlockingPool()
        promise.trySuccess(None)
      } else
        try
          ctx.blockingEc.execute(new Runnable {
            def run(): Unit = read(promise)
          })
        catch {
          case NonFatal(e) =>
            state.set(Closed)
            promise.tryFailure(e)
        }
      promise.future
    }

  private def read(promise: Promise[Option[A]]): Unit = {
    val result =
      try {
        if (underlying == null)
          underlying = Cursors.nonNull(openBlocking(), "The blocking open")
        Success(underlying.next())
      } catch { case NonFatal(e) => Failure(e) }

    result match {
      case Success(Some(_)) =>
        state.set(Idle)
        // a cancel that came while reading could not close: close now (once - CAS)
        if (cancelled && state.compareAndSet(Idle, Closed)) closeQuietly()
      case _ =>
        // end of input or a failure: close now
        state.set(Closed)
        closeQuietly()
    }

    deliver(promise, result)
  }

  private def deliver(
    promise: Promise[Option[A]],
    result: Try[Option[A]]
  ): Unit =
    try
      ctx.ec.execute(new Runnable {
        def run(): Unit = promise.tryComplete(result)
      })
    catch { case NonFatal(_) => promise.tryComplete(result) }

  private def closeQuietly(): Unit = {
    val cursor = underlying
    underlying = null
    if (cursor != null)
      try cursor.close()
      catch {
        case NonFatal(e) => logger.warn("Closing a blocking stream source failed", e)
      }
  }

  def cancel(): Unit = {
    cancelled = true
    val promise = pending
    if (promise != null) promise.trySuccess(None)
    // not reading: close here (on the blocking pool - close may block); a read in progress
    // closes after it returned
    if (state.compareAndSet(Idle, Closed)) closeOnBlockingPool()
  }

  private def closeOnBlockingPool(): Unit =
    try
      ctx.blockingEc.execute(new Runnable {
        def run(): Unit = closeQuietly()
      })
    catch { case NonFatal(_) => closeQuietly() }
}

private[wsclient] object BlockingReadCursor {
  private val Idle = 0
  private val Reading = 1
  private val Closed = 2

  private val logger = Logger(LoggerFactory.getLogger(classOf[BlockingReadCursor[_]]))
}

// -------------------------------------------------------------------- fused element stages

private[wsclient] sealed trait FusedStage
private[wsclient] final case class MapStage(f: Any => Any) extends FusedStage
private[wsclient] final case class MapConcatStage(f: Any => immutable.Iterable[Any])
    extends FusedStage
private[wsclient] final case class StatefulStage(
  init: () => Any,
  step: (Any, Any) => AsyncStream.Step[Any, Any],
  onComplete: Any => immutable.Iterable[Any]
) extends FusedStage

/** Consecutive `map` / `mapConcat` / `stateful` nodes over one upstream, run by one cursor. */
private[wsclient] final class FusedOpener(
  upstream: Opener[Any],
  stages: Vector[FusedStage]
) extends Opener[Any] {

  def open(ctx: RunContext): Cursor[Any] = {
    // per-run state first: an init that throws fails the run without opening the upstream
    val states = new Array[Any](stages.length)
    try {
      var i = 0
      while (i < stages.length) {
        stages(i) match {
          case StatefulStage(init, _, _) => states(i) = init()
          case _                         =>
        }
        i += 1
      }
      new FusedCursor(upstream.open(ctx), stages.toArray, states)
    } catch { case NonFatal(e) => new FailedCursor(e) }
  }

  def append(stage: FusedStage): FusedOpener = new FusedOpener(upstream, stages :+ stage)
}

private[wsclient] object FusedOpener {
  def append[B](
    up: Opener[Any],
    stage: FusedStage
  ): Opener[B] =
    (up match {
      case fused: FusedOpener => fused.append(stage)
      case other              => new FusedOpener(other, Vector(stage))
    }).asInstanceOf[Opener[B]]
}

/**
 * Runs a chain of element stages over one upstream. Each stage keeps the iterator of its
 * outputs not yet passed on; an output is fed to the next stage only when that stage has
 * passed on everything before it - so each stage's function runs eagerly when an element
 * reaches it, in order, and nothing is buffered beyond one element's outputs per stage.
 *
 * A stage that stops (`Step.stop`) is the new bottom: the stages below it and the upstream are
 * dropped (the upstream is cancelled, their end hooks never run); its outputs still flow, then
 * the stages above it complete in order. A stage completes when the stage below has completed
 * and passed on everything: a `stateful` stage then emits its end hook's elements.
 */
private[wsclient] final class FusedCursor(
  upstream: Cursor[Any],
  stages: Array[FusedStage],
  states: Array[Any]
) extends Cursor[Any] {

  import FusedCursor._

  private val last = stages.length - 1
  private val outputs = new Array[Iterator[Any]](stages.length)
  private val finished = new Array[Boolean](stages.length) // emits nothing beyond `outputs`
  private var bottom = 0 // stages below are dropped (a stage above stopped)
  private var upstreamDone = false
  private var terminated = false
  @volatile private var cancelled = false

  def next(): Future[Option[Any]] =
    if (cancelled || terminated)
      Cursors.NoneFuture
    else
      run(None)

  def cancel(): Unit = {
    cancelled = true
    upstream.cancel()
  }

  // `input`: an upstream result that arrived asynchronously, to be handled first
  private def run(input: Option[Try[Option[Any]]]): Future[Option[Any]] =
    try {
      var result: Future[Option[Any]] = null
      var arrived = input

      while (result == null) {
        if (arrived.isDefined) {
          val upstreamResult = arrived.get
          arrived = None
          upstreamResult match {
            case Success(Some(element)) =>
              if (cancelled)
                result = Cursors.NoneFuture
              else
                feed(0, element)
            case Success(None) =>
              if (cancelled)
                result = Cursors.NoneFuture
              else
                upstreamDone = true
            case Failure(e) =>
              terminated = true
              result =
                if (cancelled)
                  Cursors.NoneFuture
                else
                  Future.failed(e)
          }
        } else {
          val pulled = pull()
          if (pulled.asInstanceOf[AnyRef] eq NeedInput) {
            val future = upstream.next()
            future.value match {
              case Some(upstreamResult) => arrived = Some(upstreamResult)
              case None =>
                result = future.transformWith(r => run(Some(r)))(Trampoline)
            }
          } else if (pulled.asInstanceOf[AnyRef] eq Done) {
            terminated = true
            result = Cursors.NoneFuture
          } else
            result = Future.successful(Some(pulled))
        }
      }
      result
    } catch {
      case NonFatal(e) =>
        terminated = true
        if (!upstreamDone) {
          upstreamDone = true
          upstream.cancel()
        }
        Future.failed(e)
    }

  // the next output of the last stage, or NeedInput / Done. Invariant: every stage above `i`
  // has passed on all its outputs - so a scan moves up one stage per feed (linear in the stages)
  private def pull(): Any = {
    var i = last
    while (true) {
      if (i < bottom) {
        // nothing left in the stages: the bottom stage needs input or has finished (and so,
        // having propagated, have all stages above it)
        if (bottom == 0 && !finished(0)) {
          if (upstreamDone) {
            complete(0)
            i = 0
          } else
            return NeedInput
        } else
          return Done
      } else {
        val pendingOutputs = outputs(i)
        if (pendingOutputs != null && pendingOutputs.hasNext) {
          val element = pendingOutputs.next()
          if (i == last)
            return element
          // only stage i + 1 has outputs now (the stages above it passed on everything)
          feed(i + 1, element)
          i += 1
        } else {
          outputs(i) = null
          if (finished(i) && i < last && !finished(i + 1)) {
            complete(i + 1)
            i += 1
          } else
            i -= 1
        }
      }
    }
    Done // unreachable
  }

  private def feed(
    i: Int,
    element: Any
  ): Unit =
    stages(i) match {
      case MapStage(f) =>
        outputs(i) = Iterator.single(f(element))
      case MapConcatStage(f) =>
        outputs(i) = Cursors.nonNull(f(element), "The mapConcat function").iterator
      case StatefulStage(_, step, _) =>
        val result = Cursors.nonNull(step(states(i), element), "The stateful step")
        states(i) = result.state
        outputs(i) = result.emit.iterator
        if (result.stop) stop(i)
    }

  private def stop(i: Int): Unit = {
    finished(i) = true
    var below = bottom
    while (below < i) {
      outputs(below) = null
      finished(below) = true
      below += 1
    }
    bottom = i
    if (!upstreamDone) {
      upstreamDone = true
      upstream.cancel()
    }
  }

  private def complete(i: Int): Unit = {
    finished(i) = true
    stages(i) match {
      case StatefulStage(_, _, onComplete) =>
        outputs(i) = Cursors.nonNull(onComplete(states(i)), "The stateful end hook").iterator
      case _ =>
    }
  }
}

private[wsclient] object FusedCursor {
  private val NeedInput = new AnyRef
  private val Done = new AnyRef
}

// ------------------------------------------------------------------------------ concat

/** Consecutive concats as one opener: the first stream, then the next ones in order. */
private[wsclient] final class ConcatOpener[A](
  first: Opener[A],
  nexts: Vector[() => AsyncStream[A]]
) extends Opener[A] {

  def append(next: () => AsyncStream[A]): ConcatOpener[A] =
    new ConcatOpener(first, nexts :+ next)

  def open(ctx: RunContext): Cursor[A] = new ConcatCursor(first.open(ctx), nexts, ctx)
}

private[wsclient] final class ConcatCursor[A](
  initial: Cursor[A],
  nexts: Vector[() => AsyncStream[A]],
  ctx: RunContext
) extends Cursor[A] {

  private val queue = new ArrayDeque[() => AsyncStream[A]]()
  nexts.foreach(queue.addLast)

  @volatile private var current: Cursor[A] = initial
  @volatile private var cancelled = false

  def next(): Future[Option[A]] = {
    while (true) {
      if (cancelled)
        return Cursors.NoneFuture

      val future = current.next()
      future.value match {
        case Some(Success(None)) =>
          if (cancelled || !advance())
            return Cursors.NoneFuture
        case Some(_) =>
          return future
        case None =>
          return future.transformWith {
            case Success(None) =>
              if (cancelled || !advance())
                Cursors.NoneFuture
              else
                next()
            case _ =>
              future
          }(Trampoline)
      }
    }
    Cursors.NoneFuture // unreachable
  }

  // opens the next stream (false: none left). Defers are resolved, and a next stream that is
  // itself a concat is flattened into the queue - constant stack and memory for recursive concats
  private def advance(): Boolean = {
    val thunk = queue.pollFirst()
    if (thunk == null)
      false
    else {
      val cursor =
        try {
          var stream = thunk()
          var resolving = true
          while (resolving)
            stream match {
              case defer: Defer[_] =>
                stream = defer.asInstanceOf[Defer[A]].thunk()
              case concat: Concat[_] =>
                val typed = concat.asInstanceOf[Concat[A]]
                queue.addFirst(typed.next)
                stream = typed.upstream
              case _ =>
                resolving = false
            }
          Cursors.open(stream, ctx)
        } catch { case NonFatal(e) => new FailedCursor[A](e) }

      current = cursor
      if (cancelled) cursor.cancel()
      true
    }
  }

  def cancel(): Unit = {
    cancelled = true
    current.cancel()
  }
}

// ----------------------------------------------------------------------- other operators

private[wsclient] final class FlatMapConcatCursor[A, B](
  upstream: Cursor[A],
  f: A => AsyncStream[B],
  ctx: RunContext
) extends Cursor[B] {

  @volatile private var inner: Cursor[B] = _
  @volatile private var cancelled = false

  def next(): Future[Option[B]] = {
    while (true) {
      if (cancelled)
        return Cursors.NoneFuture

      val currentInner = inner
      if (currentInner != null) {
        val future = currentInner.next()
        future.value match {
          case Some(result) =>
            val handled = onInner(result, future)
            if (handled != null) return handled
          case None =>
            return future.transformWith { result =>
              val handled = onInner(result, future)
              if (handled != null) handled else next()
            }(Trampoline)
        }
      } else {
        val future = upstream.next()
        future.value match {
          case Some(result) =>
            val handled = onOuter(result)
            if (handled != null) return handled
          case None =>
            return future.transformWith { result =>
              val handled = onOuter(result)
              if (handled != null) handled else next()
            }(Trampoline)
        }
      }
    }
    Cursors.NoneFuture // unreachable
  }

  // null: continue with the next outer element
  private def onInner(
    result: Try[Option[B]],
    future: Future[Option[B]]
  ): Future[Option[B]] =
    result match {
      case Success(Some(_)) => future
      case Success(None) =>
        inner = null
        if (cancelled) Cursors.NoneFuture else null
      case Failure(_) =>
        if (cancelled)
          Cursors.NoneFuture
        else {
          upstream.cancel()
          future
        }
    }

  // null: continue with the opened inner stream
  private def onOuter(result: Try[Option[A]]): Future[Option[B]] =
    result match {
      case Success(Some(element)) =>
        if (cancelled)
          Cursors.NoneFuture
        else
          try {
            val cursor =
              Cursors.open(Cursors.nonNull(f(element), "The flatMapConcat function"), ctx)
            inner = cursor
            if (cancelled) cursor.cancel()
            null
          } catch {
            case NonFatal(e) =>
              upstream.cancel()
              Future.failed(e)
          }
      case Success(None) => Cursors.NoneFuture
      case Failure(e) =>
        if (cancelled) Cursors.NoneFuture else Future.failed(e)
    }

  def cancel(): Unit = {
    cancelled = true
    val currentInner = inner
    if (currentInner != null) currentInner.cancel()
    upstream.cancel()
  }
}

private[wsclient] final class EvalMapCursor[A, B](
  upstream: Cursor[A],
  f: A => Future[B],
  ctx: RunContext
) extends Cursor[B] {

  @volatile private var cancelled = false
  @volatile private var pending: Promise[Option[B]] = _

  def next(): Future[Option[B]] =
    if (cancelled)
      Cursors.NoneFuture
    else {
      val future = upstream.next()
      future.value match {
        case Some(result) => handle(result)
        case None         => future.transformWith(handle)(Trampoline)
      }
    }

  private def handle(result: Try[Option[A]]): Future[Option[B]] =
    result match {
      case Success(Some(element)) =>
        if (cancelled)
          Cursors.NoneFuture
        else {
          val mapped =
            try Cursors.nonNull(f(element), "The evalMap function")
            catch { case NonFatal(e) => Future.failed(e) }

          mapped.value match {
            case Some(Success(value)) => Future.successful(Some(value))
            case Some(Failure(e)) =>
              upstream.cancel()
              Future.failed(e)
            case None =>
              val promise = Promise[Option[B]]()
              pending = promise
              if (cancelled) promise.trySuccess(None)
              // a user future: re-entered on the run's ec
              mapped.onComplete {
                case Success(value) => promise.trySuccess(Some(value))
                case Failure(e) =>
                  if (!promise.isCompleted) upstream.cancel()
                  promise.tryFailure(e)
              }(ctx.ec)
              promise.future
          }
        }
      case Success(None) => Cursors.NoneFuture
      case Failure(e) =>
        if (cancelled) Cursors.NoneFuture else Future.failed(e)
    }

  def cancel(): Unit = {
    cancelled = true
    upstream.cancel()
    val promise = pending
    if (promise != null) promise.trySuccess(None)
  }
}

private[wsclient] final class MapErrorCursor[A](
  upstream: Cursor[A],
  pf: PartialFunction[Throwable, Throwable]
) extends Cursor[A] {

  @volatile private var cancelled = false

  def next(): Future[Option[A]] = {
    val future = upstream.next()
    future.value match {
      case Some(Failure(e)) => mapped(e)
      case Some(_)          => future
      case None =>
        future.transformWith {
          case Failure(e) => mapped(e)
          case _          => future
        }(Trampoline)
    }
  }

  private def mapped(e: Throwable): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else
      Future.failed(
        try pf.applyOrElse(e, identity[Throwable])
        catch { case NonFatal(thrown) => thrown }
      )

  def cancel(): Unit = {
    cancelled = true
    upstream.cancel()
  }
}

private[wsclient] final class RecoverWithCursor[A](
  upstream: Cursor[A],
  pf: PartialFunction[Throwable, AsyncStream[A]],
  ctx: RunContext
) extends Cursor[A] {

  @volatile private var current: Cursor[A] = upstream
  @volatile private var cancelled = false
  private var switched = false

  def next(): Future[Option[A]] = {
    val future = current.next()
    future.value match {
      case Some(Failure(e)) => recover(e, future)
      case Some(_)          => future
      case None =>
        future.transformWith {
          case Failure(e) => recover(e, future)
          case _          => future
        }(Trampoline)
    }
  }

  private def recover(
    e: Throwable,
    failed: Future[Option[A]]
  ): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else if (switched)
      failed
    else {
      val recovery =
        try {
          if (pf.isDefinedAt(e))
            Some(Cursors.open(Cursors.nonNull(pf(e), "The recoverWith function"), ctx))
          else
            None
        } catch { case NonFatal(thrown) => Some(new FailedCursor[A](thrown)) }

      recovery match {
        case Some(cursor) =>
          switched = true
          current = cursor
          if (cancelled) cursor.cancel()
          cursor.next()
        case None =>
          failed
      }
    }

  def cancel(): Unit = {
    cancelled = true
    current.cancel()
  }
}

private[wsclient] final class OnTerminateCursor[A](
  upstream: Cursor[A],
  hook: Termination => Future[Unit],
  ctx: RunContext
) extends Cursor[A] {

  private val fired = new AtomicBoolean(false)
  @volatile private var cancelled = false
  @volatile private var pending: Promise[Option[A]] = _

  def next(): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else {
      val future = upstream.next()
      future.value match {
        case Some(result) => handle(result, future)
        case None         => future.transformWith(result => handle(result, future))(Trampoline)
      }
    }

  private def handle(
    result: Try[Option[A]],
    future: Future[Option[A]]
  ): Future[Option[A]] =
    result match {
      case Success(Some(_)) => future
      case Success(None) =>
        if (cancelled) Cursors.NoneFuture else finish(Termination.Completed, None)
      case Failure(e) =>
        if (cancelled) Cursors.NoneFuture else finish(Termination.Failed(e), Some(e))
    }

  // the downstream signal waits for the hook
  private def finish(
    termination: Termination,
    failure: Option[Throwable]
  ): Future[Option[A]] =
    if (!fired.compareAndSet(false, true))
      Cursors.NoneFuture // cancelled meanwhile
    else {
      def outcome(hookResult: Try[Unit]): Try[Option[A]] =
        (failure, hookResult) match {
          case (None, Success(_))         => Success(None)
          case (None, Failure(hookError)) => Failure(hookError)
          case (Some(e), Success(_))      => Failure(e)
          case (Some(e), Failure(hookError)) =>
            if (hookError ne e) e.addSuppressed(hookError)
            Failure(e)
        }

      val hookFuture = callHook(termination)
      hookFuture.value match {
        case Some(hookResult) => Future.fromTry(outcome(hookResult))
        case None =>
          val promise = Promise[Option[A]]()
          pending = promise
          if (cancelled) promise.trySuccess(None)
          hookFuture.onComplete(hookResult => promise.tryComplete(outcome(hookResult)))(ctx.ec)
          promise.future
      }
    }

  private def callHook(termination: Termination): Future[Unit] =
    try Cursors.nonNull(hook(termination), "The termination hook")
    catch { case NonFatal(e) => Future.failed(e) }

  def cancel(): Unit = {
    cancelled = true
    upstream.cancel()
    val promise = pending
    if (promise != null) promise.trySuccess(None)

    if (fired.compareAndSet(false, true)) {
      val callCancelled = new Runnable {
        def run(): Unit =
          callHook(Termination.Cancelled).failed.foreach { e =>
            OnTerminateCursor.logger.warn("A termination hook failed on cancel", e)
          }(Trampoline)
      }
      try ctx.ec.execute(callCancelled)
      catch { case NonFatal(_) => callCancelled.run() }
    }
  }
}

private[wsclient] object OnTerminateCursor {
  private val logger = Logger(LoggerFactory.getLogger(classOf[OnTerminateCursor[_]]))
}

private[wsclient] final class ConcurrentlyCursor[A](
  task: () => Future[Any],
  awaitCompletion: Boolean,
  ctx: RunContext
) extends Cursor[A] {

  @volatile private var upstream: Cursor[A] = _
  @volatile private var cancelled = false
  @volatile private var ended = false
  @volatile private var taskError: Throwable = _
  @volatile private var pending: Promise[Option[A]] = _

  private val taskFuture: Future[Any] =
    try Cursors.nonNull(task(), "The concurrent task")
    catch { case NonFatal(e) => Future.failed(e) }

  def start(upstreamCursor: Cursor[A]): Unit = {
    upstream = upstreamCursor
    taskFuture.onComplete {
      case Failure(e) if !ended && !cancelled =>
        taskError = e
        upstreamCursor.cancel()
        val promise = pending
        if (promise != null) promise.tryFailure(e)
      case _ =>
    }(ctx.ec)
  }

  def next(): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else {
      val error = taskError
      if (error != null) {
        ended = true
        Future.failed(error)
      } else {
        val future = upstream.next()
        future.value match {
          case Some(result) => handle(result)
          case None =>
            val promise = Promise[Option[A]]()
            pending = promise
            future.onComplete(result => promise.completeWith(handle(result)))(Trampoline)
            promise.future
        }
      }
    }

  private def handle(result: Try[Option[A]]): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else {
      val error = taskError
      if (error != null) {
        ended = true
        Future.failed(error)
      } else
        result match {
          case Success(Some(element)) => Future.successful(Some(element))
          case Success(None) =>
            if (!awaitCompletion) {
              ended = true
              Cursors.NoneFuture
            } else
              awaitTask()
          case Failure(e) =>
            ended = true
            Future.failed(e)
        }
    }

  private def awaitTask(): Future[Option[A]] =
    taskFuture.value match {
      case Some(Success(_)) =>
        ended = true
        Cursors.NoneFuture
      case Some(Failure(e)) =>
        ended = true
        Future.failed(e)
      case None =>
        val promise = Promise[Option[A]]()
        pending = promise
        if (cancelled) promise.trySuccess(None)
        taskFuture.onComplete { taskResult =>
          ended = true
          taskResult match {
            case Success(_) => promise.trySuccess(None)
            case Failure(e) => promise.tryFailure(e)
          }
        }(ctx.ec)
        promise.future
    }

  def cancel(): Unit = {
    cancelled = true
    val upstreamCursor = upstream
    if (upstreamCursor != null) upstreamCursor.cancel()
    val promise = pending
    if (promise != null) promise.trySuccess(None)
  }
}
