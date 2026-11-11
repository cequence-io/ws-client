package io.cequence.wsclient.service

import io.cequence.wsclient.domain.{CequenceWSException, RichResponse, SiteBinding}
import io.cequence.wsclient.service.spi.TransportSettings
import io.cequence.wsclient.service.ws.{FilePart, WireFormat}
import play.api.libs.json.{JsObject, JsValue}

import java.io.File
import scala.concurrent.Future

/**
 * A fully SITE-STATELESS HTTP engine: the client/backend, its execution environment (actor
 * system / thread pools), and client-level settings
 * ([[io.cequence.wsclient.service.spi.TransportSettings]] - timeouts, proxy). Nothing here
 * binds an engine to a particular service: every call takes a
 * [[io.cequence.wsclient.domain.SiteBinding]] (base URL, auth context, error recovery, label),
 * so ONE engine instance serves any number of sites/providers at once - one connection pool,
 * one actor system.
 *
 * Services normally do not call these methods directly - they extend `WSClientWithEngineBase`,
 * which holds the service's [[SiteBinding]] and threads it into every call.
 *
 * Lifecycle: `close()` releases the client and any owned execution environment (idempotent).
 * An engine is shared by design - close it once, when done with ALL services using it.
 * Services created by the plain factories own a private engine and close it with the service;
 * services created via a `withEngine` hatch do NOT close the shared engine.
 *
 * Obtain an engine via `WSClientEngineRegistry` (classpath discovery) or a backend's explicit
 * factory (e.g. `PlayWSClientEngine(...)` on your own materializer).
 */
trait WSClientEngine extends WSClientBase {

  /**
   * The client-level settings (timeouts, proxy) this engine's HTTP client was built with.
   */
  def transportSettings: TransportSettings

  /**
   * A new engine with the given client-level settings. The copy ALWAYS builds its own HTTP
   * client (from `transportSettings`), so it is closed independently of this engine:
   *
   *   - `reuseExecContext = true` (default): the copy runs on THIS engine's execution
   *     environment (actor system / materializer / execution context) - the cheap way to get
   *     "its own engine" for a service that needs different timeouts, without paying for
   *     another actor system. Close the environment-owning original AFTER its copies.
   *   - `reuseExecContext = false`: the copy creates its OWN execution environment (an owned
   *     daemon actor system, like the discovery path) - fully independent. Supported only for
   *     engines that know how to build one (discovery-created engines); engines built on a
   *     caller-supplied environment throw a
   *     [[io.cequence.wsclient.domain.CequenceWSException]]. For engines without an actor
   *     system (jdk, sttp) the flag makes no difference.
   */
  def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): WSClientEngine

  /////////
  // GET //
  /////////

  def execGETRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  //////////
  // POST //
  //////////

  def execPOSTRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPOSTBodyRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  def execPOSTMultipartRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    fileParams: Seq[(String, File, Option[String])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes,
    useInMemoryBody: Boolean = false
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse]

  def execPOSTURLEncodedRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPOSTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ////////////
  // DELETE //
  ////////////

  def execDELETERich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ///////////
  // PATCH //
  ///////////

  def execPATCHRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  /////////
  // PUT //
  /////////

  def execPUTRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPUTBodyRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  def execPUTMultipartRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    fileParams: Seq[(String, File, Option[String])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes,
    useInMemoryBody: Boolean = false
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse]

  def execPUTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  /////////////////
  // WIRE FORMAT //
  /////////////////

  /**
   * The exact JSON text this engine sends as the body for `json` (since 1.2.0) - for request
   * signers (e.g. AWS SigV4) that must sign the bytes on the wire. Every engine renders bodies
   * with [[io.cequence.wsclient.service.ws.WireFormat.jsonBody]].
   */
  def renderJsonBody(json: JsValue): String = WireFormat.jsonBody(json)

  /**
   * The exact URL (path and query) this engine sends for these arguments (since 1.2.0) - for
   * request signers. The parameters include the site's `WsRequestContext.extraParams` (the
   * context function is evaluated for this call; a request evaluates it again - with a
   * `requestContextFun`, sign and send with one fixed binding: `site.copy(requestContext =
   * site.requestContextFn(), requestContextFun = None)`). The default is the base URL with an
   * RFC 3986-encoded query appended (a query in the endpoint string kept byte for byte) - the
   * Play and jdk engines; an engine whose HTTP library normalizes the URL overrides it with
   * that library's rendering.
   */
  def wireUrl(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil
  ): String =
    WireFormat.url(site.createURL(Some(endPoint), endPointParam), wireParams(site, params))

  // the call's parameters plus the site's extra params (one evaluation of the context function)
  private[wsclient] def wireParams(
    site: SiteBinding,
    params: Seq[(String, Option[Any])]
  ): Seq[(String, Option[Any])] =
    params ++ site.requestContextFn().extraParams.map { case (key, value) =>
      key -> Some(value)
    }

  /////////
  // AUX //
  /////////

  protected def toOptionalParams(
    params: Seq[(String, Any)]
  ): Seq[(String, Some[Any])] =
    params.map { case (a, b) => (a, Some(b)) }

  protected[service] def toJsBodyObject(
    bodyParams: Seq[(String, Option[JsValue])]
  ): JsObject = {
    val fields = bodyParams.collect { case (fieldName, Some(jsValue)) =>
      if (fieldName.trim.nonEmpty)
        Seq((fieldName, jsValue))
      else
        jsValue match {
          case JsObject(fields) => fields.toSeq
          case _                => throw new CequenceWSException("Empty param name.")
        }
    }.flatten

    JsObject(fields)
  }
}
