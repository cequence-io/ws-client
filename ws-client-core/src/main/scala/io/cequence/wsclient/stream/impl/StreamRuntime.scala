package io.cequence.wsclient.stream.impl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

/** The neutral interpreter's shared runtime: the blocking pool and run contexts. */
private[wsclient] object StreamRuntime {

  /**
   * The pool for blocking reads (`fromInputStream`, `fromBlockingIterator`): cached DAEMON
   * threads ("ws-client-blocking-N", idle ones exit after 60 s), so a blocking read never
   * starves the caller's `ExecutionContext` and a leaked stream cannot block JVM exit.
   */
  lazy val blockingEc: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool(new ThreadFactory {
        private val counter = new AtomicInteger()
        override def newThread(runnable: Runnable): Thread = {
          val thread = new Thread(runnable, s"ws-client-blocking-${counter.incrementAndGet()}")
          thread.setDaemon(true)
          thread
        }
      })
    )

  /** A run's context; its bridge counter is the current scope's (read now, on this thread). */
  def context(ec: ExecutionContext): RunContext =
    new RunContext(ec, blockingEc, BridgeCounter.current())

  def context(
    ec: ExecutionContext,
    bridges: BridgeCounter
  ): RunContext =
    new RunContext(ec, blockingEc, bridges)
}
