package io.cequence.wsclient.service.adapter

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.Logger
import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.stream.AsyncCall
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
 * Sends EVERY call to ALL underlying services at once and completes with the first response.
 *
 * Privacy and cost: the complete request - body, user data, and each service's own credentials
 * \- reaches every configured provider, and the losing calls are NOT cancelled (the engines
 * are Future-based): they run to completion and are billed by their providers. Only combine
 * providers that may all see the data. The call twins (`wrapCall`, since 1.3.0) are the race
 * of the Akka-free `parallelTakeFirst`: the first outcome wins and the losers are cancelled.
 */
private final class ParallelTakeFirstAdapter[+S <: CloseableService](
  underlyings: Seq[S]
)(
  implicit materializer: Materializer
) extends ServiceWrapper[S]
    with CloseableService {

  private val logger = Logger(LoggerFactory.getLogger(this.getClass))

  override protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T] = {
    logger.debug(s"Running parallel/redundant processing with ${underlyings.size} services.")

    val sources = Source
      .fromIterator(() => underlyings.toIterator)
      .mapAsyncUnordered(underlyings.size)(fun)

    sources.runWith(Sink.head)
  }

  // the calls need no materializer: the same race as the Akka-free variant
  private val calls = new FirstCompletedAdapter(underlyings)

  override protected[adapter] def wrapCall[T](
    fun: S => AsyncCall[T]
  ): AsyncCall[T] =
    calls.wrapCall(fun)

  override def close(): Unit =
    underlyings.foreach(_.close())
}
