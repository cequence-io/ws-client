package io.cequence.wsclient.domain

import io.cequence.wsclient.stream.Bytes
import play.api.libs.json.JsValue

trait RichResponse {
  def response: Option[Response]

  // status code and message
  def status: StatusData

  def headers: Map[String, Seq[String]]
}

trait Response {
  def json: JsValue

  def string: String

  /**
   * The raw response body (since 1.2.0) - binary-safe on every ws-client engine (they keep the
   * bytes they received). The default, for other implementations, is the UTF-8 encoding of
   * `string`, which is not binary-safe.
   */
  def bytes: Bytes = Bytes.utf8(string)
}

case class StatusData(
  code: Int,
  message: String
)
