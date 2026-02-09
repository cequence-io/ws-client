package io.cequence.wsclient

import io.cequence.wsclient.JsonUtil.JsonOps
import io.cequence.wsclient.domain.{CequenceWSException, Response, RichResponse}
import play.api.libs.json.Reads

object ResponseImplicits {

  implicit class JsonSafeOps(val response: Response) {
    def asSafeJson[T](
      implicit fjs: Reads[T]
    ): T =
      response.json.asSafe[T]

    def asSafeJsonArray[T](
      implicit fjs: Reads[T]
    ): Seq[T] =
      response.json.asSafeArray[T]
  }

  implicit class JsonSafeRichOps(val response: RichResponse) {
    def asSafeJson[T](
      implicit fjs: Reads[T]
    ): T =
      getOrThrowNoResponse(response).json.asSafe[T]

    def asSafeJsonArray[T](
      implicit fjs: Reads[T]
    ): Seq[T] =
      getOrThrowNoResponse(response).json.asSafeArray[T]

    def asSafeString: String =
      getOrThrowNoResponse(response).string

    private def getOrThrowNoResponse[T](response: RichResponse): Response =
      response.response.getOrElse(
        throw new CequenceWSException(
          s"No WS response found. The status code received is ${response.status.code} and message: '${response.status.message}'."
        )
      )
  }
}
