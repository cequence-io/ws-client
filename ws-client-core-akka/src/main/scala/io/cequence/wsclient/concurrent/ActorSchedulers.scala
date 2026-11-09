package io.cequence.wsclient.concurrent

import akka.stream.Materializer
import io.cequence.wsclient.stream.impl.Trampoline

import scala.concurrent.duration.FiniteDuration

/**
 * Adapts an actor system's timer to the backend-neutral [[AsyncScheduler]] (since 1.2.0):
 * `import io.cequence.wsclient.concurrent.ActorSchedulers._` makes an implicit
 * `akka.actor.Scheduler` in scope serve wherever an [[AsyncScheduler]] is expected (lexical
 * scope beats the default in `AsyncScheduler`'s companion).
 */
object ActorSchedulers {

  /** The implicit actor scheduler in scope, as an [[AsyncScheduler]]. */
  implicit def fromActorScheduler(
    implicit scheduler: akka.actor.Scheduler
  ): AsyncScheduler =
    new AsyncScheduler {
      override def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable =
        cancellable(
          scheduler.scheduleOnce(delay, new Runnable { override def run(): Unit = task() })(
            Trampoline
          )
        )
    }

  /** A materializer's timer as an [[AsyncScheduler]]. */
  def fromMaterializer(materializer: Materializer): AsyncScheduler =
    new AsyncScheduler {
      override def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable =
        cancellable(
          materializer.scheduleOnce(delay, new Runnable { override def run(): Unit = task() })
        )
    }

  private def cancellable(underlying: akka.actor.Cancellable): AsyncCancellable =
    new AsyncCancellable {
      override def cancel(): Boolean = underlying.cancel()
      override def isCancelled: Boolean = underlying.isCancelled
    }
}
