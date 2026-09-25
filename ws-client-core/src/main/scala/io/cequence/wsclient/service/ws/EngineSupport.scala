package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.{RichResponse, SiteBinding}
import io.cequence.wsclient.service.WSClientEngine

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
}
