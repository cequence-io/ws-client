package io.cequence.wsclient.stream.impl

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.util.ArrayDeque
import scala.concurrent.ExecutionContextExecutor
import scala.util.control.NonFatal

/**
 * A same-thread executor for the interpreter's internal continuations: a task runs on the
 * calling thread, and tasks submitted while one is running are queued on a thread-local queue
 * and run after it (like 2.13's `BatchingExecutor`) - so chains of future callbacks never grow
 * the stack. One implementation for 2.12 (which has no `ExecutionContext.parasitic`), 2.13 and
 * 3.
 *
 * Only for short, non-blocking continuations: user code reaches it only after a hop to the
 * run's `ExecutionContext`.
 */
private[wsclient] object Trampoline extends ExecutionContextExecutor {

  private val logger = Logger(LoggerFactory.getLogger(getClass))

  private val queues = new ThreadLocal[ArrayDeque[Runnable]]

  override def execute(task: Runnable): Unit = {
    val queue = queues.get()

    if (queue != null)
      queue.addLast(task)
    else {
      val newQueue = new ArrayDeque[Runnable]()
      queues.set(newQueue)
      try {
        var next = task
        while (next != null) {
          try next.run()
          catch { case NonFatal(e) => reportFailure(e) }
          next = newQueue.pollFirst()
        }
      } finally queues.remove()
    }
  }

  override def reportFailure(cause: Throwable): Unit =
    logger.error("Uncaught failure in a stream continuation", cause)
}
