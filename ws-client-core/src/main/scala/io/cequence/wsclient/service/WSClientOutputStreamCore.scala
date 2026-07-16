package io.cequence.wsclient.service

import io.cequence.wsclient.domain.SiteBinding
import play.api.libs.json.JsValue

import java.nio.ByteBuffer
import java.util.concurrent.Flow

/**
 * Backend-agnostic output-streaming contract based on `java.util.concurrent.Flow` (Reactive
 * Streams, JDK 9+) - the family-neutral counterpart of the akka/pekko `Source`-typed
 * `WSClientOutputStreamExtraAkka`/`-Pekko`. Like every engine method, the calls are
 * site-stateless: the target service rides in the [[SiteBinding]] argument.
 *
 * Contract:
 *   - publishers are COLD: no HTTP request is fired until the first `subscribe`
 *   - publishers are SINGLE-SHOT: a second subscription is rejected with `onError` (mirroring
 *     the single-materialization semantics of the `Source`-typed API)
 *   - subscription cancellation aborts the underlying HTTP stream
 *   - `execJsonStreamPublisher` has the exact semantics of `execJsonStream`: frames are split
 *     by `framingDelimiter`, the optional `data: ` item prefix is stripped, the stream
 *     completes at the `[DONE]` sentinel, and parse/transport failures surface as `onError`
 *     carrying the Cequence exception taxonomy
 *   - `execRawStreamPublisher` delivers the response bytes chunked AS RECEIVED (no re-framing)
 */
trait WSClientOutputStreamCore { self: WSClientEngine =>

  // narrows WSClientEngine.copy: a streaming engine copies as a streaming engine
  def copy(
    transportSettings: io.cequence.wsclient.service.spi.TransportSettings =
      this.transportSettings,
    reuseExecContext: Boolean = true
  ): WSClientEngine with WSClientOutputStreamCore

  def execJsonStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    framingDelimiter: String = JsonStreamFrames.DefaultFramingDelimiter,
    maxFrameLength: Option[Int] = None,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): Flow.Publisher[JsValue]

  def execRawStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): Flow.Publisher[ByteBuffer]
}
