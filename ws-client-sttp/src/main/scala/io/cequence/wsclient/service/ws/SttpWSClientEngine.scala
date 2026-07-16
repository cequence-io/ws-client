package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.spi.TransportSettings
import play.api.libs.json.{JsValue, Json}
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.model.{MediaType, Uri}

import java.io.File
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * A SITE-STATELESS engine backed by sttp (client4) - one adapter that unlocks every sttp
 * `Future` backend (JDK HttpClient, OkHttp, Armeria, Pekko-HTTP, ...). It owns a single
 * `Backend[Future]` and serves any number of sites: every call takes a
 * [[io.cequence.wsclient.domain.SiteBinding]] carrying the base URL, auth context, error
 * recovery, and logging label for that call.
 *
 * No streaming support (`WSClientInputStreamExtraAkka`/`-Pekko` /
 * `WSClientOutputStreamExtraAkka`/`-Pekko`).
 *
 * Timeout mapping: sttp exposes a single per-request timeout, to which `requestTimeout` is
 * mapped; `readTimeout`/`pooledConnectionIdleTimeout` are ignored, and `connectTimeout` can
 * only be set on the backend itself (the discovery provider does this via `TransportSettings`;
 * with `SttpWSClientEngine.apply` configure it via `BackendOptions` on your own backend).
 *
 * `close()` closes the backend.
 */
final class SttpWSClientEngine private[ws] (
  private[ws] val backend: Backend[Future],
  override val transportSettings: TransportSettings
)(
  implicit override protected val ec: ExecutionContext
) extends WSClientEngine {

  // the copy always builds its own OWNED backend from the given settings (via
  // SttpWSClientEngine.buildBackend) and closes it independently of this engine - the
  // existing backend may be caller-injected (its connect timeout/proxy config is opaque), so
  // reflecting new settings requires a fresh backend rather than reusing this one. There is no
  // actor system here (sttp uses only `ec`), so `reuseExecContext` makes no difference either
  // way: the copy always inherits `this.ec`
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): SttpWSClientEngine =
    new SttpWSClientEngine(
      SttpWSClientEngine.buildBackend(transportSettings),
      transportSettings
    )(ec)

  private val defaultRequestTimeout: Int = 120 * 1000 // two minutes

  // resolved per field so a partially-specified Timeouts (e.g. only readTimeout) never
  // silently drops the request timeout
  private val timeouts: Timeouts = {
    val expl = transportSettings.timeouts
    expl.copy(requestTimeout = expl.requestTimeout.orElse(Some(defaultRequestTimeout)))
  }

  private def serviceName(site: SiteBinding): String =
    site.label.getOrElse(getClass.getSimpleName)

  private def recoverErrors(
    site: SiteBinding
  ): String => PartialFunction[Throwable, RichResponse] =
    SiteBinding.resolveRecoverErrors(
      site.recoverErrors,
      SttpWSClientEngine.defaultRecoverErrors
    )

  /////////
  // GET //
  /////////

  override def execGETRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      baseRequest(site, extraHeaders).get(uri(site, endPoint, endPointParam, params)),
      Some(endPoint),
      acceptableStatusCodes
    )

  //////////
  // POST //
  //////////

  override def execPOSTRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execPOSTBodyRich(
      site,
      endPoint,
      endPointParam,
      params,
      toJsBodyObject(bodyParams),
      extraHeaders,
      acceptableStatusCodes
    )

  override def execPOSTBodyRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      withJsonBody(
        baseRequest(site, extraHeaders).post(uri(site, endPoint, endPointParam, params)),
        body
      ),
      Some(endPoint),
      acceptableStatusCodes
    )

  override def execPOSTMultipartRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    fileParams: Seq[(String, File, Option[String])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes,
    useInMemoryBody: Boolean = false
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] =
    execRequest(
      site,
      withMultipartBody(
        baseRequest(site, extraHeaders).post(uri(site, endPoint, endPointParam, params)),
        fileParams,
        bodyParams,
        useInMemoryBody
      ),
      Some(endPoint),
      acceptableStatusCodes
    )

  override def execPOSTURLEncodedRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val bodyData = bodyParams.collect { case (key, Some(value)) =>
      (key, value.toString)
    }

    execRequest(
      site,
      baseRequest(site, extraHeaders)
        .post(uri(site, endPoint, endPointParam, params))
        .body(bodyData.toMap),
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPOSTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      baseRequest(site, extraHeaders)
        .post(uri(site, endPoint, endPointParam, urlParams))
        .body(file),
      Some(endPoint),
      acceptableStatusCodes
    )

  ////////////
  // DELETE //
  ////////////

  override def execDELETERich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      baseRequest(site, extraHeaders).delete(uri(site, endPoint, endPointParam, params)),
      Some(endPoint),
      acceptableStatusCodes
    )

  ///////////
  // PATCH //
  ///////////

  override def execPATCHRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      withJsonBody(
        baseRequest(site, extraHeaders).patch(uri(site, endPoint, endPointParam, params)),
        toJsBodyObject(bodyParams)
      ),
      Some(endPoint),
      acceptableStatusCodes
    )

  /////////
  // PUT //
  /////////

  override def execPUTRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execPUTBodyRich(
      site,
      endPoint,
      endPointParam,
      params,
      toJsBodyObject(bodyParams),
      extraHeaders,
      acceptableStatusCodes
    )

  override def execPUTBodyRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    body: JsValue,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      withJsonBody(
        baseRequest(site, extraHeaders).put(uri(site, endPoint, endPointParam, params)),
        body
      ),
      Some(endPoint),
      acceptableStatusCodes
    )

  override def execPUTMultipartRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    fileParams: Seq[(String, File, Option[String])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes,
    useInMemoryBody: Boolean = false
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] =
    execRequest(
      site,
      withMultipartBody(
        baseRequest(site, extraHeaders).put(uri(site, endPoint, endPointParam, params)),
        fileParams,
        bodyParams,
        useInMemoryBody
      ),
      Some(endPoint),
      acceptableStatusCodes
    )

  override def execPUTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      baseRequest(site, extraHeaders)
        .put(uri(site, endPoint, endPointParam, urlParams))
        .body(file),
      Some(endPoint),
      acceptableStatusCodes
    )

  /////////
  // Aux //
  /////////

  private def baseRequest(
    site: SiteBinding,
    extraHeaders: Seq[(String, String)]
  ) = {
    val requestContext = site.requestContextFn()

    val withHeaders = (requestContext.authHeaders ++ extraHeaders).foldLeft(basicRequest) {
      case (request, (key, value)) => request.header(key, value)
    }

    withHeaders
      .readTimeout(timeouts.requestTimeout.getOrElse(defaultRequestTimeout).millis)
      .response(asStringAlways)
  }

  private def uri(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])]
  ): Uri = {
    val requestContext = site.requestContextFn()

    val extraStringParams = requestContext.extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }
    val definedParams = (params ++ extraStringParams).collect { case (key, Some(value)) =>
      (key, value.toString)
    }

    Uri.unsafeParse(site.createURL(Some(endPoint), endPointParam)).addParams(definedParams: _*)
  }

  private def withJsonBody(
    request: Request[String],
    body: JsValue
  ): Request[String] =
    request.body(Json.stringify(body)).contentType(MediaType.ApplicationJson)

  private def withMultipartBody(
    request: Request[String],
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])],
    useInMemoryBody: Boolean
  )(
    implicit filePartToContent: FilePart => String
  ): Request[String] =
    if (useInMemoryBody) {
      // fully materialized body with a known Content-Length - avoids chunked transfer
      // encoding, which some backends/proxies (e.g. Cloudflare) reject
      val formData = MultipartFormData(
        dataParts = bodyParams.collect { case (key, Some(value)) =>
          (key, Seq(value.toString))
        }.toMap,
        files = fileParams.map { case (key, file, headerFileName) =>
          FilePart(key, file.getPath, headerFileName)
        }
      )
      val boundary = MultipartBodyBuilder.generateBoundary

      request
        .body(MultipartBodyBuilder.buildInMemory(formData, boundary))
        .contentType(MultipartBodyBuilder.contentTypeHeaderValue(boundary))
    } else
      request.multipartBody(multipartParts(fileParams, bodyParams))

  private def multipartParts(
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])]
  )(
    implicit filePartToContent: FilePart => String
  ) = {
    val dataParts = bodyParams.collect { case (key, Some(value)) =>
      multipart(key, value.toString)
    }

    val fileParts = fileParams.map { case (key, file, headerFileName) =>
      val filePart = FilePart(key, file.getPath, headerFileName)
      val base = multipartFile(key, file).fileName(filePart.filenameAux)

      parseContentType(filePartToContent(filePart)).map(base.contentType).getOrElse(base)
    }

    // immutable Seq required by multipartBody on Scala 2.12
    (dataParts ++ fileParts).toList
  }

  // the (implicit) file-part-to-content function produces a raw header line
  // ("content-type: <media type>\r\n") or an empty string - extract the media type from it
  private def parseContentType(headerLine: String): Option[MediaType] = {
    val prefix = s"${HttpHeaderNames.CONTENT_TYPE}: "
    val trimmed = headerLine.stripSuffix("\r\n")
    if (trimmed.startsWith(prefix))
      MediaType.parse(trimmed.stripPrefix(prefix)).toOption
    else
      None
  }

  private def execRequest(
    site: SiteBinding,
    request: Request[String],
    endPointForLogging: Option[String],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] =
    request
      .send(backend)
      .map { rawResponse =>
        val response =
          if (acceptableStatusCodes.contains(rawResponse.code.code))
            Some(StringBackedResponse(rawResponse.body, serviceName(site), endPointForLogging))
          else None

        SimpleRichResponse(
          response,
          status = StatusData(rawResponse.code.code, rawResponse.body),
          headers = rawResponse.headers.groupBy(_.name).map { case (name, headers) =>
            name -> headers.map(_.value)
          }
        )
      }
      .recover(recoverErrors(site)(serviceAndEndpoint(site, endPointForLogging)))

  private def serviceAndEndpoint(
    site: SiteBinding,
    endPointForLogging: Option[String]
  ) =
    s"${serviceName(site)}${endPointForLogging.map("." + _).getOrElse("")}"

  override def close(): Unit = {
    backend.close()
    ()
  }
}

object SttpWSClientEngine {

  import ThrowableUtil.hasCause

  def apply(
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit backend: Backend[Future],
    ec: ExecutionContext
  ): SttpWSClientEngine =
    new SttpWSClientEngine(backend, transportSettings)

  // hoisted out of SttpWSClientEngineProvider so `copy` can build a freshly-owned backend from
  // new settings too - connect timeout and proxy are backend-level options in sttp - honor
  // them here, where the backend is built (an engine can only set the per-request timeout per
  // call)
  private[ws] def buildBackend(settings: TransportSettings): Backend[Future] = {
    val optionsWithTimeout = settings.timeouts.connectTimeout
      .map(ms => BackendOptions.Default.copy(connectionTimeout = ms.millis))
      .getOrElse(BackendOptions.Default)

    val options = settings.proxyURL.map { proxyUrl =>
      val (host, port) = ProxyUrlUtil.hostAndPort(proxyUrl)
      optionsWithTimeout.copy(
        proxy = Some(BackendOptions.Proxy(host, port, BackendOptions.ProxyType.Http))
      )
    }.getOrElse(optionsWithTimeout)

    HttpClientFutureBackend(options)
  }

  private[ws] def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
    (serviceEndPointName: String) =>
      {
        // sttp wraps backend exceptions in SttpClientException - inspect the cause chain
        case e: Exception
            if hasCause(e, classOf[TimeoutException]) ||
              hasCause(e, classOf[java.net.http.HttpTimeoutException]) =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}."
          )
        // the default (JDK HttpClient) backend reports a DNS failure as ConnectException
        // caused by UnresolvedAddressException, never as UnknownHostException
        case e: Exception
            if hasCause(e, classOf[UnknownHostException]) ||
              hasCause(e, classOf[UnresolvedAddressException]) =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}."
          )
      }
  }
}
