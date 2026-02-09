package io.cequence.wsclient.service

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.FilePart
import play.api.libs.json._

import java.io.File
import scala.concurrent.Future

/**
 * WS client with an "engine"
 *
 * @since July
 *   2024
 */
trait WSClientWithEngineBase[T <: WSClientEngine] extends WSClient with HasWSClientEngine[T] {

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
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] =
    engine.execPOSTMultipartRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      param3TuplesToStrings(fileParams),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes
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
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] =
    engine.execPUTMultipartRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      param3TuplesToStrings(fileParams),
      paramTuplesToStrings(bodyParams),
      extraHeaders,
      acceptableStatusCodes
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

  def close() = engine.close()

  // aux

  protected def paramTuplesToStrings[V](
    params: Seq[(PT, V)]
  ) =
    params.map { case (k, v) => k.toString -> v }

  protected def param3TuplesToStrings[V1, V2](
    params: Seq[(PT, V1, V2)]
  ) =
    params.map { case (k, v1, v2) => (k.toString, v1, v2) }

  // engine delegates
  protected def createURL(
    endpoint: Option[String],
    value: Option[String] = None
  ): String = engine.createURL(endpoint, value)

  protected def toJsBodyObject(
    bodyParams: Seq[(String, Option[JsValue])]
  ): JsObject =
    engine.toJsBodyObject(bodyParams)

  protected def requestContext: WsRequestContext =
    engine.requestContext
}
