package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.CequenceWSException

import java.net.URI

private[wsclient] object ProxyUrlUtil {

  /**
   * Parses `TransportSettings.proxyURL` - accepted forms are "host:port" and
   * "scheme://host:port" (any path/query is ignored). The port is required: proxies have no
   * conventional default, so guessing one would misroute traffic silently.
   */
  def hostAndPort(proxyUrl: String): (String, Int) = {
    val withScheme = if (proxyUrl.contains("://")) proxyUrl else s"http://$proxyUrl"

    val uri =
      try URI.create(withScheme)
      catch {
        case e: IllegalArgumentException =>
          throw new CequenceWSException(
            s"Cannot parse the proxy URL '$proxyUrl': ${e.getMessage}"
          )
      }

    val host = Option(uri.getHost).getOrElse(
      throw new CequenceWSException(s"Cannot parse a host from the proxy URL '$proxyUrl'.")
    )
    val port = uri.getPort
    if (port < 0)
      throw new CequenceWSException(s"The proxy URL '$proxyUrl' must include a port.")

    (host, port)
  }
}
