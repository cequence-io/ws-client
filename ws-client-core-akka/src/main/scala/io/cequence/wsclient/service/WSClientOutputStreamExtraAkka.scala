package io.cequence.wsclient.service

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain.SiteBinding
import play.api.libs.json.JsValue

/**
 * Akka `Source`-typed output streaming - SSE/JSON event streams (`execJsonStream`) and raw
 * byte streams (`execRawStream`). Like every engine method, the calls are site-stateless: the
 * target service rides in the [[SiteBinding]] argument.
 *
 * Note: no `Materializer` is required here - the returned `Source`s are blueprints, and any
 * materialization an engine needs internally (e.g. Play's client, entity draining) is its own
 * implementation concern.
 *
 * Extends the backend-agnostic [[WSClientOutputStreamCore]]: every flavored streaming engine
 * also exposes its streams as cold `java.util.concurrent.Flow.Publisher`s (typically via
 * [[SourcePublishersAkka.deferred]]), so family-neutral consumers can stream without touching
 * akka/pekko types.
 *
 * @since Feb
 *   2023
 */
trait WSClientOutputStreamExtraAkka extends WSClientOutputStreamCore { self: WSClientEngine =>

  // narrows further: a Source-typed streaming engine copies as one
  def copy(
    transportSettings: io.cequence.wsclient.service.spi.TransportSettings =
      this.transportSettings,
    reuseExecContext: Boolean = true
  ): WSClientEngine with WSClientOutputStreamExtraAkka

  def execJsonStream(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    framingDelimiter: String = "\n\n",
    maxFrameLength: Option[Int] = None,
    stripPrefix: Option[String] = None,
    stripSuffix: Option[String] = None
  ): Source[JsValue, NotUsed]

  def execRawStream(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): Source[ByteString, NotUsed]
}
