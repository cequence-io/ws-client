package io.cequence.wsclient.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
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
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execGETRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
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
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
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
      acceptableStatusCodes
    )

  override def execPOSTURLEncodedRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTURLEncodedRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
      acceptableStatusCodes
    )

  override def execPOSTFileRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTFileRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(urlParams),
      file,
      acceptableStatusCodes
    )

  override def execPOSTSourceRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _],
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTSourceRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(urlParams),
      source,
      acceptableStatusCodes
    )

  ////////////
  // DELETE //
  ////////////

  override def execDELETERich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execDELETERich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      acceptableStatusCodes
    )

  ////////////
  // PATCH //
  ////////////

  override def execPATCRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPATCRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
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
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPUTRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(params),
      paramTuplesToStrings(bodyParams),
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
}
