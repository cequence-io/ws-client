package io.cequence.wsclient.service

import io.cequence.wsclient.JsonUtil.toJson
import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.FilePart
import play.api.libs.json._

import java.io.File
import scala.concurrent.Future

/**
 * Base trait for web services / client with handy GET, POST, and DELETE requests
 *
 * @since July
 *   2024
 */
trait WSClient extends WSClientBase {

  protected type PEP
  protected type PT

  /////////
  // GET //
  /////////

  def execGET(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execGETRich(
      endPoint,
      endPointParam,
      params
    ).map(getResponseOrError)

  def execGETRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  //////////
  // POST //
  //////////

  def execPOST(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil
  ): Future[Response] =
    execPOSTRich(
      endPoint,
      endPointParam,
      params,
      bodyParams
    ).map(getResponseOrError)

  def execPOSTRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  def execPOSTMultipart(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execPOSTMultipartRich(
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams
    ).map(getResponseOrError)

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  def execPOSTMultipartRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse]

  def execPOSTURLEncoded(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execPOSTURLEncodedRich(
      endPoint,
      endPointParam,
      params,
      bodyParams
    ).map(getResponseOrError)

  def execPOSTURLEncodedRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPOSTFile(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File
  ): Future[Response] =
    execPOSTFileRich(
      endPoint,
      endPointParam,
      urlParams,
      file
    ).map(getResponseOrError)

  def execPOSTFileRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ////////////
  // DELETE //
  ////////////

  def execDELETE(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execDELETERich(
      endPoint,
      endPointParam,
      params
    ).map(getResponseOrError)

  def execDELETERich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ////////////
  // PATCH //
  ////////////

  def execPATCH(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil
  ): Future[Response] =
    execPATCRich(
      endPoint,
      endPointParam,
      params,
      bodyParams
    ).map(getResponseOrError)

  def execPATCRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  // AUX

  protected def jsonBodyParams[T](
    params: (T, Option[Any])*
  ): Seq[(T, Option[JsValue])] =
    params.map { case (paramName, value) => (paramName, value.map(toJson)) }

  protected def jsonBodyParams[T: Format](
    spec: T
  ): Seq[(String, Option[JsValue])] = {
    val json = Json.toJson(spec)
    json.as[JsObject].value.toSeq.map { case (key, value) =>
      val optionValue = value match {
        case JsNull => None
        case _      => Some(value)
      }
      (key, optionValue)
    }
  }
}
