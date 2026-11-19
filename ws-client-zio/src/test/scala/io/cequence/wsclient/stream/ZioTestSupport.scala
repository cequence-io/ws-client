package io.cequence.wsclient.stream

import zio.{Runtime, Unsafe, ZIO}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Shared helpers of the ZIO interop specs. */
trait ZioTestSupport {

  implicit protected val runtime: Runtime[Any] = Runtime.default

  /** Runs `effect` to its value (or throws its failure) - for at most 30 seconds. */
  protected def unsafeRun[T](effect: ZIO[Any, Throwable, T]): T =
    Unsafe.unsafe { implicit unsafe =>
      Await.result(runtime.unsafe.runToFuture(effect), 30.seconds)
    }

  /** Retries `assertion` until it holds - for at most 10 seconds, then its failure. */
  protected def eventually(assertion: => Any): Unit = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    var done = false
    while (!done)
      try {
        assertion
        done = true
      } catch {
        case NonFatal(_) | _: AssertionError if System.nanoTime() < deadline =>
          Thread.sleep(10)
      }
  }
}
