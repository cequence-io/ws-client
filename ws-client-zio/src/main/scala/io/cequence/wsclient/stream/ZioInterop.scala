package io.cequence.wsclient.stream

import io.cequence.wsclient.concurrent.{AsyncCancellable, AsyncScheduler}
import io.cequence.wsclient.stream.impl._
import zio._
import zio.stream.{ZPipeline, ZStream}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{Flow => JFlow}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * A ZIO stream kept native inside an [[AsyncStream]]: compiled for ZIO it is `stream` itself -
 * no bridge; any other consumer runs it on `runtime` (one bridge).
 */
private[wsclient] final class ZioLeaf[A] private[wsclient] (
  val stream: ZStream[Any, Throwable, A],
  runtime: Runtime[Any]
) extends NativeStream[A] {
  override def openPublisher(): JFlow.Publisher[_ <: A] = ZioBridges.publisher(stream, runtime)
  override def backend: String = "zio"
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

  /** A ZIO stream as a cold publisher: one run (on `runtime`) per subscription. */
  def publisher[A](
    stream: ZStream[Any, Throwable, A],
    runtime: Runtime[Any]
  ): JFlow.Publisher[A] = {
    val ec = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.executor.map(_.asExecutionContext)).getOrThrowFiberFailure()
    }
    new CursorPublisher[A](
      new Opener[A] {
        def open(ctx: RunContext): Cursor[A] = new ZioCursor(stream, runtime, ctx)
      },
      StreamRuntime.context(ec, BridgeCounter.Global)
    )
  }
}

/**
 * A run of a ZIO stream as a cursor: its pull in its own scope, chunks handed out element by
 * element; `cancel()` interrupts a pull in flight and closes the scope (the stream's
 * finalizers) once that pull has finished - a resource it acquired meanwhile is released too.
 * The end of the stream (or its failure) is signalled after the scope closed - its finalizers
 * have run, as for a native ZIO consumer.
 */
private[wsclient] final class ZioCursor[A](
  stream: ZStream[Any, Throwable, A],
  runtime: Runtime[Any],
  ctx: RunContext
) extends Cursor[A] {

  import ZioCursor._

  private type Pull = ZIO[Any, Option[Throwable], Chunk[A]]

  private val scope: Scope.Closeable =
    Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(Scope.make).getOrThrowFiberFailure())

  private val closed = new AtomicBoolean(false)
  private val closing = scala.concurrent.Promise[Unit]()
  // Idle | Starting (a pull being started) | the pull in flight (CancelableFuture) | Cancelled
  private val state = new AtomicReference[AnyRef](Idle)
  @volatile private var cancelled = false
  private var buffer: Chunk[A] = Chunk.empty
  private var index = 0

  private val pull: Future[Pull] = run(scope.extend[Any](stream.toPull))

  private def run[T](effect: ZIO[Any, Throwable, T]): CancelableFuture[T] =
    Unsafe.unsafe(implicit unsafe => runtime.unsafe.runToFuture(effect))

  def next(): Future[Option[A]] =
    if (cancelled)
      Cursors.NoneFuture
    else if (index < buffer.size) {
      val element = buffer(index)
      index += 1
      Future.successful(Some(element))
    } else
      pull.value match {
        case Some(result) => afterPull(result)
        case None         => pull.transformWith(afterPull)(ctx.ec)
      }

  private def afterPull(result: Try[Pull]): Future[Option[A]] =
    result match {
      case Success(p) => pullOnce(p)
      case Failure(e) => terminate(Failure(e))
    }

  private def pullOnce(p: Pull): Future[Option[A]] =
    // the pull is registered before it starts: a cancel either comes first (the stream is never
    // pulled) or sees it and closes the scope only after it finished
    if (!state.compareAndSet(Idle, Starting))
      Cursors.NoneFuture
    else {
      val result = run(p.either)
      if (!state.compareAndSet(Starting, result)) {
        // cancelled while starting: interrupt it, then close
        interruptThenClose(result)
        Cursors.NoneFuture
      } else
        result.transformWith { outcome =>
          if (!state.compareAndSet(result, Idle))
            Cursors.NoneFuture // cancelled meanwhile (the cancel closes the scope)
          else
            outcome match {
              case Success(Right(chunk)) =>
                if (chunk.isEmpty)
                  pullOnce(p)
                else {
                  buffer = chunk
                  index = 1
                  Future.successful(Some(chunk(0)))
                }
              case Success(Left(None))    => terminate(Success(None))
              case Success(Left(Some(e))) => terminate(Failure(e))
              case Failure(e)             => terminate(Failure(e))
            }
        }(ctx.ec)
    }

  // the end (or the failure) once the scope closed; a failing finalizer fails a completed
  // stream and is attached as suppressed to a failure
  private def terminate(end: Try[Option[A]]): Future[Option[A]] =
    close().transform { closeResult =>
      if (cancelled)
        Success(None)
      else
        (end, closeResult) match {
          case (Failure(e), Failure(finalizerError)) =>
            if (finalizerError ne e) e.addSuppressed(finalizerError)
            end
          case (Success(_), Failure(finalizerError)) =>
            Failure(finalizerError)
          case _ =>
            end
        }
    }(ctx.ec)

  private def close(): Future[Unit] = {
    if (closed.compareAndSet(false, true))
      closing.completeWith(run(scope.close(Exit.unit)))
    closing.future
  }

  private def interruptThenClose(inFlight: CancelableFuture[_]): Unit = {
    inFlight.cancel().onComplete(_ => close())(ctx.ec)
    ()
  }

  def cancel(): Unit = {
    cancelled = true
    var done = false
    while (!done)
      state.get() match {
        case Cancelled => done = true
        case Starting  =>
          // the puller sees the failed CAS and interrupts / closes
          done = state.compareAndSet(Starting, Cancelled)
        case Idle =>
          if (state.compareAndSet(Idle, Cancelled)) {
            close()
            done = true
          }
        case inFlight: CancelableFuture[_] @unchecked =>
          if (state.compareAndSet(inFlight, Cancelled)) {
            interruptThenClose(inFlight)
            done = true
          }
        case _ => done = true
      }
  }
}

private[wsclient] object ZioCursor {
  private val Idle = new AnyRef
  private val Starting = new AnyRef
  private val Cancelled = new AnyRef
}
