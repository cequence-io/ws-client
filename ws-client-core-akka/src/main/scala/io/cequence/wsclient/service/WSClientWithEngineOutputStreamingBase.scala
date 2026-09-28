package io.cequence.wsclient.service

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.json.JsValue

/**
 * Adds `PEP`-typed response streaming (SSE / JSON / raw bytes) to a service, delegating to an
 * engine with output-streaming support - the service's
 * [[io.cequence.wsclient.domain.SiteBinding]] rides on every call.
 *
 * A non-2xx response fails the stream through the service's `handleErrorCodes` (via
 * `mapHttpStatusErrors`) - the same error classification the non-streamed calls get. Prefer
 * these over calling `engine.execJsonStream(site, ...)` directly, which fails with the
 * unclassified [[io.cequence.wsclient.domain.CequenceWSHttpStatusException]].
 */
trait WSClientWithEngineOutputStreamingBase[
  T <: WSClientEngine with WSClientOutputStreamExtraAkka
] extends WSClientWithEngineBase[T] {

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
  ): Source[JsValue, NotUsed] =
    engine
      .execJsonStream(
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
  ): Source[ByteString, NotUsed] =
    engine
      .execRawStream(
        site,
        endPoint.toString,
        method,
        endPointParam,
        paramTuplesToStrings(params),
        paramTuplesToStrings(bodyParams),
        extraHeaders
      )
      .mapError(mapHttpStatusErrors)
}
