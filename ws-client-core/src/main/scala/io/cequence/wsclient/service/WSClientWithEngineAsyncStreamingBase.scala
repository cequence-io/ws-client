package io.cequence.wsclient.service

import io.cequence.wsclient.stream.{AsyncStream, Bytes}
import play.api.libs.json.JsValue

import scala.concurrent.Future

/**
 * Adds backend-neutral, `PEP`-typed response streaming and binary downloads to a service, on
 * any engine with output streaming (`WSClientOutputStreamCore`: jdk, zio-http, the Play stream
 * engines, pekko-http) - the Akka-free counterpart of `WSClientWithEngineOutputStreamingBase`
 * (since 1.2.0). The streaming methods keep that base's names, so porting a service is a mixin
 * swap (the two cannot be mixed into one class).
 *
 * Streams are cold and re-runnable: each run is a new request. A non-2xx response fails a run
 * through the service's `handleErrorCodes` (via `mapHttpStatusErrors`, applied as a stream
 * operator - so it runs natively on any consumer).
 *
 * Downloads are binary-safe on every engine (they read the raw response bytes): `execGETBytes`
 * / `execPOSTBytes` collect the body (at most `maxBytes`); `execGETStream` / `execPOSTStream`
 * start the request and complete once the body started (its first chunk arrived, or it ended)
 *   - `None` for a 404, a classified failure for any other non-2xx status, else a SINGLE-RUN
 *     stream of the body (run or cancel it - an abandoned one holds its connection).
 */
trait WSClientWithEngineAsyncStreamingBase[T <: WSClientEngine with WSClientOutputStreamCore]
    extends WSClientWithEngineBase[T] {

  def execJsonStream(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    framingDelimiter: String = JsonStreamFrames.DefaultFramingDelimiter,
    maxFrameLength: Option[Int] = None,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): AsyncStream[JsValue] =
    engine
      .execJsonAsyncStream(
        site,
        endPoint.toString,
        method,
        endPointParam,
        paramTuplesToStrings(params),
        paramTuplesToStrings(bodyParams),
        extraHeaders,
        framingDelimiter,
        maxFrameLength,
        stripPrefix,
        stripSuffix
      )
      .mapError(mapHttpStatusErrors)

  def execRawStream(
    endPoint: PEP,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): AsyncStream[Bytes] =
    engine
      .execRawAsyncStream(
        site,
        endPoint.toString,
        method,
        endPointParam,
        paramTuplesToStrings(params),
        paramTuplesToStrings(bodyParams),
        extraHeaders
      )
      .mapError(mapHttpStatusErrors)

  ///////////////
  // DOWNLOADS //
  ///////////////

  /**
   * The response body of a GET (binary-safe), at most `maxBytes` (a larger one fails with a
   * [[io.cequence.wsclient.domain.CequenceWSException]]). A non-2xx status fails through
   * `handleErrorCodes`. (Services on any engine: `WSClientWithEngineBase.execBytesDownload`.)
   */
  def execGETBytes(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    maxBytes: Long = Bytes.DefaultMaxBytes
  ): Future[Bytes] =
    execBytesDownload(endPoint, "GET", endPointParam, params, Nil, extraHeaders, maxBytes)

  /** The response body of a POST (binary-safe) - see [[execGETBytes]]. */
  def execPOSTBytes(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    maxBytes: Long = Bytes.DefaultMaxBytes
  ): Future[Bytes] =
    execBytesDownload(
      endPoint,
      "POST",
      endPointParam,
      params,
      bodyParams,
      extraHeaders,
      maxBytes
    )

  /**
   * Starts a GET and completes once its body started: `None` for a 404, a failure through
   * `handleErrorCodes` for any other non-2xx status, else a single-run stream of the body.
   */
  def execGETStream(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Option[AsyncStream[Bytes]]] =
    execStreamDownload(endPoint, "GET", endPointParam, params, Nil, extraHeaders)

  /** Starts a POST - see [[execGETStream]]. */
  def execPOSTStream(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Option[AsyncStream[Bytes]]] =
    execStreamDownload(endPoint, "POST", endPointParam, params, bodyParams, extraHeaders)
}
