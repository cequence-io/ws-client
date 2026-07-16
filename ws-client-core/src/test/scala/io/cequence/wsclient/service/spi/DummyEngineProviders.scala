package io.cequence.wsclient.service.spi

import io.cequence.wsclient.domain.{
  RichResponse,
  SimpleRichResponse,
  SiteBinding,
  StatusData,
  StringBackedResponse
}
import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.ws.FilePart
import play.api.libs.json.{JsValue, Json}

import java.io.File
import scala.concurrent.{ExecutionContext, Future}

/**
 * A minimal engine for registry tests: GET succeeds and echoes back the calling site's
 * `coreUrl` (as `{"coreUrl": "..."}`) - so a test can drive one engine instance against
 * several [[SiteBinding]]s and assert that each call is correctly isolated to its own site.
 * Every other request fails with [[UnsupportedOperationException]].
 */
final class DummyEngine extends WSClientEngine {

  override protected implicit val ec: ExecutionContext = ExecutionContext.global

  override def transportSettings: TransportSettings = TransportSettings()

  // stateless (echoes the calling site's coreUrl per request, not a stored one) - a fresh
  // instance is a fully equivalent copy
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): DummyEngine = new DummyEngine

  private def unsupported: Future[RichResponse] =
    Future.failed(new UnsupportedOperationException("dummy engine"))

  override def execGETRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] =
    Future.successful(
      SimpleRichResponse(
        Some(StringBackedResponse(Json.obj("coreUrl" -> site.coreUrl).toString())),
        StatusData(200, "ok"),
        Map.empty
      )
    )

  override def execPOSTRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPOSTBodyRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    body: JsValue,
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPOSTMultipartRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int],
    useInMemoryBody: Boolean
  )(
    implicit filePartToContent: FilePart => String
  ): Future[RichResponse] = unsupported

  override def execPOSTURLEncodedRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPOSTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    urlParams: Seq[(String, Option[Any])],
    file: java.io.File,
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execDELETERich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPATCHRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPUTRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPUTBodyRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    body: JsValue,
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def execPUTMultipartRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int],
    useInMemoryBody: Boolean
  )(
    implicit filePartToContent: FilePart => String
  ): Future[RichResponse] = unsupported

  override def execPUTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    urlParams: Seq[(String, Option[Any])],
    file: java.io.File,
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = unsupported

  override def close(): Unit = ()
}

// registered in src/test/resources/META-INF/services

class DummyEngineProviderA extends WSClientEngineProvider {
  override val engineId = "dummy-a"
  override val priority = 1
  override def newEngine(settings: TransportSettings): WSClientEngine = new DummyEngine
}

class DummyEngineProviderB extends WSClientEngineProvider {
  override val engineId = "dummy-b"
  override val priority = 2
  override def newEngine(settings: TransportSettings): WSClientEngine = new DummyEngine
}

// NOT registered statically - loaded only through a custom class loader in the tie test

class DummyTieProvider1 extends WSClientEngineProvider {
  override val engineId = "tie-1"
  override val priority = 99
  override def newEngine(settings: TransportSettings): WSClientEngine = new DummyEngine
}

class DummyTieProvider2 extends WSClientEngineProvider {
  override val engineId = "tie-2"
  override val priority = 99
  override def newEngine(settings: TransportSettings): WSClientEngine = new DummyEngine
}
