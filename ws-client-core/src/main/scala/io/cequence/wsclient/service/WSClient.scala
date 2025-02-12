package io.cequence.wsclient.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.JsonUtil
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
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPOSTRich(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPOSTBody(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPOSTBodyRich(
      endPoint,
      endPointParam,
      params,
      body,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTBodyRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
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

  def execPOSTSource(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _]
  ): Future[Response] =
    execPOSTSourceRich(
      endPoint,
      endPointParam,
      urlParams,
      source
    ).map(getResponseOrError)

  def execPOSTSourceRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _],
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

  ////////////
  // PUT //
  ////////////

  def execPUT(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil
  ): Future[Response] =
    execPUTRich(
      endPoint,
      endPointParam,
      params,
      bodyParams
    ).map(getResponseOrError)

  def execPUTRich(
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
    JsonUtil.jsonBodyParams(params: _*)

  protected def jsonBodyParams[T: Format](
    spec: T
  ): Seq[(String, Option[JsValue])] =
    JsonUtil.jsonBodyParams(spec)
}
