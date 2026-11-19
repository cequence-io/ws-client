package io.cequence.wsclient.service

import io.cequence.wsclient.JsonUtil
import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.FilePart
import io.cequence.wsclient.stream.AsyncCall
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
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execGETRich(
      endPoint,
      endPointParam,
      params,
      extraHeaders
    ).map(getResponseOrError)

  def execGETRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
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
   * @param useInMemoryBody
   *   if true, materializes the entire body in memory avoiding chunked transfer encoding; use
   *   when the target server does not support chunked requests (e.g. behind Cloudflare)
   */
  def execPOSTMultipart(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    useInMemoryBody: Boolean = false
  ): Future[Response] =
    execPOSTMultipartRich(
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams,
      extraHeaders,
      useInMemoryBody = useInMemoryBody
    ).map(getResponseOrError)

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   * @param useInMemoryBody
   *   if true, materializes the entire body in memory avoiding chunked transfer encoding; use
   *   when the target server does not support chunked requests (e.g. behind Cloudflare)
   */
  def execPOSTMultipartRich(
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
  ): Future[RichResponse]

  def execPOSTURLEncoded(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPOSTURLEncodedRich(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTURLEncodedRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPOSTFile(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPOSTFileRich(
      endPoint,
      endPointParam,
      urlParams,
      file,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTFileRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ////////////
  // DELETE //
  ////////////

  def execDELETE(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execDELETERich(
      endPoint,
      endPointParam,
      params,
      extraHeaders
    ).map(getResponseOrError)

  def execDELETERich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ////////////
  // PATCH //
  ////////////

  def execPATCH(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPATCHRich(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPATCHRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ////////////
  // PUT //
  ////////////

  def execPUT(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPUTRich(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPUTRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  def execPUTBody(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPUTBodyRich(
      endPoint,
      endPointParam,
      params,
      body,
      extraHeaders
    ).map(getResponseOrError)

  def execPUTBodyRich(
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
   * @param useInMemoryBody
   *   if true, materializes the entire body in memory avoiding chunked transfer encoding; use
   *   when the target server does not support chunked requests (e.g. behind Cloudflare)
   */
  def execPUTMultipart(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    useInMemoryBody: Boolean = false
  ): Future[Response] =
    execPUTMultipartRich(
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams,
      extraHeaders,
      useInMemoryBody = useInMemoryBody
    ).map(getResponseOrError)

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   * @param useInMemoryBody
   *   if true, materializes the entire body in memory avoiding chunked transfer encoding; use
   *   when the target server does not support chunked requests (e.g. behind Cloudflare)
   */
  def execPUTMultipartRich(
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
  ): Future[RichResponse]

  def execPUTFile(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPUTFileRich(
      endPoint,
      endPointParam,
      urlParams,
      file,
      extraHeaders
    ).map(getResponseOrError)

  def execPUTFileRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]

  ///////////
  // CALLS //
  ///////////

  // The call twins (since 1.3.0): `execXCall` / `execXRichCall` are `execX` / `execXRich` as a
  // lazy, backend-neutral AsyncCall - every RUN is a new request. `execXCall` turns a
  // non-acceptable status into this service's exception (`getResponseOrError` /
  // `handleErrorCodes`) as a stage of the call. The `execXRichCall` defaults run `execXRich`
  // per run (a `Future` leaf), so whatever a service's `execXRich` does, its calls do too. A
  // service on `WSClientWithEngineBase` can opt into the engine's own calls - NATIVE on an
  // effect-system engine (zio-http) - with `nativeCalls`.

  def execGETCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execGETRichCall(
      endPoint,
      endPointParam,
      params,
      extraHeaders
    ).map(getResponseOrError)

  def execGETRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execGETRich(
        endPoint,
        endPointParam,
        params,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPOSTCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPOSTRichCall(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPOSTRich(
        endPoint,
        endPointParam,
        params,
        bodyParams,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPOSTBodyCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPOSTBodyRichCall(
      endPoint,
      endPointParam,
      params,
      body,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTBodyRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPOSTBodyRich(
        endPoint,
        endPointParam,
        params,
        body,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPOSTMultipartCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    useInMemoryBody: Boolean = false
  ): AsyncCall[Response] =
    execPOSTMultipartRichCall(
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams,
      extraHeaders,
      useInMemoryBody = useInMemoryBody
    ).map(getResponseOrError)

  def execPOSTMultipartRichCall(
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
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPOSTMultipartRich(
        endPoint,
        endPointParam,
        params,
        fileParams,
        bodyParams,
        extraHeaders,
        acceptableStatusCodes,
        useInMemoryBody
      )(filePartToContent)
    )

  def execPOSTURLEncodedCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPOSTURLEncodedRichCall(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTURLEncodedRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPOSTURLEncodedRich(
        endPoint,
        endPointParam,
        params,
        bodyParams,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPOSTFileCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPOSTFileRichCall(
      endPoint,
      endPointParam,
      urlParams,
      file,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTFileRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPOSTFileRich(
        endPoint,
        endPointParam,
        urlParams,
        file,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execDELETECall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execDELETERichCall(
      endPoint,
      endPointParam,
      params,
      extraHeaders
    ).map(getResponseOrError)

  def execDELETERichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execDELETERich(
        endPoint,
        endPointParam,
        params,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPATCHCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPATCHRichCall(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPATCHRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPATCHRich(
        endPoint,
        endPointParam,
        params,
        bodyParams,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPUTCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPUTRichCall(
      endPoint,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).map(getResponseOrError)

  def execPUTRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPUTRich(
        endPoint,
        endPointParam,
        params,
        bodyParams,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPUTBodyCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPUTBodyRichCall(
      endPoint,
      endPointParam,
      params,
      body,
      extraHeaders
    ).map(getResponseOrError)

  def execPUTBodyRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPUTBodyRich(
        endPoint,
        endPointParam,
        params,
        body,
        extraHeaders,
        acceptableStatusCodes
      )
    )

  def execPUTMultipartCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    useInMemoryBody: Boolean = false
  ): AsyncCall[Response] =
    execPUTMultipartRichCall(
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams,
      extraHeaders,
      useInMemoryBody = useInMemoryBody
    ).map(getResponseOrError)

  def execPUTMultipartRichCall(
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
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPUTMultipartRich(
        endPoint,
        endPointParam,
        params,
        fileParams,
        bodyParams,
        extraHeaders,
        acceptableStatusCodes,
        useInMemoryBody
      )(filePartToContent)
    )

  def execPUTFileCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncCall[Response] =
    execPUTFileRichCall(
      endPoint,
      endPointParam,
      urlParams,
      file,
      extraHeaders
    ).map(getResponseOrError)

  def execPUTFileRichCall(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): AsyncCall[RichResponse] =
    AsyncCall.future(
      execPUTFileRich(
        endPoint,
        endPointParam,
        urlParams,
        file,
        extraHeaders,
        acceptableStatusCodes
      )
    )

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
