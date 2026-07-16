package io.cequence.wsclient.domain

import scala.util.control.NonFatal

/**
 * The SITE half of a WS call - which service an engine talks to, and how. Engines are
 * STATELESS with respect to the site: every engine call takes a [[SiteBinding]], so ONE engine
 * (one HTTP client, one connection pool, one actor system) serves any number of
 * sites/providers. A service typically holds its binding once and feeds it into each call
 * (`WSClientWithEngineBase` does this automatically).
 *
 * @param coreUrl
 *   base URL of the web service
 * @param requestContext
 *   per-request data (auth headers, extra params) attached to every call
 * @param recoverErrors
 *   custom error recovery, composed OVER the engine's default transport-failure normalization:
 *   the partial function sees the Cequence exception taxonomy
 *   ([[io.cequence.wsclient.domain.CequenceWSTimeoutException]],
 *   [[io.cequence.wsclient.domain.CequenceWSUnknownHostException]], ...) for failures the
 *   engine recognizes, and the raw backend exception otherwise - so the same recovery logic is
 *   portable across engines
 * @param requestContextFun
 *   when set, the request context is re-evaluated through this function on every request (e.g.
 *   to refresh an expiring auth token) and `requestContext` is ignored
 * @param label
 *   logging label for this site (defaults to the engine's class name) - set it to tell
 *   providers apart when several sites share one engine
 */
final case class SiteBinding(
  coreUrl: String,
  requestContext: WsRequestContext = WsRequestContext(),
  recoverErrors: Option[String => PartialFunction[Throwable, RichResponse]] = None,
  requestContextFun: Option[() => WsRequestContext] = None,
  label: Option[String] = None
) {

  /**
   * The effective request-context provider: `requestContextFun` when set, else the fixed
   * `requestContext`. Engines read the context exclusively through this - per request.
   */
  def requestContextFn: () => WsRequestContext =
    requestContextFun.getOrElse(() => requestContext)

  /**
   * The full URL of an endpoint on this site - `coreUrl` plus the endpoint path (plus an
   * optional path value).
   */
  def createURL(
    endpoint: Option[String],
    value: Option[String] = None
  ): String = {
    val endpointString = endpoint.getOrElse("")
    val valueString = value.map("/" + _).getOrElse("")
    val slash =
      if (coreUrl.endsWith("/") || (endpointString.isEmpty && valueString.isEmpty)) "" else "/"

    coreUrl + slash + endpointString + valueString
  }
}

object SiteBinding {

  /**
   * Composes the site's error recovery with an engine's default transport-failure
   * normalization, for use by engine implementations:
   *
   *   - without site recovery, the default mapping applies as is
   *   - with site recovery, raw failures are first normalized by the default mapping (which
   *     returns a response or, typically, throws a Cequence exception); the site's function is
   *     then applied to the normalized exception, or to the raw one if the default mapping
   *     does not cover it
   *
   * Failures neither mapping covers are left untouched (the partial function is not defined
   * for them).
   */
  def resolveRecoverErrors(
    site: Option[String => PartialFunction[Throwable, RichResponse]],
    default: String => PartialFunction[Throwable, RichResponse]
  ): String => PartialFunction[Throwable, RichResponse] =
    site match {
      case None => default

      case Some(siteFun) =>
        (serviceEndPointName: String) => {
          val defaultPF = default(serviceEndPointName)
          val sitePF = siteFun(serviceEndPointName)

          {
            case e if defaultPF.isDefinedAt(e) =>
              try defaultPF(e)
              catch {
                case NonFatal(normalized) if sitePF.isDefinedAt(normalized) =>
                  sitePF(normalized)
              }
            case e if sitePF.isDefinedAt(e) => sitePF(e)
          }
        }
    }
}
