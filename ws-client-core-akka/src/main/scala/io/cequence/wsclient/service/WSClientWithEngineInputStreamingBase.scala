package io.cequence.wsclient.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain._

import scala.concurrent.Future

trait WSClientWithEngineInputStreamingBase[T <: WSClientEngine with WSClientInputStreamExtra]
    extends WSClientWithEngineBase[T]
    with WSClientInputStreamExtra {

  override def execPOSTSourceRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _],
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    engine.execPOSTSourceRich(
      endPoint.toString,
      endPointParam,
      paramTuplesToStrings(urlParams),
      source,
      extraHeaders,
      acceptableStatusCodes
    )
}
