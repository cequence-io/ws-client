package io.cequence.wsclient.service.ws

import akka.stream.Materializer
import io.cequence.wsclient.domain.{SiteBinding, WsRequestContext}
import io.cequence.wsclient.service.{
  WSClientEngine,
  WSClientInputStreamExtraAkka,
  WSClientWithEngineInputStreamingBase
}
import io.cequence.wsclient.service.spi.TransportSettings

import scala.concurrent.ExecutionContext

/**
 * A minimal, ad-hoc WS client for a single URL: binds one [[SiteBinding]] to a private, owned
 * [[PlayWSClientEngine]] and exposes the plain `WSClient` API (`execGET`, `execPOST`,
 * `execPOSTSource`, ...) directly - without the ceremony of defining a full service class.
 * `close()` closes the underlying engine.
 */
final class DirectWSClient(
  override protected val site: SiteBinding,
  override protected val engine: WSClientEngine with WSClientInputStreamExtraAkka
)(
  implicit executionContext: ExecutionContext
) extends WSClientWithEngineInputStreamingBase[
      WSClientEngine with WSClientInputStreamExtraAkka
    ] {
  protected type PEP = String
  protected type PT = String

  override protected implicit val ec: ExecutionContext = executionContext
}

object DirectWSClient {
  def apply(
    url: String,
    headers: Seq[(String, String)] = Nil,
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): DirectWSClient = {
    val finalURL = if (url.startsWith("http")) url else s"http://${url}"

    new DirectWSClient(
      SiteBinding(finalURL, WsRequestContext(authHeaders = headers)),
      PlayWSClientEngine(transportSettings)
    )
  }
}
