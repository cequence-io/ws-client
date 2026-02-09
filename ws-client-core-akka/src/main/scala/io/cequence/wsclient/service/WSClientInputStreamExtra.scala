package io.cequence.wsclient.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain._

import scala.concurrent.Future

trait WSClientInputStreamExtra { self: WSClient =>

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
  ): Future[RichResponse]
}
