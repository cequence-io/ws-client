package io.cequence.wsclient.service

import io.cequence.wsclient.concurrent.{Futures, AsyncScheduler}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
 * Polling on the backend-neutral [[io.cequence.wsclient.concurrent.AsyncScheduler]] (the
 * default one unless one is in scope) - the Akka-free counterpart of ws-client-core-akka's
 * `PollingHelper` (since 1.2.0). Mix in one of the two, never both.
 */
trait PollingHelperCore {

  protected val pollingMs = 200

  /** Calls `call` every `pollingMs` until its result satisfies `isDone`. */
  protected def pollUntilDone[T](
    isDone: T => Boolean
  )(
    call: => Future[T]
  )(
    implicit ec: ExecutionContext,
    scheduler: AsyncScheduler
  ): Future[T] =
    call.flatMap { result =>
      if (isDone(result))
        Future.successful(result)
      else
        Futures.after(pollingMs.millis)(pollUntilDone(isDone)(call))
    }
}
