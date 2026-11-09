package io.cequence.wsclient.concurrent

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{
  ScheduledExecutorService,
  ScheduledFuture,
  ScheduledThreadPoolExecutor,
  ThreadFactory,
  TimeUnit
}
import scala.concurrent.duration.FiniteDuration

/** A scheduled task that can be cancelled. */
trait AsyncCancellable {

  /** Cancels the task if it has not run yet; true if this call cancelled it. */
  def cancel(): Boolean

  def isCancelled: Boolean
}

/**
 * A timer for delayed tasks (retries, polling, timeouts) - the backend-neutral counterpart of
 * `akka.actor.Scheduler`. The default is in the companion's implicit scope, so callers need no
 * configuration; an implicit `AsyncScheduler` in lexical scope (e.g. one adapting an actor
 * system's scheduler, see `ActorSchedulers` in ws-client-core-akka / -pekko) takes precedence.
 */
trait AsyncScheduler {

  /**
   * Runs `task` once after `delay`. The task must be short and non-blocking (it runs on the
   * scheduler's thread). May throw (e.g. for a delay the scheduler cannot take) - callers that
   * need to, fall back.
   */
  def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable
}

object AsyncScheduler {

  /**
   * The default scheduler: one DAEMON thread ("ws-client-scheduler") with remove-on-cancel, so
   * a cancelled task leaves nothing behind and the timer never blocks JVM exit.
   */
  implicit lazy val default: AsyncScheduler = {
    val executor = new ScheduledThreadPoolExecutor(
      1,
      new ThreadFactory {
        private val counter = new AtomicInteger()
        override def newThread(runnable: Runnable): Thread = {
          val suffix = counter.incrementAndGet()
          val thread = new Thread(
            runnable,
            if (suffix == 1) "ws-client-scheduler" else s"ws-client-scheduler-$suffix"
          )
          thread.setDaemon(true)
          thread
        }
      }
    )
    executor.setRemoveOnCancelPolicy(true)
    fromExecutor(executor)
  }

  /** A scheduler on a caller-owned executor (never shut down by this library). */
  def fromExecutor(executor: ScheduledExecutorService): AsyncScheduler =
    new AsyncScheduler {
      override def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable = {
        val runnable = new Runnable {
          override def run(): Unit = task()
        }
        val scheduled: ScheduledFuture[_] =
          executor.schedule(runnable, delay.toNanos, TimeUnit.NANOSECONDS)

        new AsyncCancellable {
          private val cancelled = new AtomicBoolean(false)

          override def cancel(): Boolean =
            scheduled.cancel(false) && cancelled.compareAndSet(false, true)

          override def isCancelled: Boolean = cancelled.get()
        }
      }
    }
}
