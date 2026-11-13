package io.cequence.wsclient.stream

import io.cequence.wsclient.concurrent.{AsyncCancellable, AsyncScheduler}
import io.cequence.wsclient.stream.impl._
import zio._
import zio.stream.{ZPipeline, ZStream}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{Flow => JFlow}
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * A ZIO stream kept native inside an [[AsyncStream]]: compiled for ZIO it is `stream` itself -
 * no bridge; any other consumer runs it on `runtime` (one bridge). A stream over it is
 * preflighted as a ZIO stream, on the same runtime.
 */
private[wsclient] final class ZioLeaf[A] private[wsclient] (
  val stream: ZStream[Any, Throwable, A],
  runtime: Runtime[Any],
  preflighted: Option[ZioBridges.PreflightedRun[A]]
) extends NativeStream[A]
    with NativePreflight {

  private[wsclient] def this(
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any]
  ) =
    this(stream, runtime, None)

  // a preflighted stream claims its single run HERE - when a consumer outside ZIO starts the
  // run (the neutral runner, Akka, fs2 call it as the run opens) - not when the run's fiber
  // first evaluates `stream`: a run cancelled before that would hold the response forever
  override def openPublisher(): JFlow.Publisher[_ <: A] =
    preflighted.fold[JFlow.Publisher[_ <: A]](ZioBridges.publisher(stream, runtime))(
      _.publisher(runtime)
    )
  override def backend: String = "zio"

  override def preflight[B](whole: AsyncStream[B]): Future[AsyncStream[B]] =
    ZioBridges.preflight(ZioCompiler.toZStream(whole), runtime)
}

/** A ZIO pipeline kept native inside an [[AsyncPipe]]. */
private[wsclient] final class ZioPipe[A, B] private[wsclient] (
  val pipeline: ZPipeline[Any, Throwable, A, B],
  runtime: Runtime[Any]
) extends NativePipe[A, B] {

  override def transform(upstream: JFlow.Publisher[_ <: A]): JFlow.Publisher[_ <: B] =
    ZioBridges.publisher(
      ZioBridges
        .cursorStream[A](
          ctx => SubscriberCursor[A](() => upstream, Cursors.BridgeBufferSize, ctx),
          BridgeCounter.Global
        )
        .via(pipeline),
      runtime
    )

  override def backend: String = "zio"
}

/**
 * Conversions between [[AsyncStream]] / [[AsyncPipe]] and ZIO streams (since 1.2.0): `import
 * io.cequence.wsclient.stream.ZioInterop._`.
 *
 *   - `stream.toZStream` compiles the stream natively for ZIO: ZIO leaves stay themselves,
 *     every operator is a ZIO stream operator (user functions run under `ZIO.attempt`: a throw
 *     is a failure, not a defect)
 *   - `zStream.toAsyncStream` keeps the stream native; the implicit `Runtime` is used only
 *     when the stream is consumed outside ZIO
 *   - `bytes.toZByteStream` / `zByteStream.toAsyncByteStream` convert between
 *     `AsyncStream[Bytes]` and `ZStream[Any, Throwable, Byte]` - chunks are shared, not
 *     copied, towards ZIO; a converted ZIO byte stream (or the zio-http engine's raw stream)
 *     comes back as its own chunks
 *   - `pipe.toZStreamFunction` / `ZioInterop.fromZPipeline(pipeline)` likewise for
 *     transformations
 */
object ZioInterop {

  implicit final class AsyncStreamToZio[A](private val stream: AsyncStream[A]) extends AnyVal {
    def toZStream: ZStream[Any, Throwable, A] = ZioCompiler.toZStream(stream)
  }

  implicit final class ZioToAsyncStream[A](private val stream: ZStream[Any, Throwable, A])
      extends AnyVal {
    def toAsyncStream(
      implicit runtime: Runtime[Any]
    ): AsyncStream[A] =
      AsyncStream.fromNative(new ZioLeaf(stream, runtime))
  }

  implicit final class AsyncBytesToZio(private val stream: AsyncStream[Bytes]) extends AnyVal {
    def toZByteStream: ZStream[Any, Throwable, Byte] = ZioCompiler.toZByteStream(stream)
  }

  implicit final class ZioByteStreamToAsync(private val stream: ZStream[Any, Throwable, Byte])
      extends AnyVal {
    def toAsyncByteStream(
      implicit runtime: Runtime[Any]
    ): AsyncStream[Bytes] =
      byteChunkLeaf(stream.chunks, runtime)
  }

  implicit final class AsyncPipeToZio[A, B](private val pipe: AsyncPipe[A, B]) extends AnyVal {
    def toZStreamFunction: ZStream[Any, Throwable, A] => ZStream[Any, Throwable, B] =
      ZioCompiler.toZStreamFunction(pipe)
  }

  def fromZPipeline[A, B](
    pipeline: ZPipeline[Any, Throwable, A, B]
  )(
    implicit runtime: Runtime[Any]
  ): AsyncPipe[A, B] =
    AsyncPipe.fromNative(new ZioPipe(pipeline, runtime))

  /** An engine's native stream (its fallback runs on the engine's own runtime). */
  private[wsclient] def engineLeaf[A](
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any]
  ): AsyncStream[A] =
    AsyncStream.fromNative(new ZioLeaf(stream, runtime))

  /** An engine's native byte stream - `toZByteStream` gives back `chunks`, flattened. */
  private[wsclient] def byteChunkEngineLeaf(
    chunks: ZStream[Any, Throwable, Chunk[Byte]],
    runtime: Runtime[Any]
  ): AsyncStream[Bytes] =
    byteChunkLeaf(chunks, runtime)

  private def byteChunkLeaf(
    chunks: ZStream[Any, Throwable, Chunk[Byte]],
    runtime: Runtime[Any]
  ): AsyncStream[Bytes] =
    AsyncStream.fromNative(new ZioLeaf(chunks, runtime)).map(ZioCompiler.FromChunk)

  /** ZIO's clock as a [[io.cequence.wsclient.concurrent.AsyncScheduler]]. */
  def scheduler(
    implicit runtime: Runtime[Any]
  ): AsyncScheduler =
    new AsyncScheduler {
      override def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable = {
        // the task and cancel() race for one claim: exactly one of them wins (a cancel that
        // returns true means the task never runs)
        val claimed = new AtomicBoolean(false)
        val cancelled = new AtomicBoolean(false)
        val fiber = Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.fork(
            ZIO.sleep(Duration.fromScala(delay)) *>
              ZIO.succeed(if (claimed.compareAndSet(false, true)) task())
          )
        }
        new AsyncCancellable {
          override def cancel(): Boolean =
            if (claimed.compareAndSet(false, true)) {
              cancelled.set(true)
              Unsafe.unsafe(implicit unsafe => runtime.unsafe.fork(fiber.interrupt))
              true
            } else
              false
          override def isCancelled: Boolean = cancelled.get()
        }
      }
    }
}

/**
 * Opt-in implicit conversions between [[AsyncStream]] and ZIO streams: `import
 * io.cequence.wsclient.stream.ZioConversions._`. Prefer the explicit `ZioInterop` methods in
 * new code.
 */
object ZioConversions {

  import scala.language.implicitConversions

  implicit def asyncStreamToZio[A](stream: AsyncStream[A]): ZStream[Any, Throwable, A] =
    ZioCompiler.toZStream(stream)

  implicit def zioToAsyncStream[A](
    stream: ZStream[Any, Throwable, A]
  )(
    implicit runtime: Runtime[Any]
  ): AsyncStream[A] =
    AsyncStream.fromNative(new ZioLeaf(stream, runtime))
}

/** The hand-written pull bridges between ZIO streams and the neutral interpreter's cursors. */
private[wsclient] object ZioBridges {

  /** A run of a cursor as a ZIO stream: opened per run, cancelled when the stream ends. */
  def cursorStream[A](
    open: RunContext => Cursor[A],
    bridges: BridgeCounter
  ): ZStream[Any, Throwable, A] =
    ZStream.unwrapScoped[Any] {
      for {
        ec <- ZIO.executor.map(_.asExecutionContext)
        cursor <- ZIO.acquireRelease(ZIO.attempt(open(StreamRuntime.context(ec, bridges))))(
          cursor => ZIO.succeed(cursor.cancel())
        )
      } yield ZStream.repeatZIOOption(
        ZIO.fromFuture(_ => cursor.next()).mapError(Some(_)).flatMap {
          case Some(element) => ZIO.succeed(element)
          case None          => ZIO.fail(None)
        }
      )
    }

  /**
   * `stream` preflighted natively (see `NativePreflight`): pulled at once on `runtime`, in its
   * own scope, up to its first non-empty chunk; the returned (native, single-run) stream
   * replays that chunk and continues the same pull, and closes the scope - the stream's
   * finalizers - when its run ends, however it ends. A stream that ended or failed before a
   * first element has its scope closed here.
   *
   * The fiber of those first pulls stays until the run ended: a fiber the stream forked in
   * them (a producer feeding a queue) is its child, and would be interrupted with it.
   */
  def preflight[A](
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any]
  ): Future[AsyncStream[A]] = {
    val result = scala.concurrent.Promise[AsyncStream[A]]()

    def hand(run: PreflightedRun[A]): UIO[Unit] =
      ZIO.succeed {
        result.trySuccess(AsyncStream.fromNative(new ZioLeaf(run.native, runtime, Some(run))))
        ()
      }

    val start: ZIO[Any, Nothing, Unit] =
      ZIO.uninterruptibleMask { restore =>
        for {
          scope <- Scope.make
          runEnded <- Promise.make[Nothing, Unit]
          started <- restore(
            scope
              .extend[Any](stream.toPull)
              .flatMap(pull => pull.repeatUntil(_.nonEmpty).unsome.map(first => (first, pull)))
          ).exit
          _ <- started match {
            case Exit.Success((Some(first), pull)) =>
              val run = new PreflightedRun[A](
                ZStream.fromChunk(first) ++ ZStream.repeatZIOChunkOption(pull),
                exit => scope.close(exit).ensuring(runEnded.succeed(()))
              )
              hand(run) *> restore(runEnded.await)
            case Exit.Success((None, _)) =>
              scope.close(Exit.unit) *>
                hand(new PreflightedRun[A](ZStream.empty, _ => ZIO.unit))
            case Exit.Failure(cause) =>
              // the stream's own exception, as it is (nothing attached)
              scope.close(started) *> ZIO.succeed(result.tryFailure(cause.squash))
          }
        } yield ()
      }

    // a fiber that ended without a result (a finalizer's defect, a runtime shutting down)
    Unsafe
      .unsafe(implicit unsafe => runtime.unsafe.runToFuture(start))
      .failed
      .foreach(result.tryFailure)(Trampoline)
    result.future
  }

  /**
   * The rest of a preflighted stream (`body`: the replayed first chunk, then the same pull)
   * and how to release what the preflight holds (`release`: closes its scope). ONE run only,
   * claimed by whichever starts first: a native ZIO consumer when its run evaluates `native`,
   * any other consumer when its run opens the leaf's publisher (`publisher`) - and released
   * when that run ends, however it ends: also when it is cancelled before its fiber started
   * (the claim is then made already) or before the publisher opened it (`Opener.unopened`).
   */
  private[wsclient] final class PreflightedRun[A](
    body: ZStream[Any, Throwable, A],
    release: Exit[Any, Any] => UIO[Any]
  ) {
    private val claimed = new AtomicBoolean(false)

    private def claim(): Boolean = claimed.compareAndSet(false, true)

    val native: ZStream[Any, Throwable, A] =
      ZStream.unwrapScoped[Any](
        ZIO
          .acquireReleaseExit(ZIO.succeed(claim()))(
            (
              won,
              exit
            ) => release(exit).when(won)
          )
          .map(won => if (won) body else ZStream.fail(NativePreflight.singleRunOnly()))
      )

    def publisher(runtime: Runtime[Any]): JFlow.Publisher[A] =
      if (claim()) ZioBridges.publisher(body, runtime, release)
      else ZioBridges.publisher(ZStream.fail(NativePreflight.singleRunOnly()), runtime)
  }

  /** A ZIO stream as a cold publisher: one run (on `runtime`) per subscription. */
  def publisher[A](
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any]
  ): JFlow.Publisher[A] =
    new CursorPublisher[A](
      new Opener[A] {
        def open(ctx: RunContext): Cursor[A] = new ZioCursor(stream, runtime, ctx)
      },
      StreamRuntime.context(executionContext(runtime), BridgeCounter.Global)
    )

  /**
   * A ZIO stream that holds a resource for its ONE run (a preflighted stream) as a publisher:
   * `release` runs once, when the run's fiber ended - or at once when the subscription ended
   * before the run was opened. A second subscription fails (a single run only).
   */
  def publisher[A](
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any],
    release: Exit[Any, Any] => UIO[Any]
  ): JFlow.Publisher[A] = {
    val opened = new AtomicBoolean(false)
    new CursorPublisher[A](
      new Opener[A] {
        def open(ctx: RunContext): Cursor[A] =
          if (opened.compareAndSet(false, true)) new ZioCursor(stream, runtime, ctx, release)
          else new ZioCursor(ZStream.fail(NativePreflight.singleRunOnly()), runtime, ctx)

        override def unopened(): Unit =
          if (opened.compareAndSet(false, true))
            Unsafe.unsafe(implicit unsafe => runtime.unsafe.fork(release(Exit.unit)))
      },
      StreamRuntime.context(executionContext(runtime), BridgeCounter.Global)
    )
  }

  private def executionContext(runtime: Runtime[Any]): scala.concurrent.ExecutionContext =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.executor.map(_.asExecutionContext)).getOrThrowFiberFailure()
    }
}

/**
 * A run of a ZIO stream as a cursor, on ONE fiber: it opens the stream's pull in a scope of
 * its own, pulls a chunk whenever one is asked for (chunks are handed out element by element)
 * and closes the scope - the stream's finalizers - when the run ends. A fiber the stream forks
 * is that fiber's child and lives as long as the run, as for a native ZIO consumer (a fiber
 * per pull would take the children of each pull with it: a producer forked by the first pull,
 * feeding a queue, stopped after it).
 *
 * `cancel()` completes the outstanding `next()` and interrupts the fiber: a pull in flight is
 * interrupted, and the scope closes once that pull has finished - a resource it acquired
 * meanwhile is released too. The end of the stream (or its failure) is signalled after the
 * scope closed - its finalizers have run, as for a native ZIO consumer.
 */
private[wsclient] final class ZioCursor[A](
  stream: ZStream[Any, Throwable, A],
  runtime: Runtime[Any],
  ctx: RunContext,
  release: Exit[Any, Any] => UIO[Any]
) extends Cursor[A] {

  import ZioCursor._

  def this(
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any],
    ctx: RunContext
  ) =
    this(stream, runtime, ctx, ZioCursor.NoRelease)

  private type Pull = ZIO[Any, Option[Throwable], Chunk[A]]
  private type Asked = scala.concurrent.Promise[Option[Chunk[A]]]

  // the hand-over between the consumer and the fiber: Idle | Demand (a chunk is asked for, the
  // fiber busy) | a Parked fiber (waiting to be asked) | Closed (cancelled, or the fiber ended)
  private val state = new AtomicReference[AnyRef](Idle)
  @volatile private var cancelled = false
  // how the run ended; written before `state` is Closed for it
  @volatile private var end: Try[Option[Chunk[A]]] = Stopped
  // the chunk asked for; written before the demand is signalled, completed once
  @volatile private var asked: Asked = _
  // the consumer's: written when a chunk is handed over, read by the next() after it
  private var buffer: Chunk[A] = Chunk.empty
  private var index = 0

  // on the fiber: until a chunk is asked for
  private val awaitDemand: UIO[Unit] =
    ZIO.suspendSucceed {
      if (state.compareAndSet(Demand, Idle)) ZIO.unit
      else ZIO.async[Any, Nothing, Unit](resume => park(new Parked(resume)))
    }

  @tailrec private def park(parked: Parked): Unit =
    state.get() match {
      case Demand =>
        if (state.compareAndSet(Demand, Idle)) parked.resume(ZIO.unit) else park(parked)
      case Idle =>
        if (!state.compareAndSet(Idle, parked)) park(parked)
      case _ =>
        () // closed by a cancel: the fiber is being interrupted
    }

  // on the fiber: a non-empty chunk per demand, until the stream ends
  private def serve(pull: Pull): ZIO[Any, Throwable, Unit] =
    (awaitDemand *> pull.repeatUntil(_.nonEmpty).unsome.map {
      case Some(chunk) =>
        asked.trySuccess(Some(chunk)) // lost against a cancel: the chunk is dropped
        true
      case None =>
        false
    }).repeatWhile(identity).unit

  // the run's fiber. Started last: it reads the fields above
  private val fiber: CancelableFuture[Try[Unit]] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(
        ZIO.uninterruptibleMask { restore =>
          for {
            scope <- Scope.make
            served <- restore(scope.extend[Any](stream.toPull).flatMap(serve)).exit
            closed <- scope.close(Exit.unit).exit
          } yield ended(served, closed)
        }
      )
    }

  // a fiber that ended without a result was interrupted before it started. A resource held
  // for the run (`release`) is released after the fiber ended - so never under a pull in
  // flight, and also when the fiber never started - and before the run's end is signalled
  fiber.onComplete { result =>
    val outcome = result.flatten
    if (release eq NoRelease) finish(outcome)
    else {
      val exit = outcome.fold(e => Exit.fail(e), _ => Exit.unit)
      Unsafe
        .unsafe(implicit unsafe => runtime.unsafe.runToFuture(release(exit)))
        .onComplete(_ => finish(outcome))(ctx.ec)
    }
  }(ctx.ec)

  // a failing finalizer fails a completed stream and is attached as suppressed to a failure
  private def ended(
    served: Exit[Throwable, Unit],
    closed: Exit[Nothing, Any]
  ): Try[Unit] =
    (served, closed) match {
      case (Exit.Failure(cause), Exit.Failure(finalizerCause)) =>
        val error = cause.squash
        val finalizerError = finalizerCause.squash
        if (finalizerError ne error) error.addSuppressed(finalizerError)
        Failure(error)
      case (Exit.Failure(cause), Exit.Success(_))          => Failure(cause.squash)
      case (Exit.Success(_), Exit.Failure(finalizerCause)) => Failure(finalizerCause.squash)
      case (Exit.Success(_), Exit.Success(_))              => Success(())
    }

  private def finish(result: Try[Unit]): Unit = {
    end = if (cancelled) Stopped else result.map(_ => None)
    state.set(Closed)
    // read after the state is Closed: a next() that missed it sees Closed itself
    val pending = asked
    if (pending != null) pending.tryComplete(end)
    ()
  }

  def next(): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else if (index < buffer.size) {
      val element = buffer(index)
      index += 1
      Future.successful(Some(element))
    } else {
      val demand: Asked = scala.concurrent.Promise[Option[Chunk[A]]]()
      asked = demand
      signal(demand)
      // on the run's ec, whoever completed it (the fiber, a cancel, the end)
      demand.future.map {
        case Some(chunk) =>
          buffer = chunk
          index = 1
          Some(chunk(0))
        case None =>
          None
      }(ctx.ec)
    }

  @tailrec private def signal(demand: Asked): Unit =
    state.get() match {
      case Idle =>
        if (!state.compareAndSet(Idle, Demand)) signal(demand)
      case parked: Parked =>
        if (state.compareAndSet(parked, Idle)) parked.resume(ZIO.unit) else signal(demand)
      case Closed =>
        demand.tryComplete(end)
        ()
      case _ =>
        () // Demand: one next() at a time
    }

  def cancel(): Unit = {
    cancelled = true
    if (state.getAndSet(Closed) ne Closed) {
      // read after the state is Closed, as in `finish`
      val pending = asked
      if (pending != null) pending.trySuccess(None)
      // a parked fiber is interrupted where it waits, a pull where it can be; the fiber then
      // closes the scope
      fiber.cancel()
      ()
    }
  }
}

private[wsclient] object ZioCursor {
  // no resource held for the run (every stream but a preflighted one)
  private val NoRelease: Exit[Any, Any] => UIO[Any] = _ => ZIO.unit

  private val Idle = new AnyRef
  private val Demand = new AnyRef
  private val Closed = new AnyRef

  // a run that was cancelled ends as a completed one
  private val Stopped: Try[Option[Nothing]] = Success(None)

  private final class Parked(val resume: UIO[Unit] => Unit)
}
