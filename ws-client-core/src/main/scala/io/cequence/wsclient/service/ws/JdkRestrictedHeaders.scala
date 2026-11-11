package io.cequence.wsclient.service.ws

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The request headers java.net.http (the jdk engine, and the sttp engine on its own backend)
 * derives itself and refuses from a caller: `Host`, `Connection`, `Content-Length`, `Expect`,
 * `Upgrade`. Before 1.2.0 such a header failed the call with an `IllegalArgumentException`
 * ("restricted header name"); now it is dropped, logged once per name.
 *
 * Whether the running JDK refuses one is asked once per name (`HttpRequest.Builder.header`),
 * so `-Djdk.httpclient.allowRestrictedHeaders` and `conf/net.properties` apply exactly. Any
 * other refusal still fails the call: JDK 11 before 11.0.14 also restricts `Referer`,
 * `Origin`, `Date`, `From`, `Via` and `Warning`, which the client does not set itself -
 * dropping those silently (e.g. the `Referer` a referrer-restricted API key needs) would turn
 * a clear error into a confusing 403. Loaded only by the java.net.http-based engines (core
 * also runs on Java 8).
 */
private[wsclient] object JdkRestrictedHeaders {

  private val logger = Logger(LoggerFactory.getLogger(getClass))

  // the headers java.net.http sets itself
  private val DerivedByClient =
    Set("host", "connection", "content-length", "expect", "upgrade")

  private val restricted = new ConcurrentHashMap[String, java.lang.Boolean]()
  private val warned = ConcurrentHashMap.newKeySet[String]()

  /** True for a header java.net.http derives itself and the running JDK refuses. */
  def isRestricted(name: String): Boolean = {
    val lowerCaseName = name.toLowerCase(Locale.ROOT)
    DerivedByClient.contains(lowerCaseName) && refusedByJdk(lowerCaseName)
  }

  private def refusedByJdk(lowerCaseName: String): Boolean =
    restricted.computeIfAbsent(
      lowerCaseName,
      key =>
        try {
          java.net.http.HttpRequest.newBuilder().header(key, "x")
          java.lang.Boolean.FALSE
        } catch {
          // "restricted header name" - any other refusal (an invalid name) fails the call as before
          case e: IllegalArgumentException =>
            java.lang.Boolean.valueOf(Option(e.getMessage).exists(_.contains("restricted")))
        }
    )

  /** `headers` without the ones java.net.http refuses. */
  def filter(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter { case (name, _) =>
      val drop = isRestricted(name)
      if (drop && warned.add(name.toLowerCase(Locale.ROOT)))
        logger.warn(
          s"Dropping the caller-set header '$name': java.net.http sets it itself and refuses " +
            "it from a caller (see jdk.httpclient.allowRestrictedHeaders)"
        )
      !drop
    }
}
