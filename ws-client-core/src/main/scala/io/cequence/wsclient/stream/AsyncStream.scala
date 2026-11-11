package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl._

import java.util.concurrent.{ConcurrentLinkedQueue, Flow}
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * A backend-neutral, asynchronous stream of `A`s - the stream type every ws-client engine can
 * produce and every consumer (plain Futures, Akka, Pekko, fs2, ZIO) can run.
 *
 * Semantics:
 *   - COLD: building a stream does no I/O. Every user thunk (`defer`, a `stateful` init,
 *     `statefulMapConcat`'s create, a publisher factory, `future` / `futureStream`, a
 *     `concurrently` task, a blocking `open`) runs per run, no later than the first demand -
 *     never when the stream is built.
 *   - RE-RUNNABLE: each run is independent; for an HTTP stream, a run is a new request (a
 *     re-run is a new, possibly billed, request).
 *   - ONE CONSUMER PER RUN: fan-out goes through the hot [[AsyncBroadcastHub]].
 *   - BACK-PRESSURED: elements are pulled, and every buffer is bounded.
 *   - CANCELLATION travels upstream to the leaf: it aborts the HTTP request, closes the
 *     `InputStream` / iterator. `onTerminate(Cancelled)` fires exactly once.
 *   - FAILURE: a non-fatal exception thrown by any user function (or a failed `evalMap`
 *     future) fails the stream with that exception, unwrapped. There is no resume supervision;
 *     fatal errors are never caught.
 *   - ORDERED COMPLETION: a `stateful` end hook and a `guaranteeCase(Completed)` hook finish
 *     before completion is signalled downstream.
 *   - PER-RUN STATE: allocate mutable state only inside `defer`, a `stateful` init or a
 *     `statefulMapConcat` create - never when building a stream or a pipe (it would be shared
 *     by every run).
 *
 * A stream is an immutable plan (an AST): a native leaf (e.g. an Akka engine's `Source`) stays
 * native, and a backend runs its own leaf directly - `Source -> AsyncStream -> Source` gives
 * back the same `Source`, with the operators applied as native stages. A bridge (through
 * `java.util.concurrent.Flow.Publisher`) happens only at a foreign leaf (once) or a foreign
 * native pipe (twice).
 *
 * The run methods (`runForeach`, `runFold`, ...) execute on the neutral interpreter: user
 * functions run on the given `ExecutionContext` (never on an IO thread); the elements of one
 * stage are processed sequentially, with happens-before between calls (stage state needs no
 * locks).
 */
abstract class AsyncStream[+A] private[wsclient] () {

  import AsyncStream._

  // ----------------------------------------------------------------------- element operators

  def map[B](f: A => B): AsyncStream[B] = Mapped(this, f)

  /** `f` runs eagerly when the element arrives; its elements are emitted in order. */
  def mapConcat[B](f: A => immutable.Iterable[B]): AsyncStream[B] = MapConcat(this, f)

  def filter(p: A => Boolean): AsyncStream[A] =
    stateless[A]("filter") { a =>
      if (p(a))
        a :: Nil
      else
        Nil
    }

  def filterNot(p: A => Boolean): AsyncStream[A] =
    stateless[A]("filterNot") { a =>
      if (p(a))
        Nil
      else
        a :: Nil
    }

  def collect[B](pf: PartialFunction[A, B]): AsyncStream[B] =
    stateless[B]("collect") { a =>
      val b: Any = pf.applyOrElse(a, NoMatchFunction)
      if (b.asInstanceOf[AnyRef] eq NoMatch)
        Nil
      else
        b.asInstanceOf[B] :: Nil
    }

  /** `mapAsync(1)`: one future at a time, in order. A failed future fails the stream. */
  def evalMap[B](f: A => Future[B]): AsyncStream[B] = EvalMap(this, f)

  /** `create` runs once per run; the function it returns is applied to every element. */
  def statefulMapConcat[B](create: () => A => immutable.Iterable[B]): AsyncStream[B] =
    Stateful[A, A => immutable.Iterable[B], B](
      this,
      create,
      (
        f,
        a
      ) => Step(f, f(a)),
      _ => Nil,
      "statefulMapConcat"
    )

  /**
   * Per-run state: `init` once per run, `step` for each element (eagerly, when it arrives),
   * `onComplete` on NORMAL completion only - its elements are emitted before the completion,
   * and a throw fails the stream. A step with `stop = true` emits its elements, then completes
   * the stream (the upstream is cancelled, `onComplete` is not called).
   */
  def stateful[S, B](
    init: () => S
  )(
    step: (S, A) => Step[S, B],
    onComplete: S => immutable.Iterable[B] = (_: S) => Nil
  ): AsyncStream[B] =
    Stateful(this, init, step, onComplete, "stateful")

  /** Emits `zero`, then each accumulated value. */
  def scan[S](zero: S)(f: (S, A) => S): AsyncStream[S] =
    Concat[S](
      Emits(zero :: Nil),
      () =>
        Stateful[A, S, S](
          this,
          () => zero,
          (
            s,
            a
          ) => {
            val next = f(s, a)
            Step(next, next :: Nil)
          },
          _ => Nil,
          "scan"
        )
    )

  /** One element: the accumulated value, when the upstream completes (`zero` if empty). */
  def fold[S](zero: S)(f: (S, A) => S): AsyncStream[S] =
    Stateful[A, S, S](
      this,
      () => zero,
      (
        s,
        a
      ) => Step(f(s, a)),
      s => s :: Nil,
      "fold"
    )

  def zipWithIndex: AsyncStream[(A, Long)] =
    Stateful[A, Long, (A, Long)](
      this,
      () => 0L,
      (
        i,
        a
      ) => Step(i + 1, (a, i) :: Nil),
      _ => Nil,
      "zipWithIndex"
    )

  /**
   * The first `n` elements; the upstream is cancelled after the `n`-th. `n <= 0`: never runs
   * the upstream.
   */
  def take(n: Long): AsyncStream[A] =
    if (n <= 0)
      Nodes.Empty
    else
      Stateful[A, Long, A](
        this,
        () => 0L,
        (
          count,
          a
        ) => {
          val taken = count + 1
          Step(taken, a :: Nil, stop = taken >= n)
        },
        _ => Nil,
        s"take($n)"
      )

  /** Elements while `p` holds; with `inclusive`, also the first element failing `p`. */
  def takeWhile(
    p: A => Boolean,
    inclusive: Boolean = false
  ): AsyncStream[A] =
    Stateful[A, Unit, A](
      this,
      () => (),
      (
        _,
        a
      ) =>
        if (p(a))
          Step((), a :: Nil)
        else if (inclusive)
          Step((), a :: Nil, stop = true)
        else
          Step((), Nil, stop = true),
      _ => Nil,
      if (inclusive) "takeWhile(inclusive)" else "takeWhile"
    )

  def drop(n: Long): AsyncStream[A] =
    if (n <= 0)
      this
    else
      Stateful[A, Long, A](
        this,
        () => 0L,
        (
          dropped,
          a
        ) =>
          if (dropped < n)
            Step(dropped + 1)
          else
            Step(dropped, a :: Nil),
        _ => Nil,
        s"drop($n)"
      )

  // ------------------------------------------------------------- composition and errors

  /** `that` (by-name, evaluated per run) runs after this stream completed. */
  def concat[B >: A](that: => AsyncStream[B]): AsyncStream[B] = Concat[B](this, () => that)

  def ++[B >: A](that: => AsyncStream[B]): AsyncStream[B] = concat(that)

  /**
   * Sequential: each element's stream runs to completion before the next element is pulled.
   */
  def flatMapConcat[B](f: A => AsyncStream[B]): AsyncStream[B] = FlatMapConcat(this, f)

  def via[B](pipe: AsyncPipe[A, B]): AsyncStream[B] = pipe(this)

  /**
   * Runs the projections of the elements through `pipe` and combines each element with the
   * pipe's output for it. The pipe must be 1:1 (one output per input, in order) - a pipe that
   * emits more outputs than it got fails the stream; one that drops elements misaligns them.
   */
  def zipVia[X, Y, B](
    project: A => X,
    pipe: AsyncPipe[X, Y]
  )(
    combine: (A, Y) => B
  ): AsyncStream[B] = {
    val upstream = this
    AsyncStream.defer {
      val inFlight =
        new ConcurrentLinkedQueue[A @scala.annotation.unchecked.uncheckedVariance]()
      upstream.map { a =>
        inFlight.add(a)
        project(a)
      }.via(pipe).map { y =>
        val a = inFlight.poll()
        if (a == null)
          throw new IllegalStateException(
            "zipVia: the pipe emitted more elements than it received (it must be 1:1)"
          )
        else
          combine(a, y)
      }
    }
  }

  def mapError(pf: PartialFunction[Throwable, Throwable]): AsyncStream[A] = MapError(this, pf)

  /** On a failure `pf` is defined for: emits `pf(e)`, then completes. */
  def recover[B >: A](pf: PartialFunction[Throwable, B]): AsyncStream[B] =
    RecoverWith[B](this, pf.andThen(b => Emits(b :: Nil)))

  /**
   * On a failure `pf` is defined for: continues with `pf(e)` (once; its own failure is final).
   */
  def recoverWith[B >: A](pf: PartialFunction[Throwable, AsyncStream[B]]): AsyncStream[B] =
    RecoverWith[B](this, pf)

  // -------------------------------------------------------------------------- lifecycle

  /** `guaranteeCase` with a synchronous hook. */
  def onTerminate(f: Termination => Unit): AsyncStream[A] =
    guaranteeCase { termination =>
      f(termination)
      Future.unit
    }

  /**
   * `f` is called exactly once per started run. On `Completed` / `Failed(e)` the downstream
   * signal is delivered only after `f`'s future completed: a failing `f` fails a completed
   * run, and is attached to `e` (suppressed) on a failed one. On `Cancelled`, `f` is called
   * after the upstream was cancelled (the neutral interpreter does not wait for it).
   */
  def guaranteeCase(f: Termination => Future[Unit]): AsyncStream[A] = OnTerminate(this, f)

  /**
   * Runs `task()` alongside each run (started once per run, no later than the first demand).
   * If it fails before the stream ended, the upstream is cancelled and the stream fails with
   * its error. With `awaitCompletion`, a normal completion waits for the task, and fails if
   * the task fails. A downstream cancel ignores the task's outcome (a `Future` cannot be
   * cancelled).
   */
  def concurrently(
    task: () => Future[Any],
    awaitCompletion: Boolean = false
  ): AsyncStream[A] =
    Concurrently(this, task, awaitCompletion)

  /**
   * Starts a run NOW. The future completes with a SINGLE-SHOT stream when the first element
   * (or the empty completion) arrived - it replays that element and continues the same run -
   * and fails if the run fails before. A second run of the returned stream fails with an
   * `IllegalStateException`. A preflighted stream that is never run holds its connection until
   * the server or an idle timeout closes it - run or cancel it.
   *
   * A stream whose source is a native Akka / Pekko or ZIO stream (an engine's stream, a
   * converted `Source` / `ZStream`) is preflighted by that backend, on the source's own
   * materializer / runtime, and comes back native: a consumer of the same backend runs it
   * without a bridge. Any other stream runs on the neutral interpreter, on `ec`.
   */
  def preflight(
    implicit ec: ExecutionContext
  ): Future[AsyncStream[A]] =
    StreamCompiler.leafOf(this) match {
      case Native(leaf: NativePreflight) =>
        try leaf.preflight(this)
        catch { case NonFatal(e) => Future.failed(e) }
      case _ =>
        NeutralRunner.preflight(this)
    }

  // ---------------------------------------------------------------------------- running

  def runForeach(
    f: A => Unit
  )(
    implicit ec: ExecutionContext
  ): Future[Unit] =
    NeutralRunner.runFold[A, Unit](this, ()) {
      (
        _,
        a
      ) =>
        f(a)
    }

  def runFold[S](
    zero: S
  )(
    f: (S, A) => S
  )(
    implicit ec: ExecutionContext
  ): Future[S] =
    NeutralRunner.runFold(this, zero)(f)

  def runToSeq(
    implicit ec: ExecutionContext
  ): Future[immutable.Seq[A]] =
    NeutralRunner
      .runFold(this, Vector.newBuilder[A]) {
        (
          builder,
          a
        ) =>
          builder += a
      }
      .map(_.result())(Trampoline)

  /**
   * The first element (the upstream is then cancelled); fails with `NoSuchElementException` if
   * empty.
   */
  def runHead(
    implicit ec: ExecutionContext
  ): Future[A] =
    runHeadOption.map(
      _.getOrElse(throw new NoSuchElementException("runHead of an empty stream"))
    )(Trampoline)

  def runHeadOption(
    implicit ec: ExecutionContext
  ): Future[Option[A]] =
    NeutralRunner.runHeadOption(this)

  /** The last element; fails with `NoSuchElementException` if empty. */
  def runLast(
    implicit ec: ExecutionContext
  ): Future[A] =
    runLastOption.map(
      _.getOrElse(throw new NoSuchElementException("runLast of an empty stream"))
    )(Trampoline)

  def runLastOption(
    implicit ec: ExecutionContext
  ): Future[Option[A]] =
    NeutralRunner.runFold[A, Option[A]](this, None)(
      (
        _,
        a
      ) => Some(a)
    )

  def runDrain(
    implicit ec: ExecutionContext
  ): Future[Unit] =
    NeutralRunner.runFold[A, Unit](this, ())(
      (
        _,
        _
      ) => ()
    )

  /** Runs the stream, applying `f` to every element; the run can be cancelled. */
  def run(
    f: A => Unit
  )(
    implicit ec: ExecutionContext
  ): StreamRun =
    NeutralRunner.run(this, f)

  /**
   * This stream as a cold `Flow.Publisher`: one independent run per subscription (Reactive
   * Streams compliant, verified with the RS TCK).
   */
  def toPublisher[B >: A](
    implicit ec: ExecutionContext
  ): Flow.Publisher[B] =
    new CursorPublisher[B](
      StreamCompiler.compile(NeutralCompiler, this),
      StreamRuntime.context(ec)
    )

  /**
   * The plan of this stream, leaf first (e.g. `fromPublisher -> map -> take(1)`) - never its
   * elements.
   */
  def describe: String = StreamCompiler.describe(this)

  // a stream is a plan: identity equality (structural equality would compare functions, and an
  // `Emits` iterable could run a per-run iterator); `toString` never shows elements
  final override def equals(other: Any): Boolean =
    other.isInstanceOf[AnyRef] && (this eq other.asInstanceOf[AnyRef])

  final override def hashCode(): Int = System.identityHashCode(this)

  final override def toString: String = s"AsyncStream($describe)"

  private def stateless[B](name: String)(f: A => immutable.Iterable[B]): AsyncStream[B] =
    Stateful[A, Unit, B](
      this,
      () => (),
      (
        _,
        a
      ) => Step((), f(a)),
      _ => Nil,
      name
    )
}

object AsyncStream {

  /**
   * The result of one `stateful` step: the next state, the elements to emit, and whether the
   * stream completes after them. Built with `Step(state, emit, stop)`.
   */
  // a plain class, not a case class: a case class's apply / copy / unapply would be frozen
  // by binary compatibility, so the type could never grow
  final class Step[+S, +B] private (
    val state: S,
    val emit: immutable.Iterable[B],
    val stop: Boolean
  ) {

    override def equals(other: Any): Boolean =
      other match {
        case that: Step[_, _] => state == that.state && emit == that.emit && stop == that.stop
        case _                => false
      }

    override def hashCode: Int = (state, emit, stop).hashCode

    override def toString: String = s"Step($state, $emit, $stop)"
  }

  object Step {

    def apply[S, B](
      state: S,
      emit: immutable.Iterable[B] = Nil,
      stop: Boolean = false
    ): Step[S, B] = new Step(state, emit, stop)
  }

  private object NoMatch
  private val NoMatchFunction: Any => Any = _ => NoMatch

  def empty[A]: AsyncStream[A] = Nodes.Empty

  def single[A](a: A): AsyncStream[A] = Emits(a :: Nil)

  def apply[A](as: A*): AsyncStream[A] = Emits(as.toList)

  def fromIterable[A](as: immutable.Iterable[A]): AsyncStream[A] = Emits(as)

  /** `create` runs once per run. For NON-blocking iterators - see [[fromBlockingIterator]]. */
  def fromIterator[A](create: () => Iterator[A]): AsyncStream[A] =
    Emits(new PerRunIterable(create))

  def failed[A](error: Throwable): AsyncStream[A] = FailedStream(error)

  /** `thunk` is evaluated once per run - the place for per-run state. */
  def defer[A](thunk: => AsyncStream[A]): AsyncStream[A] = Defer(() => thunk)

  /** One element: the future's value. `f` (by-name) is evaluated once per run. */
  def future[A](f: => Future[A]): AsyncStream[A] =
    FutureStream(() => f.map(a => Emits(a :: Nil): AsyncStream[A])(Trampoline))

  /** The stream the future yields. `f` (by-name) is evaluated once per run. */
  def futureStream[A](f: => Future[AsyncStream[A]]): AsyncStream[A] = FutureStream(() => f)

  /**
   * A stream from a publisher factory, called once per run (so a single-shot publisher such as
   * an engine's `execJsonStreamPublisher` becomes a re-runnable stream). `bufferSize` bounds
   * the demand: `bufferSize` elements are requested up front, and half as many more after each
   * half consumed (Akka / Pekko round it up to a power of two, at most 4096 - their input
   * buffer, preallocated per run).
   */
  def fromPublisher[A](
    factory: () => Flow.Publisher[_ <: A],
    bufferSize: Int = 16
  ): AsyncStream[A] = {
    require(bufferSize > 0, s"bufferSize must be positive: $bufferSize")
    FromPublisher[A](factory, bufferSize)
  }

  /**
   * A stream over one existing publisher: single-shot, a second run fails with
   * `IllegalStateException`.
   */
  def fromPublisherOnce[A](
    publisher: Flow.Publisher[_ <: A],
    bufferSize: Int = 16
  ): AsyncStream[A] = {
    val used = new java.util.concurrent.atomic.AtomicBoolean(false)
    fromPublisher[A](
      () =>
        if (used.compareAndSet(false, true))
          publisher
        else
          throw new IllegalStateException(
            "This stream wraps one publisher and supports a single run only"
          ),
      bufferSize
    )
  }

  /**
   * The bytes of an `InputStream`, in chunks of at most `chunkSize` bytes. `open` runs once
   * per run and every read on a blocking pool; the stream is closed exactly once (at the end,
   * on failure, or on cancel - after the read in progress returned).
   */
  def fromInputStream(
    open: () => java.io.InputStream,
    chunkSize: Int = 8192
  ): AsyncStream[Bytes] = {
    require(chunkSize > 0, s"chunkSize must be positive: $chunkSize")
    FromBlocking(() => new InputStreamCursor(open(), chunkSize))
  }

  /**
   * The elements of a BLOCKING iterator (e.g. a gRPC stream). `open` runs once per run, and
   * every `hasNext` / `next` on a blocking pool; `close` is called exactly once (at the end,
   * on failure, or on cancel - after the read in progress returned).
   */
  def fromBlockingIterator[A](
    open: () => Iterator[A],
    close: () => Unit = () => ()
  ): AsyncStream[A] =
    FromBlocking(() => new IteratorCursor(open(), close))

  /** A stream in its producer's native representation - see [[NativeStream]]. */
  def fromNative[A](leaf: NativeStream[A]): AsyncStream[A] = Native(leaf)

  // an iterable whose iterator is created per run - never iterated outside a run
  private final class PerRunIterable[A](create: () => Iterator[A])
      extends immutable.Iterable[A] {
    override def iterator: Iterator[A] = create()
    override def toString: String = "PerRunIterable"
  }

  private final class InputStreamCursor(
    in: java.io.InputStream,
    chunkSize: Int
  ) extends BlockingCursor[Bytes] {
    override def next(): Option[Bytes] = {
      val buffer = new Array[Byte](chunkSize)
      val read = in.read(buffer)
      if (read < 0)
        None
      else if (read < chunkSize / 2)
        // a short read keeps only its bytes, not the whole chunk-sized buffer
        Some(Bytes.unsafeWrap(java.util.Arrays.copyOf(buffer, read)))
      else
        Some(Bytes.unsafeWrap(buffer, 0, read))
    }

    override def close(): Unit = in.close()
  }

  private final class IteratorCursor[A](
    it: Iterator[A],
    onClose: () => Unit
  ) extends BlockingCursor[A] {
    override def next(): Option[A] =
      if (it.hasNext)
        Some(it.next())
      else
        None

    override def close(): Unit = onClose()
  }
}
