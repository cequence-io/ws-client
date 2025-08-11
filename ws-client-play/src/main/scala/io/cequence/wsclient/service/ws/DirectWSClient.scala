package io.cequence.wsclient.service.ws

import akka.stream.Materializer
import io.cequence.wsclient.domain.WsRequestContext
import io.cequence.wsclient.service.{WSClient, WSClientEngine}

import scala.concurrent.ExecutionContext

object DirectWSClient {
  def apply(
    url: String,
    headers: Seq[(String, String)] = Nil,
    timeouts: Option[Timeouts] = None
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): WSClientEngine = {
    val finalURL = if (url.startsWith("http")) url else s"http://${url}"

    PlayWSClientEngine(
      finalURL,
      WsRequestContext(authHeaders = headers, explTimeouts = timeouts)
    )
  }
}
