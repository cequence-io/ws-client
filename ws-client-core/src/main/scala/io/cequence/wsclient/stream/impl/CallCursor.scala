package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.stream.{AsyncCall, CallRun}

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * `AsyncStream.fromCall` on the neutral interpreter: the call starts when the run opens (as
 * `futureStream`'s future does), its value is the stream's only element, and cancelling the
 * run cancels the call.
 */
private[wsclient] final class CallCursor[A](
  call: AsyncCall[A],
  ctx: RunContext
) extends Cursor[A] {

  @volatile private var cancelled = false
  @volatile private var delivered = false
  @volatile private var pending: Promise[Option[A]] = _

  private val started: Either[Throwable, CallRun[A]] =
    try Right(BridgeCounter.withCounter(ctx.bridges)(CallRunner.start(call, ctx.ec)))
    catch { case NonFatal(e) => Left(e) }

  def next(): Future[Option[A]] =
    if (cancelled || delivered)
      Cursors.NoneFuture
    else {
      delivered = true
      started match {
        case Left(e) => Future.failed(e)
        case Right(run) =>
          val promise = Promise[Option[A]]()
          pending = promise
          if (cancelled) promise.trySuccess(None)
          run.result.onComplete {
            case Success(value) => promise.trySuccess(Some(value))
            // the run a cancel stopped fails with its own cancellation: the stream just ends
            case Failure(e) =>
              if (cancelled) promise.trySuccess(None) else promise.tryFailure(e)
          }(Trampoline)
          promise.future
      }
    }

  def cancel(): Unit = {
    cancelled = true
    // the outstanding next() ends first: the run's cancellation must not reach it as a failure
    val promise = pending
    if (promise != null) promise.trySuccess(None)
    started.foreach(_.cancel())
  }
}
