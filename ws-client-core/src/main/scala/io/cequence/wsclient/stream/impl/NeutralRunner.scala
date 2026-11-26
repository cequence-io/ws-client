package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.stream.{AsyncStream, StreamRun}

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** The run methods of [[AsyncStream]] on the neutral interpreter. */
private[wsclient] object NeutralRunner {

  // a synchronous run yields its thread back to the ec after this many elements (fairness)
  private val YieldEvery = 1024

  def runFold[A, S](
    stream: AsyncStream[A],
    zero: S
  )(
    f: (S, A) => S
  )(
    implicit ec: ExecutionContext
  ): Future[S] = {
    val promise = Promise[S]()
    new FoldLoop[A, S](stream, zero, f, promise, StreamRuntime.context(ec), headOnly = false)
      .start()
    promise.future
  }

  /** A fold run that can be cancelled (a cancelled run completes with what it has folded). */
  def startFold[A, S](
    stream: AsyncStream[A],
    zero: S
  )(
    f: (S, A) => S
  )(
    ctx: RunContext
  ): FoldRun[S] = {
    val promise = Promise[S]()
    val loop = new FoldLoop[A, S](stream, zero, f, promise, ctx, headOnly = false)
    loop.start()
    new FoldRun[S] {
      override val result: Future[S] = promise.future
      override def cancel(): Unit = loop.cancel()
    }
  }

  private[wsclient] trait FoldRun[S] {
    def result: Future[S]
    def cancel(): Unit
  }

  def runHeadOption[A](
    stream: AsyncStream[A]
  )(
    implicit ec: ExecutionContext
  ): Future[Option[A]] = {
    val promise = Promise[Option[A]]()
    new FoldLoop[A, Option[A]](
      stream,
      None,
      (
        _,
        a
      ) => Some(a),
      promise,
      StreamRuntime.context(ec),
      headOnly = true
    ).start()
    promise.future
  }

  def run[A](
    stream: AsyncStream[A],
    f: A => Unit
  )(
    implicit ec: ExecutionContext
  ): StreamRun = {
    val promise = Promise[Unit]()
    val loop = new FoldLoop[A, Unit](
      stream,
      (),
      (
        _,
        a
      ) => f(a),
      promise,
      StreamRuntime.context(ec),
      headOnly = false
    )
    loop.start()

    new StreamRun {
      override val done: Future[Unit] = promise.future
      override def cancel(): Unit = loop.cancel()
    }
  }

  def preflight[A](
    stream: AsyncStream[A]
  )(
    implicit ec: ExecutionContext
  ): Future[AsyncStream[A]] =
    startPreflight(stream, StreamRuntime.context(ec)).result

  /**
   * A preflight that can be cancelled: cancelling it before it completed cancels the run
   * (which aborts the request), and a stream that arrives after the cancel is cancelled on
   * arrival - nothing is held. After it completed, the stream is the caller's.
   */
  def startPreflight[A](
    stream: AsyncStream[A],
    ctx: RunContext
  ): PreflightRun[A] = {
    val promise = Promise[AsyncStream[A]]()
    val cancelled = new AtomicBoolean(false)
    val opened = new AtomicReference[Cursor[A]]()

    // the cursor is cancelled once, by whoever sees both the cancel and the cursor
    def cancelOpened(): Unit = {
      val cursor = opened.getAndSet(null)
      if (cursor != null) cursor.cancel()
    }

    // a cancel that came while this was queued sends no request at all
    execute(ctx.ec, promise) {
      if (!cancelled.get()) {
        val cursor = Cursors.open(stream, ctx)
        opened.set(cursor)
        if (cancelled.get()) cancelOpened() // the cancel came while the stream opened
        else
          Cursors
            .pull(cursor)
            .onComplete {
              case Success(first) =>
                if (cancelled.get()) {
                  cancelOpened()
                  promise.tryFailure(new CancellationException("preflight cancelled"))
                } else {
                  opened.set(null) // handed over
                  // a cancel between the check and here failed the promise first: the stream
                  // nobody got is cancelled (`Cursor.cancel` is idempotent)
                  if (!promise.trySuccess(singleShot(first, cursor))) cursor.cancel()
                }
                ()
              case Failure(e) =>
                promise.tryFailure(e)
                ()
            }(Trampoline)
      }
    }

    new PreflightRun[A] {
      override val result: Future[AsyncStream[A]] = promise.future
      override def cancel(): Unit = {
        cancelled.set(true)
        cancelOpened()
        promise.tryFailure(new CancellationException("preflight cancelled"))
        ()
      }
    }
  }

  private[wsclient] trait PreflightRun[A] {
    def result: Future[AsyncStream[A]]
    def cancel(): Unit
  }

  // the rest of a preflighted run: replays its first result, then continues the same cursor
  private def singleShot[A](
    first: Option[A],
    cursor: Cursor[A]
  ): AsyncStream[A] = {
    val used = new AtomicBoolean(false)
    FromCursor[A](
      _ =>
        if (used.compareAndSet(false, true))
          new ReplayCursor(first, cursor)
        else
          new FailedCursor(NativePreflight.singleRunOnly()),
      "preflight"
    )
  }

  private final class ReplayCursor[A](
    first: Option[A],
    underlying: Cursor[A]
  ) extends Cursor[A] {
    private var replayed = false

    def next(): Future[Option[A]] =
      if (!replayed) {
        replayed = true
        Future.successful(first) // a None first result is terminal: no further next()
      } else
        underlying.next()

    def cancel(): Unit = underlying.cancel()
  }

  private def execute(
    ec: ExecutionContext,
    promise: Promise[_]
  )(
    body: => Unit
  ): Unit =
    try
      ec.execute(new Runnable {
        def run(): Unit =
          try body
          catch { case NonFatal(e) => promise.tryFailure(e) }
      })
    catch { case NonFatal(e) => promise.tryFailure(e) }

  /**
   * Drives a cursor to its end, folding the elements. It starts on the ec (the caller's thread
   * returns at once); already-completed results are consumed synchronously, yielding the
   * thread every `YieldEvery` elements.
   */
  private final class FoldLoop[A, S](
    stream: AsyncStream[A],
    zero: S,
    f: (S, A) => S,
    promise: Promise[S],
    ctx: RunContext,
    headOnly: Boolean
  ) {
    @volatile private var cursor: Cursor[A] = _
    @volatile private var cancelRequested = false
    private var acc = zero

    def start(): Unit =
      execute(ctx.ec, promise) {
        val opened = Cursors.open(stream, ctx)
        cursor = opened
        if (cancelRequested) {
          opened.cancel()
          promise.trySuccess(acc)
        } else
          loop()
      }

    def cancel(): Unit = {
      cancelRequested = true
      val current = cursor
      // the outstanding next() completes with None, which ends the loop
      if (current != null) current.cancel()
    }

    private def loop(): Unit = {
      var steps = 0
      var continue = true
      while (continue) {
        if (cancelRequested) {
          promise.trySuccess(acc)
          return
        }
        val future = Cursors.pull(cursor)
        future.value match {
          case Some(result) =>
            continue = handle(result)
            steps += 1
            if (continue && steps >= YieldEvery) {
              continue = false
              execute(ctx.ec, promise)(loop())
            }
          case None =>
            continue = false
            future.onComplete { result =>
              if (handle(result)) loop()
            }(Trampoline)
        }
      }
    }

    // true: pull the next element
    private def handle(result: Try[Option[A]]): Boolean =
      result match {
        case Success(Some(_)) if cancelRequested =>
          // an element that raced the cancel is dropped (best effort: an f that already
          // started still finishes, and the run completes after it)
          promise.trySuccess(acc)
          false
        case Success(Some(element)) =>
          try {
            acc = f(acc, element)
            if (headOnly) {
              cursor.cancel()
              promise.trySuccess(acc)
              false
            } else
              true
          } catch {
            case NonFatal(e) =>
              cursor.cancel()
              promise.tryFailure(e)
              false
          }
        case Success(None) =>
          promise.trySuccess(acc)
          false
        case Failure(e) =>
          promise.tryFailure(e)
          false
      }
  }
}
