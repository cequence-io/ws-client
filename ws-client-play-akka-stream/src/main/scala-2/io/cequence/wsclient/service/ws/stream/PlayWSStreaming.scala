package io.cequence.wsclient.service.ws.stream

import play.api.libs.ws.{StandaloneWSRequest, StandaloneWSResponse}

import scala.concurrent.Future

/**
 * Starts a streamed request. Play WS 2.1.x (Scala 2.12 / 2.13) and 3.x deliver the body
 * incrementally from `stream()` itself, and cancelling its `bodyAsSource` aborts the request.
 */
private[stream] object PlayWSStreaming {

  def stream(request: StandaloneWSRequest): Future[StandaloneWSResponse] =
    request.stream()
}
