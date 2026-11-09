package io.cequence.wsclient.concurrent

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/**
 * Timer-based `Future` helpers on an [[AsyncScheduler]] (the default one unless one is in
 * scope).
 */
object Futures {

  /**
   * `f` (by-name) evaluated after `delay` - like `akka.pattern.after`. If the scheduler cannot
   * take the delay, the returned future fails with its exception.
   */
  def after[T](
    delay: FiniteDuration
  )(
    f: => Future[T]
  )(
    implicit scheduler: AsyncScheduler,
    ec: ExecutionContext
  ): Future[T] = {
    val promise = Promise[T]()
    try
      scheduler.scheduleOnce(delay) { () =>
        // hop off the scheduler thread before running user code; an ec that rejects the task
        // (shut down meanwhile) fails the future - the scheduler would swallow the exception
        try
          ec.execute(new Runnable {
            override def run(): Unit =
              promise.completeWith(
                try f
                catch { case NonFatal(e) => Future.failed(e) }
              )
          })
        catch { case NonFatal(e) => promise.tryFailure(e) }
      }
    catch { case NonFatal(e) => promise.tryFailure(e) }
    promise.future
  }

  /** Completes after `delay`. */
  def delay(
    delay: FiniteDuration
  )(
    implicit scheduler: AsyncScheduler
  ): Future[Unit] = {
    val promise = Promise[Unit]()
    try scheduler.scheduleOnce(delay)(() => promise.trySuccess(()))
    catch { case NonFatal(e) => promise.tryFailure(e) }
    promise.future
  }

  /**
   * `f`, or a `TimeoutException` if it has not completed within `limit`. `f` itself is not
   * cancelled (a `Future` cannot be). If the scheduler cannot take `limit`, `f` is returned
   * unbounded.
   */
  def timeout[T](
    f: Future[T],
    limit: FiniteDuration
  )(
    implicit scheduler: AsyncScheduler,
    ec: ExecutionContext
  ): Future[T] =
    bounded(f, limit, new TimeoutException(s"Future timed out after $limit"))

  /**
   * `f`, or a failure with `error` (by-name, evaluated when the limit passes; a throw becomes
   * the failure) if it has not completed within `limit` - otherwise as the two-argument
   * `timeout`.
   */
  def timeout[T](
    f: Future[T],
    limit: FiniteDuration,
    error: => Throwable
  )(
    implicit scheduler: AsyncScheduler,
    ec: ExecutionContext
  ): Future[T] =
    bounded(f, limit, error)

  private def bounded[T](
    f: Future[T],
    limit: FiniteDuration,
    error: => Throwable
  )(
    implicit scheduler: AsyncScheduler,
    ec: ExecutionContext
  ): Future[T] =
    if (f.isCompleted)
      f
    else {
      val promise = Promise[T]()
      val timer =
        try
          Some(scheduler.scheduleOnce(limit) { () =>
            if (!promise.isCompleted) {
              val failure =
                try {
                  val e = error
                  if (e == null) new TimeoutException(s"Future timed out after $limit") else e
                } catch { case NonFatal(e) => e }
              promise.tryFailure(failure)
            }
          })
        catch { case NonFatal(_) => None }

      timer match {
        case Some(cancellable) =>
          f.onComplete { result =>
            cancellable.cancel()
            promise.tryComplete(result)
          }
          promise.future
        case None =>
          f
      }
    }
}
