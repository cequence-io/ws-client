package io.cequence.wsclient.service.ws.stream

import play.api.libs.ws.ahc.{StandaloneAhcWSRequest, StreamedExecution}
import play.api.libs.ws.{StandaloneWSRequest, StandaloneWSResponse}

import scala.concurrent.Future

/**
 * Starts a streamed request. Play WS 2.2.0-M2 - the last Play WS built for Scala 3.2 - implements
 * `stream()` as the buffered `execute()`: the whole body arrives as one element once the response
 * has ended, and cancelling the stream cannot abort the request. Its client's streaming path,
 * which `stream()` uses in Play WS 2.1.x and 3.x, is called instead.
 */
private[stream] object PlayWSStreaming {

  def stream(request: StandaloneWSRequest): Future[StandaloneWSResponse] =
    request match {
      case ahcRequest: StandaloneAhcWSRequest => StreamedExecution.stream(ahcRequest)
      case other                              => other.stream()
    }
}
