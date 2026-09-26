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

  /**
   * @param url
   *   base URL; without a scheme it defaults to `https://` (plain HTTP requires an explicit
   *   `http://`). Schemes other than http/https are rejected with an
   *   `IllegalArgumentException`.
   */
  def apply(
    url: String,
    headers: Seq[(String, String)] = Nil,
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): DirectWSClient =
    new DirectWSClient(
      SiteBinding(normalizeUrl(url), WsRequestContext(authHeaders = headers)),
      PlayWSClientEngine(transportSettings)
    )

  private val SchemePrefix = "^([a-zA-Z][a-zA-Z0-9+.-]*)://".r

  // the auth headers must never go out in plain text by accident - hence HTTPS by default.
  // Error messages deliberately omit the URL itself (it may carry credentials)
  private[ws] def normalizeUrl(url: String): String = {
    val trimmed = url.trim

    SchemePrefix.findPrefixMatchOf(trimmed) match {
      case Some(m) =>
        val scheme = m.group(1).toLowerCase
        if (scheme != "http" && scheme != "https")
          throw new IllegalArgumentException(
            s"Unsupported URL scheme '$scheme' - only http:// and https:// are allowed."
          )
        trimmed

      case None =>
        s"https://$trimmed"
    }
  }
}
