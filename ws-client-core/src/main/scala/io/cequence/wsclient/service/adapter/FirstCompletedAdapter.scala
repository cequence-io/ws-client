package io.cequence.wsclient.service.adapter

import com.typesafe.scalalogging.Logger
import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.stream.impl.Trampoline
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * Sends EVERY call to ALL underlying services at once and completes with the first to complete
 *   - its response or its failure (the semantics of `mapAsyncUnordered(n)` + `Sink.head`). A
 *     call that throws synchronously fails the call at once. The losing calls are not
 *     cancelled.
 */
private final class FirstCompletedAdapter[+S <: CloseableService](
  underlyings: Seq[S]
) extends ServiceWrapper[S]
    with CloseableService {

  private val logger = Logger(LoggerFactory.getLogger(this.getClass))

  override protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T] = {
    logger.debug(s"Running parallel/redundant processing with ${underlyings.size} services.")

    try {
      val calls = underlyings.map(fun)
      if (calls.isEmpty)
        Future.failed(new NoSuchElementException("parallelTakeFirst without services"))
      else
        Future.firstCompletedOf(calls)(Trampoline)
    } catch { case NonFatal(e) => Future.failed(e) }
  }

  override def close(): Unit =
    underlyings.foreach(_.close())
}
