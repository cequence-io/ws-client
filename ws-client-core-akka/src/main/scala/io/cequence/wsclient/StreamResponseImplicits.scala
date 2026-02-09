package io.cequence.wsclient

import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain.{
  CequenceWSException,
  Response,
  StreamedResponse,
  RichResponse
}

object StreamResponseImplicits {

  implicit class StreamSafeOps(val response: Response) {
    def asSafeSource: Source[ByteString, _] =
      response match {
        case sr: StreamedResponse => sr.source
        case _ =>
          throw new CequenceWSException(
            "Response does not support streaming. Use StreamedResponse."
          )
      }
  }

  implicit class StreamSafeRichOps(val response: RichResponse) {
    def asSafeSource: Source[ByteString, _] = {
      val resp = response.response.getOrElse(
        throw new CequenceWSException(
          s"No WS response found. The status code received is ${response.status.code} and message: '${response.status.message}'."
        )
      )
      resp match {
        case sr: StreamedResponse => sr.source
        case _ =>
          throw new CequenceWSException(
            "Response does not support streaming. Use StreamedResponse."
          )
      }
    }
  }
}
