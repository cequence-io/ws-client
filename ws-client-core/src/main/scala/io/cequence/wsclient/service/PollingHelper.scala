package io.cequence.wsclient.service

import akka.actor.Scheduler
import akka.pattern.after

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

trait PollingHelper {

  protected val pollingMs = 200

  protected def pollUntilDone[T](
    isDone: T => Boolean
  )(
    call: => Future[T]
  )(
    implicit ec: ExecutionContext, scheduler: Scheduler
  ): Future[T] =
    call.flatMap { result =>
      if (isDone(result)) {
        Future.successful(result)
      } else {
        // Use `akka.pattern.after` to schedule a future that will retry after pollingMs
        after(pollingMs.millis, scheduler)(
          pollUntilDone(isDone)(call)
        )
      }
    }
}
