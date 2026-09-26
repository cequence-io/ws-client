package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.CequenceWSException

import java.net.URI

private[wsclient] object ProxyUrlUtil {

  /**
   * Parses `TransportSettings.proxyURL` - accepted forms are "host:port" and
   * "scheme://host:port" (any path/query is ignored; credentials in the URL are NOT supported
   * and ignored). The port is required: proxies have no conventional default, so guessing one
   * would misroute traffic silently. Error messages never echo the URL itself - it may carry
   * credentials.
   */
  def hostAndPort(proxyUrl: String): (String, Int) = {
    val uri =
      parse(proxyUrl).getOrElse(
        throw new CequenceWSException(
          "Cannot parse the proxy URL - expected \"host:port\" or \"scheme://host:port\"."
        )
      )

    val host = Option(uri.getHost).getOrElse(
      throw new CequenceWSException(
        "Cannot parse a host from the proxy URL - expected \"host:port\" or \"scheme://host:port\"."
      )
    )
    val port = uri.getPort
    if (port < 0)
      throw new CequenceWSException(s"The proxy URL for host '$host' must include a port.")

    (host, port)
  }

  /**
   * A log- and error-safe rendering of a proxy URL: host and port only - never credentials
   * (user-info), path or query.
   */
  def redacted(proxyUrl: String): String =
    parse(proxyUrl)
      .flatMap(uri =>
        Option(uri.getHost)
          .map(host => if (uri.getPort >= 0) s"$host:${uri.getPort}" else host)
      )
      .getOrElse("<unparseable proxy URL>")

  private def parse(proxyUrl: String): Option[URI] = {
    val withScheme = if (proxyUrl.contains("://")) proxyUrl else s"http://$proxyUrl"
    try Some(URI.create(withScheme))
    catch { case _: IllegalArgumentException => None }
  }
}
