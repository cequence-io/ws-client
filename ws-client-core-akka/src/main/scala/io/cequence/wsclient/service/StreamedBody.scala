package io.cequence.wsclient.service

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The stream of a response body that arrives with the response head: the future's source, once
 * it is there - and a body that arrives too late is still released.
 *
 * `Source.futureSource` alone drops a source whose future completes after the run ended (the
 * downstream cancelled before the head arrived, or the stream was stopped abruptly): the
 * source is never run, so the response is never read and its request never aborted - the
 * connection stays busy until the server ends the body or a transport timeout fires. Here such
 * a source is run and cancelled at once, which aborts the request as a cancel after the head
 * does.
 */
private[wsclient] object StreamedBody {

  def of[T](
    body: Future[Source[T, Any]]
  )(
    implicit materializer: Materializer
  ): Source[T, NotUsed] =
    Source.futureSource(body).mapMaterializedValue { materialized =>
      implicit val ec: ExecutionContext = materializer.executionContext
      // fails when the run ended before `body` completed (or with `body`: nothing to release)
      materialized.failed.foreach { _ =>
        body.foreach { unrun =>
          // a materializer that shut down meanwhile took the connection with it
          try unrun.runWith(Sink.cancelled)
          catch { case NonFatal(_) => () }
        }
      }
      NotUsed
    }
}
