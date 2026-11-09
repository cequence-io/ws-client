package io.cequence.wsclient.service

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.FilePart
import io.cequence.wsclient.stream.{AsyncStream, Bytes}
import play.api.libs.json._

import java.io.File
import scala.concurrent.Future
import scala.util.{Failure, Success}

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

  ///////////////
  // DOWNLOADS //
  ///////////////

  /**
   * The response body of a call (since 1.2.0), binary-safe on every engine: an engine with
   * output streaming reads the raw byte stream (at most `maxBytes` are read, then the call
   * fails); any other engine reads the buffered body (`Response.bytes` - in memory first, then
   * checked against `maxBytes`). A non-2xx status fails through `handleErrorCodes`. `method`:
   * GET, POST, PUT, PATCH or DELETE (the body parameters apply to POST / PUT / PATCH only, on
   * every engine). Time bound: a buffered read is bounded by the engine's request timeout; a
   * streamed one per engine - the Play stream engines cap it by the request timeout too, jdk /
   * zio-http bound only the gaps between chunks (`readTimeout`), pekko-http by pekko's client
   * idle-timeout; a body that keeps trickling in is read to its end or to `maxBytes` there.
   * For a total bound, run `execStreamDownload`'s stream with `run` and cancel it on a timer -
   * a `concurrent.Futures.timeout` around this call only stops the wait, not the transfer.
   */
  protected def execBytesDownload(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    maxBytes: Long = Bytes.DefaultMaxBytes
  ): Future[Bytes] =
    if (maxBytes < 0)
      Future.failed(new IllegalArgumentException(s"maxBytes must not be negative: $maxBytes"))
    else
      downloadMethod(method) match {
        case Left(unsupported) => Future.failed(unsupported)
        case Right(m) =>
          bytesDownload(endPoint, m, endPointParam, params, bodyParams, extraHeaders, maxBytes)
      }

  private def bytesDownload(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String],
    params: Seq[(PT, Option[Any])],
    bodyParams: Seq[(PT, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    maxBytes: Long
  ): Future[Bytes] =
    engine match {
      case streaming: WSClientOutputStreamCore =>
        streaming
          .execRawAsyncStream(
            site,
            endPoint.toString,
            method,
            endPointParam,
            paramTuplesToStrings(params),
            paramTuplesToStrings(downloadBody(method, bodyParams)),
            extraHeaders
          )
          .mapError(mapHttpStatusErrors)
          .runToBytes(maxBytes)
      case _ =>
        bufferedDownload(
          endPoint,
          method,
          endPointParam,
          params,
          bodyParams,
          extraHeaders
        ).map { response =>
          val bytes = getResponseOrError(response).bytes
          if (bytes.length > maxBytes)
            throw new CequenceWSException(
              s"The response body exceeds the limit of $maxBytes bytes"
            )
          else
            bytes
        }
    }

  /**
   * Starts a call and completes once its body started - the first chunk arrived, or the body
   * ended (since 1.2.0): `None` for a 404, a failure through `handleErrorCodes` for any other
   * non-2xx status, else a SINGLE-RUN stream of the body (binary-safe; run or cancel it - an
   * abandoned one holds its connection). An engine with output streaming streams the body; any
   * other engine serves the buffered body as one chunk (then the stream can also be re-run).
   * `method` as for [[execBytesDownload]].
   */
  protected def execStreamDownload(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Option[AsyncStream[Bytes]]] =
    downloadMethod(method) match {
      case Left(unsupported) => Future.failed(unsupported)
      case Right(m) =>
        streamDownload(endPoint, m, endPointParam, params, bodyParams, extraHeaders)
    }

  private def streamDownload(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String],
    params: Seq[(PT, Option[Any])],
    bodyParams: Seq[(PT, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): Future[Option[AsyncStream[Bytes]]] =
    engine match {
      case streaming: WSClientOutputStreamCore =>
        streaming
          .execRawAsyncStream(
            site,
            endPoint.toString,
            method,
            endPointParam,
            paramTuplesToStrings(params),
            paramTuplesToStrings(downloadBody(method, bodyParams)),
            extraHeaders
          )
          .preflight
          .transform {
            case Success(stream) =>
              Success(Some(stream.mapError(mapHttpStatusErrors)))
            case Failure(e: CequenceWSHttpStatusException) if e.statusCode == 404 =>
              Success(None)
            case Failure(e) =>
              Failure(mapHttpStatusErrors.applyOrElse(e, identity[Throwable]))
          }
      case _ =>
        bufferedDownload(
          endPoint,
          method,
          endPointParam,
          params,
          bodyParams,
          extraHeaders
        ).map { response =>
          if (response.response.isEmpty && response.status.code == 404)
            None
          else {
            Some(AsyncStream.single(getResponseOrError(response).bytes))
          }
        }
    }

  // the download methods, normalized once (the wire method is the same on every engine)
  private def downloadMethod(method: String): Either[CequenceWSException, String] =
    method.toUpperCase(java.util.Locale.ROOT) match {
      case m @ ("GET" | "POST" | "PUT" | "PATCH" | "DELETE") => Right(m)
      case other =>
        Left(new CequenceWSException(s"Unsupported download method: $other"))
    }

  // the body parameters of a download (`method` normalized): POST / PUT / PATCH only
  private def downloadBody(
    method: String,
    bodyParams: Seq[(PT, Option[JsValue])]
  ): Seq[(PT, Option[JsValue])] =
    method match {
      case "POST" | "PUT" | "PATCH" => bodyParams
      case _                        => Nil
    }

  // a buffered call accepting every 2xx status - straight to the engine, as the streaming path
  // goes (a service's own exec*Rich overrides apply to neither)
  private def bufferedDownload(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String],
    params: Seq[(PT, Option[Any])],
    bodyParams: Seq[(PT, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): Future[RichResponse] = {
    val anySuccess = 200 to 299
    val ep = endPoint.toString
    val urlParams = paramTuplesToStrings(params)
    val body = paramTuplesToStrings(bodyParams)
    method match {
      case "GET" =>
        engine.execGETRich(site, ep, endPointParam, urlParams, extraHeaders, anySuccess)
      case "POST" =>
        engine.execPOSTRich(site, ep, endPointParam, urlParams, body, extraHeaders, anySuccess)
      case "PUT" =>
        engine.execPUTRich(site, ep, endPointParam, urlParams, body, extraHeaders, anySuccess)
      case "PATCH" =>
        engine.execPATCHRich(
          site,
          ep,
          endPointParam,
          urlParams,
          body,
          extraHeaders,
          anySuccess
        )
      case "DELETE" =>
        engine.execDELETERich(site, ep, endPointParam, urlParams, extraHeaders, anySuccess)
      case other =>
        Future.failed(new CequenceWSException(s"Unsupported download method: $other"))
    }
  }

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
