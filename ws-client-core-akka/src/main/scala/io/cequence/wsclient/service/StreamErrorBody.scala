package io.cequence.wsclient.service

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import io.cequence.wsclient.service.ws.EngineSupport

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * The body of a non-2xx STREAMING response, read only for the error message: at most
 * `EngineSupport.MaxErrorBodyBytes`, within `within` - so a huge or never-ending error body is
 * neither buffered nor able to stall the stream. Never fails: a failed read yields "" (it must
 * not mask the HTTP status).
 */
private[wsclient] object StreamErrorBody {

  def read(
    body: Source[ByteString, Any],
    within: FiniteDuration
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): Future[String] =
    body
      .takeWithin(within)
      .scan(ByteString.empty)(
        (
          acc,
          chunk
        ) => acc ++ chunk
      )
      .takeWhile(_.size < EngineSupport.MaxErrorBodyBytes, inclusive = true)
      .runWith(Sink.last)
      .map(_.take(EngineSupport.MaxErrorBodyBytes).utf8String)
      .recover { case _ => "" }
}
