package io.cequence.wsclient.service

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import play.api.libs.json.JsValue

/**
 * Stream request support specifically tailored for OpenAI API.
 *
 * @since Feb
 *   2023
 */
trait WSClientEngineStreamExtra {

  protected implicit val materializer: Materializer

  def execJsonStream(
    endPoint: String,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil
  ): Source[JsValue, NotUsed]
}
