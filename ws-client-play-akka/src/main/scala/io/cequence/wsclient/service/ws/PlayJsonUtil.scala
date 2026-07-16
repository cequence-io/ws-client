package io.cequence.wsclient.service.ws

import play.api.libs.json.JsValue
import play.api.libs.ws.InMemoryBody
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue

import java.nio.charset.StandardCharsets

/**
 * Universal JSON utilities that match WS client serialization behavior exactly.
 *
 * The WS client has specific behavior:
 *   - Emoticons (📞📧🌐): Escapes to \\u... format
 *   - Other Unicode (Chinese, Arabic): Preserves as UTF-8
 *   - ASCII: Preserves as-is
 */
object PlayJsonUtil {

  def wsClientStringify(json: JsValue): String =
    writeableOf_JsValue.transform(json) match {
      case InMemoryBody(bytes) => new String(bytes.toArray, StandardCharsets.UTF_8)
      case _                   => throw new IllegalArgumentException("Unsupported body type")
    }
}
