package io.cequence.wsclient.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain._

import scala.concurrent.Future

/**
 * Adds `PEP`-typed request-body streaming (chunked uploads) to a service, delegating to an
 * engine with input-streaming support - the service's [[SiteBinding]] rides on every call.
 */
trait WSClientWithEngineInputStreamingBase[
  T <: WSClientEngine with WSClientInputStreamExtraAkka
] extends WSClientWithEngineBase[T] {

  def execPOSTSource(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _],
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[Response] =
    execPOSTSourceRich(
      endPoint,
      endPointParam,
      urlParams,
      source,
      extraHeaders
    ).map(getResponseOrError)

  def execPOSTSourceRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _],
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTSourceRich(
      site,
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(urlParams),
      source,
      extraHeaders,
      acceptableStatusCodes
    )
}
