package io.cequence.wsclient.service

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.FilePart
import play.api.libs.json._

import java.io.File
import scala.concurrent.Future

/**
 * WS client with a site-stateless "engine": the service holds its [[SiteBinding]] (base URL,
 * auth context, error recovery, label) and feeds it into every engine call. The engine itself
 * carries no site state, so ONE engine can back any number of services/providers.
 *
 * Ownership: `close()` closes the engine only when the service OWNS it (`ownsEngine`, default
 * true - the plain-factory case, where the factory created a private engine for this service).
 * Services built on a SHARED engine (`withEngine` hatches) override it to false - the shared
 * engine is closed once, by its creator, when done with all services.
 *
 * @since July
 *   2024
 */
trait WSClientWithEngineBase[T <: WSClientEngine] extends WSClient with HasWSClientEngine[T] {

  /**
   * The site this service talks to - threaded into every engine call.
   */
  protected def site: SiteBinding

  /**
   * Whether `close()` closes the engine. True (default) for a private, factory-created engine;
   * false for a shared, caller-supplied one.
   */
  protected def ownsEngine: Boolean = true

  /////////
  // GET //
  /////////

  override def execGETRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execGETRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      extraHeaders,
      acceptableStatusCodes
    )

  //////////
  // POST //
  //////////

  override def execPOSTRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes
    )

  override def execPOSTBodyRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTBodyRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      body,
      extraHeaders,
      acceptableStatusCodes
    )

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  override def execPOSTMultipartRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes,
    useInMemoryBody: Boolean = false
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] =
    engine.execPOSTMultipartRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      param3TuplesToStrings(fileParams),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes,
      useInMemoryBody
    )

  override def execPOSTURLEncodedRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTURLEncodedRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes
    )

  override def execPOSTFileRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTFileRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(urlParams),
      file,
      extraHeaders,
      acceptableStatusCodes
    )

  ////////////
  // DELETE //
  ////////////

  override def execDELETERich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execDELETERich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      extraHeaders,
      acceptableStatusCodes
    )

  ////////////
  // PATCH //
  ////////////

  override def execPATCHRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPATCHRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes
    )

  /////////
  // PUT //
  /////////

  override def execPUTRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPUTRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes
    )

  override def execPUTBodyRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPUTBodyRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      body,
      extraHeaders,
      acceptableStatusCodes
    )

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  override def execPUTMultipartRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes,
    useInMemoryBody: Boolean = false
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] =
    engine.execPUTMultipartRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      param3TuplesToStrings(fileParams),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes,
      useInMemoryBody
    )

  override def execPUTFileRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPUTFileRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(urlParams),
      file,
      extraHeaders,
      acceptableStatusCodes
    )

  ///////////
  // CLOSE //
  ///////////

  def close() =
    if (ownsEngine) engine.close()

  // aux

  protected def paramTuplesToStrings[V](
    params: Seq[(PT, V)]
  ) =
    params.map { case (k, v) => k.toString -> v }

  protected def param3TuplesToStrings[V1, V2](
    params: Seq[(PT, V1, V2)]
  ) =
    params.map { case (k, v1, v2) => (k.toString, v1, v2) }

  // site delegates (kept for source compatibility with pre-stateless-engine code)

  protected def createURL(
    endpoint: Option[String],
    value: Option[String] = None
  ): String = site.createURL(endpoint, value)

  protected def toJsBodyObject(
    bodyParams: Seq[(String, Option[JsValue])]
  ): JsObject =
    engine.toJsBodyObject(bodyParams)

  protected def requestContext: WsRequestContext =
    site.requestContextFn()
}
