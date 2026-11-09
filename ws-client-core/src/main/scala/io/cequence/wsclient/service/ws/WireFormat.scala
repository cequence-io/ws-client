package io.cequence.wsclient.service.ws

import play.api.libs.json.{JsValue, Json}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The wire format the engines share (since 1.2.0): the bytes of a JSON body and the encoding
 * of query parameters - so a request signer (e.g. AWS SigV4) can sign exactly what an engine
 * sends. See `WSClientEngine.renderJsonBody` / `wireUrl`.
 */
object WireFormat {

  /**
   * A JSON body as every engine sends it: play-json's `Json.toBytes` (Play WS's own body
   * rendering) - characters outside the Basic Multilingual Plane (e.g. emoji) are escaped as
   * `\\uXXXX` surrogate pairs; other non-ASCII text (CJK, Arabic, ...) stays UTF-8.
   */
  def jsonBody(json: JsValue): String = new String(jsonBodyBytes(json), StandardCharsets.UTF_8)

  /** The UTF-8 bytes of [[jsonBody]]. */
  def jsonBodyBytes(json: JsValue): Array[Byte] = Json.toBytes(json)

  /**
   * RFC 3986 percent-encoding of a query key or value: every byte of its UTF-8 form but the
   * unreserved characters (letters, digits, `-`, `.`, `_`, `~`) - `%20` for a space (form
   * encoding's `+` is read as a literal `+` by an RFC 3986 server).
   */
  def encodeQueryComponent(value: String): String =
    URLEncoder
      .encode(value, "UTF-8")
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")

  /**
   * `base` with the defined `params` appended as an RFC 3986-encoded query - after `&` when
   * `base` already carries a query (e.g. Azure's `?api-version=...`), which stays byte for
   * byte as the caller wrote it.
   */
  def url(
    base: String,
    params: Seq[(String, Option[Any])]
  ): String = {
    val query = params.collect { case (key, Some(value)) =>
      s"${encodeQueryComponent(key)}=${encodeQueryComponent(value.toString)}"
    }.mkString("&")

    if (query.isEmpty)
      base
    else if (base.contains("?"))
      s"$base&$query"
    else
      s"$base?$query"
  }
}
