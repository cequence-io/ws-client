package io.cequence.wsclient.service.adapter

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.Logger
import io.cequence.wsclient.service.CloseableService
import org.slf4j.LoggerFactory

import scala.concurrent.Future

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

  override def close(): Unit =
    underlyings.foreach(_.close())
}
