package play.api.libs.ws.ahc

import scala.concurrent.Future

/**
 * ws-client's access to Play WS 2.2.0-M2's package-private streaming path (see
 * `io.cequence.wsclient.service.ws.stream.PlayWSStreaming`): what `stream()` does in Play WS
 * 2.1.x and 3.x, without request filters (ws-client sets none).
 */
object StreamedExecution {

  def stream(request: StandaloneAhcWSRequest): Future[StreamedResponse] =
    request.client.executeStream(request.buildRequest())
}
