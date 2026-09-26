package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.{RichResponse, SiteBinding}
import io.cequence.wsclient.service.WSClientEngine

import scala.collection.immutable.ListMap

/**
 * Shared scaffolding of the engines built directly on a backend client (jdk, sttp, pekko-http,
 * zio-http): logging labels and the composition of a site's error recovery over the engine's
 * default transport-failure normalization. The Play engines predate it and keep their own
 * (protected, released) members.
 */
private[wsclient] trait EngineSupport { self: WSClientEngine =>

  /**
   * Normalizes this backend's raw transport failures into the Cequence exception taxonomy
   * (typically by throwing a `CequenceWSTimeoutException` / `CequenceWSUnknownHostException`).
   */
  protected def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse]

  protected def serviceName(site: SiteBinding): String =
    site.label.getOrElse(getClass.getSimpleName)

  protected def serviceAndEndpoint(
    site: SiteBinding,
    endPointForLogging: Option[String]
  ): String =
    s"${serviceName(site)}${endPointForLogging.map("." + _).getOrElse("")}"

  // composes the site's custom error recovery (if any) over the engine's default
  // transport-failure normalization - see SiteBinding.resolveRecoverErrors
  protected def recoverErrors(
    site: SiteBinding
  ): String => PartialFunction[Throwable, RichResponse] =
    SiteBinding.resolveRecoverErrors(site.recoverErrors, defaultRecoverErrors)
}

private[wsclient] object EngineSupport {

  val DefaultRequestTimeoutMs: Int = 120 * 1000 // two minutes
  val DefaultConnectTimeoutMs: Int = 5 * 1000 // the Play engines' AHC default

  // a non-2xx STREAMING response fails the stream; its body is read only for the error
  // message - at most this many bytes, within ErrorBodyReadTimeoutMs (or the request timeout,
  // if shorter)
  val MaxErrorBodyBytes: Int = 4 * 1024
  val ErrorBodyReadTimeoutMs: Int = 10 * 1000

  def streamErrorMessage(
    label: String,
    status: Int,
    errorBody: String
  ): String =
    s"$label: HTTP $status - ${errorBody.take(500)}"

  /**
   * Resolves `Timeouts` PER FIELD - each unset field falls back to the engine's default - so a
   * partially-specified `Timeouts` (e.g. only `readTimeout`) can never silently drop the
   * request timeout (with `java.net.http`, for instance, that would mean an indefinite hang).
   */
  def resolveTimeouts(
    explicit: Timeouts,
    defaults: Timeouts
  ): Timeouts =
    Timeouts(
      requestTimeout = explicit.requestTimeout.orElse(defaults.requestTimeout),
      readTimeout = explicit.readTimeout.orElse(defaults.readTimeout),
      connectTimeout = explicit.connectTimeout.orElse(defaults.connectTimeout),
      pooledConnectionIdleTimeout =
        explicit.pooledConnectionIdleTimeout.orElse(defaults.pooledConnectionIdleTimeout)
    )

  /**
   * Form fields grouped by key - EVERY value of a repeated key is kept, keys in
   * first-occurrence order (a plain `.toMap` silently keeps only the last value of a repeated
   * key). Form fields are few, so the quadratic grouping is irrelevant.
   */
  def groupValues(pairs: Seq[(String, String)]): Map[String, Seq[String]] =
    ListMap(pairs.map(_._1).distinct.map { key =>
      key -> pairs.collect { case (`key`, value) => value }
    }: _*)

  /**
   * The media type carried by a raw `Content-Type: <media type>\r\n` header line - the format
   * the `FilePart => String` content functions produce (see
   * `WSClientBase.contentTypeByExtension`); `None` for an empty or any other line.
   */
  def contentTypeOfHeaderLine(headerLine: String): Option[String] = {
    val prefix = s"${HttpHeaderNames.CONTENT_TYPE}: "
    val line = headerLine.stripSuffix("\r\n")
    if (line.regionMatches(true, 0, prefix, 0, prefix.length))
      Some(line.substring(prefix.length).trim).filter(_.nonEmpty)
    else
      None
  }
}
