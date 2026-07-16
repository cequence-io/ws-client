package io.cequence.wsclient.service

import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain._

import scala.concurrent.Future

/**
 * Akka `Source`-typed request-body streaming (chunked uploads). Like every engine method, the
 * call is site-stateless: the target service rides in the [[SiteBinding]] argument. (The
 * `PEP`-typed convenience variant for services lives in
 * `WSClientWithEngineInputStreamingBase`.)
 */
trait WSClientInputStreamExtraAkka { self: WSClientEngine =>

  // narrows WSClientEngine.copy: an upload-streaming engine copies as one
  def copy(
    transportSettings: io.cequence.wsclient.service.spi.TransportSettings =
      this.transportSettings,
    reuseExecContext: Boolean = true
  ): WSClientEngine with WSClientInputStreamExtraAkka

  def execPOSTSourceRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    source: Source[ByteString, _],
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse]
}
