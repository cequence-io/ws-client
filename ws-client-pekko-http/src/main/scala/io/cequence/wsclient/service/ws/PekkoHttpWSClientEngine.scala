package io.cequence.wsclient.service.ws

import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.{
  JsonStreamFrames,
  SourcePublishersPekko,
  WSClientEngine,
  WSClientInputStreamExtraPekko,
  WSClientOutputStreamExtraPekko
}
import io.cequence.wsclient.service.spi.TransportSettings
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.settings.{
  ClientConnectionSettings,
  ConnectionPoolSettings
}
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl.Framing.FramingException
import org.apache.pekko.stream.scaladsl.{Framing, Source}
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}

import java.io.File
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * An engine backed directly by the pekko-http client - no Play WS / shaded AsyncHttpClient
 * layer. Supports input streaming (`execPOSTSourceRich`) via chunked request entities and
 * output streaming (`execJsonStream` / `execRawStream`) for SSE.
 *
 * SITE-STATELESS: the engine owns the `Http()` client, its connection pool, and the actor
 * system/materializer they run on; it holds no per-service state at all. Every method takes a
 * [[io.cequence.wsclient.domain.SiteBinding]] (base URL, auth context, error recovery, label)
 * as its first parameter, so ONE engine instance serves any number of sites/providers.
 *
 * @param ownsSystem
 *   when `true`, `close()` drains the shared `Http()` connection pools and terminates the
 *   actor system - set by the discovery provider, whose engines own a dedicated system. When
 *   `false` (the default - e.g. for a caller-supplied system shared with other clients),
 *   `close()` is a deliberate no-op: tearing down a system this engine does not own would
 *   disrupt unrelated pekko-http clients running on it.
 * @param newExecEnv
 *   an optional factory for a brand new, owned actor system - threaded through by
 *   [[copy]](reuseExecContext = false) to build a fully independent engine. Engines built
 *   without one (e.g. via [[PekkoHttpWSClientEngine.apply]] on a caller-supplied system) throw
 *   a [[io.cequence.wsclient.domain.CequenceWSException]] if such a copy is requested. Set by
 *   the discovery provider (`PekkoHttpWSClientEngineProvider`).
 */
class PekkoHttpWSClientEngine(
  val transportSettings: TransportSettings = TransportSettings(),
  ownsSystem: Boolean = false,
  newExecEnv: Option[() => ActorSystem] = None
)(
  implicit val system: ActorSystem,
  val ec: ExecutionContext
) extends WSClientEngine
    with WSClientInputStreamExtraPekko
    with WSClientOutputStreamExtraPekko {

  private val logger = LoggerFactory.getLogger("PekkoHttpWSClientEngine")

  private val defaultRequestTimeout: Int = 120 * 1000 // two minutes
  private val defaultReadoutTimeout: Int = 120 * 1000 // two minutes

  // the system-wide materializer; its lifecycle is bound to the actor system
  private implicit lazy val materializer: Materializer =
    SystemMaterializer(system).materializer

  private lazy val http = Http()

  private lazy val poolSettings: ConnectionPoolSettings = {
    // pekko-http only supports CONNECT-tunneling proxies (ClientTransport.httpsProxy), which
    // plain-http requests routinely can't use - so the proxy URL is not honored here
    transportSettings.proxyURL.foreach(proxyUrl =>
      logger.warn(
        s"PekkoHttpWSClientEngine: TransportSettings.proxyURL ('$proxyUrl') is not supported by the pekko-http engine and is IGNORED - use the Play, jdk, or sttp engine for proxied traffic."
      )
    )

    val base = ConnectionPoolSettings(system)
    transportSettings.timeouts.connectTimeout
      .map(ms =>
        base.withConnectionSettings(
          ClientConnectionSettings(system).withConnectingTimeout(ms.millis)
        )
      )
      .getOrElse(base)
  }

  // resolved per field so a partially-specified Timeouts (e.g. only connectTimeout) never
  // silently drops the request timeout
  private val timeouts: Timeouts = {
    val expl = transportSettings.timeouts
    expl.copy(
      requestTimeout = expl.requestTimeout.orElse(Some(defaultRequestTimeout)),
      readTimeout = expl.readTimeout.orElse(Some(defaultReadoutTimeout))
    )
  }

  private def serviceName(site: SiteBinding): String =
    site.label.getOrElse(getClass.getSimpleName)

  // composes the site's custom error recovery (if any) over the engine's default
  // transport-failure normalization - see SiteBinding.resolveRecoverErrors
  private def recoverErrors(
    site: SiteBinding
  ): String => PartialFunction[Throwable, RichResponse] =
    SiteBinding.resolveRecoverErrors(
      site.recoverErrors,
      PekkoHttpWSClientEngine.defaultRecoverErrors
    )

  //////////
  // Copy //
  //////////

  /**
   * A new engine with the given transport settings - always builds its own `Http()` connection
   * pool, so it is closed independently of this engine.
   *
   *   - `reuseExecContext = true` (default): the copy runs on THIS engine's actor system /
   *     dispatcher and does NOT own it (`ownsSystem = false`), so its `close()` only drains
   *     its own connection pool - the shared system is untouched. Close order matters only for
   *     the system itself: if this engine owns its system, close the copies BEFORE (or without
   *     regard to) this engine, but never rely on a copy after this engine has terminated the
   *     system.
   *   - `reuseExecContext = false`: the copy creates and owns a BRAND NEW actor system, built
   *     via the `newExecEnv` factory this engine was constructed with - fully independent,
   *     terminated by the copy's own `close()`. Only supported when a factory was supplied
   *     (e.g. by the discovery provider); an engine built on a caller-supplied system (via
   *     [[PekkoHttpWSClientEngine.apply]]) throws a
   *     [[io.cequence.wsclient.domain.CequenceWSException]].
   *
   * Either way, the `newExecEnv` factory (if any) is threaded through to the copy, so further
   * `copy(reuseExecContext = false)` calls on it keep working.
   */
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): PekkoHttpWSClientEngine =
    if (reuseExecContext)
      new PekkoHttpWSClientEngine(transportSettings, ownsSystem = false, newExecEnv)(
        system,
        ec
      )
    else {
      val mkSystem = newExecEnv.getOrElse(
        throw new CequenceWSException(
          "this engine's execution environment was caller-supplied - create a new engine " +
            "via the explicit factory or the discovery registry"
        )
      )
      val newSystem = mkSystem()
      new PekkoHttpWSClientEngine(transportSettings, ownsSystem = true, newExecEnv)(
        newSystem,
        newSystem.dispatcher
      )
    }

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
      HttpMethods.GET,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      HttpEntity.Empty,
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
      HttpMethods.POST,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      jsonEntity(body),
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
      HttpMethods.POST,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      multipartEntity(fileParams, bodyParams, useInMemoryBody),
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
    val fields = bodyParams.collect { case (key, Some(value)) => (key, value.toString) }

    execRequest(
      site,
      HttpMethods.POST,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      FormData(fields: _*).toEntity,
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
      HttpMethods.POST,
      endPoint,
      endPointParam,
      urlParams,
      extraHeaders,
      HttpEntity.fromPath(ContentTypes.`application/octet-stream`, file.toPath),
      acceptableStatusCodes
    )

  override def execPOSTSourceRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    source: Source[ByteString, _],
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      HttpMethods.POST,
      endPoint,
      endPointParam,
      urlParams,
      extraHeaders,
      HttpEntity(ContentTypes.`application/octet-stream`, source),
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
      HttpMethods.DELETE,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      HttpEntity.Empty,
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
      HttpMethods.PATCH,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      jsonEntity(toJsBodyObject(bodyParams)),
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
      HttpMethods.PUT,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      jsonEntity(body),
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
      HttpMethods.PUT,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      multipartEntity(fileParams, bodyParams, useInMemoryBody),
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
      HttpMethods.PUT,
      endPoint,
      endPointParam,
      urlParams,
      extraHeaders,
      HttpEntity.fromPath(ContentTypes.`application/octet-stream`, file.toPath),
      acceptableStatusCodes
    )

  //////////////////////////
  // Streaming (output)   //
  //////////////////////////

  private val streamLogger = LoggerFactory.getLogger("PekkoHttpWSClientEngine")

  protected val defaultMaxFrameLength = JsonStreamFrames.DefaultMaxFrameLength

  override def execJsonStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    framingDelimiter: String,
    maxFrameLength: Option[Int],
    stripPrefix: Option[String],
    stripSuffix: Option[String]
  ): java.util.concurrent.Flow.Publisher[JsValue] =
    SourcePublishersPekko.deferred(
      execJsonStream(
        site,
        endPoint,
        method,
        endPointParam,
        params,
        bodyParams,
        extraHeaders,
        framingDelimiter,
        maxFrameLength,
        stripPrefix,
        stripSuffix
      )
    )

  override def execRawStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): java.util.concurrent.Flow.Publisher[java.nio.ByteBuffer] =
    SourcePublishersPekko.deferred(
      execRawStream(site, endPoint, method, endPointParam, params, bodyParams, extraHeaders)
        // normalize transport failures to the Cequence taxonomy on the PUBLISHER path only,
        // matching the jdk engine (the Source-typed execRawStream keeps its raw errors)
        .recover(handleException(site, endPoint))
        .map(_.asByteBuffer)
    )

  override def execJsonStream(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)],
    framingDelimiter: String,
    maxFrameLength: Option[Int],
    stripPrefix: Option[String],
    stripSuffix: Option[String]
  ): Source[JsValue, NotUsed] = {
    val source = execRawStream(
      site,
      endPoint,
      method,
      endPointParam,
      params,
      bodyParams,
      extraHeaders
    ).via(
      Framing.delimiter(
        ByteString(framingDelimiter),
        maxFrameLength.getOrElse(defaultMaxFrameLength),
        allowTruncation = true
      )
    ).map(parseFrame(stripPrefix, stripSuffix))
      .recover(handleException(site, endPoint))

    // take until the end of stream marked with the structural [DONE] sentinel
    source.takeWhile(_ != JsonStreamFrames.EndOfStream).collect {
      case JsonStreamFrames.JsonFrame(json) => json
    }
  }

  override def execRawStream(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): Source[ByteString, NotUsed] = {
    val httpMethod =
      HttpMethods.getForKey(method.toUpperCase).getOrElse(HttpMethod.custom(method))

    val entity: RequestEntity =
      if (bodyParams.nonEmpty) jsonEntity(toJsBodyObject(bodyParams)) else HttpEntity.Empty

    val request =
      buildRequest(site, httpMethod, endPoint, endPointParam, params, extraHeaders, entity)

    val source = singleRequestWithTimeout(request).map(_.entity.dataBytes)

    val svc = serviceAndEndpoint(site, Some(endPoint))

    Source
      .futureSource(source)
      .log(s"$svc: execRawStream failed")
      .recover { case e: Throwable =>
        streamLogger.error(s"$svc: execRawStream failed: ${e.getMessage}.")
        throw e
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  // frame semantics (data:-prefix, [DONE], strip, error mapping) live in the shared,
  // backend-agnostic JsonStreamFrames. The [DONE] sentinel stays a STRUCTURAL FrameResult
  // (never a JsValue element), so a legitimate JSON-string payload "[DONE]" cannot be
  // mistaken for it.
  private def parseFrame(
    stripPrefix: Option[String],
    stripSuffix: Option[String]
  )(
    byteString: ByteString
  ): JsonStreamFrames.FrameResult = {
    val string = byteString.utf8String

    streamLogger.debug(s"Unmarshalling JSON: $string")

    JsonStreamFrames.parseFrame(string, stripPrefix, stripSuffix)
  }

  protected def handleException[T](
    site: SiteBinding,
    endPoint: String
  ): PartialFunction[Throwable, T] = {
    val svc = serviceAndEndpoint(site, Some(endPoint))

    {
      case e: JsonParseException =>
        val message = s"$svc: Response is not a JSON. ${e.getMessage}."
        streamLogger.error(message)
        throw new CequenceWSException(message)
      case e: FramingException =>
        val message = s"$svc: Stream framing problem occurred. ${e.getMessage}."
        streamLogger.error(message)
        throw new CequenceWSException(message)
      case e: TimeoutException =>
        val message = s"$svc: Time out. ${e.getMessage}."
        streamLogger.error(message)
        throw new CequenceWSTimeoutException(message)
      case e: UnknownHostException =>
        val message = s"$svc: Host name cannot be resolved. ${e.getMessage}."
        streamLogger.error(message)
        throw new CequenceWSUnknownHostException(message)
      case e: Throwable =>
        val message = s"$svc: Fatal problem! ${e.getMessage}."
        streamLogger.error(message)
        throw new CequenceWSException(message)
    }
  }

  /////////
  // Aux //
  /////////

  private def jsonEntity(body: JsValue): RequestEntity =
    HttpEntity(ContentTypes.`application/json`, Json.stringify(body))

  private def multipartEntity(
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])],
    useInMemoryBody: Boolean
  )(
    implicit filePartToContent: FilePart => String
  ): RequestEntity = {
    val dataParts = bodyParams.collect { case (key, Some(value)) =>
      Multipart.FormData.BodyPart.Strict(key, HttpEntity(value.toString))
    }

    if (useInMemoryBody) {
      // strict (fully materialized) parts yield a strict entity with a Content-Length -
      // avoids chunked transfer encoding, which some backends/proxies (e.g. Cloudflare) reject
      val fileParts = fileParams.map { case (key, file, headerFileName) =>
        val filePart = FilePart(key, file.getPath, headerFileName)
        val contentType = parseContentType(filePartToContent(filePart))
          .getOrElse(ContentTypes.`application/octet-stream`)

        Multipart.FormData.BodyPart.Strict(
          key,
          HttpEntity.Strict(
            contentType,
            ByteString(java.nio.file.Files.readAllBytes(file.toPath))
          ),
          Map("filename" -> filePart.filenameAux)
        )
      }

      Multipart.FormData((dataParts ++ fileParts): _*).toEntity
    } else {
      val fileParts = fileParams.map { case (key, file, headerFileName) =>
        val filePart = FilePart(key, file.getPath, headerFileName)
        val contentType = parseContentType(filePartToContent(filePart))
          .getOrElse(ContentTypes.`application/octet-stream`)

        Multipart.FormData.BodyPart(
          key,
          HttpEntity.fromPath(contentType, file.toPath),
          Map("filename" -> filePart.filenameAux)
        )
      }

      Multipart.FormData((dataParts ++ fileParts): _*).toEntity()
    }
  }

  // the (implicit) file-part-to-content function produces a raw header line
  // ("content-type: <media type>\r\n") or an empty string - extract the content type from it
  private def parseContentType(headerLine: String): Option[ContentType] = {
    val prefix = s"${HttpHeaderNames.CONTENT_TYPE}: "
    val trimmed = headerLine.stripSuffix("\r\n")
    if (trimmed.startsWith(prefix))
      ContentType.parse(trimmed.stripPrefix(prefix)).toOption
    else
      None
  }

  private def buildRequest(
    site: SiteBinding,
    method: HttpMethod,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    entity: RequestEntity
  ): HttpRequest = {
    val requestContext = site.requestContextFn()

    val extraStringParams = requestContext.extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }
    val definedParams = (params ++ extraStringParams).collect { case (key, Some(value)) =>
      (key, value.toString)
    }

    // merge with any query already embedded in coreUrl/endPoint (e.g. ?api-version=...) -
    // withQuery alone would REPLACE it
    val baseUri = Uri(site.createURL(Some(endPoint), endPointParam))
    val uri =
      if (definedParams.isEmpty) baseUri
      else baseUri.withQuery(Uri.Query(baseUri.query() ++ definedParams: _*))

    val allHeaders = requestContext.authHeaders ++ extraHeaders

    // Content-Type is entity-bound in pekko-http - passed as a RawHeader it would be dropped
    // with a warning; apply it to the entity instead (the caller-provided value wins)
    val finalEntity = allHeaders
      .filter(h => h._1.equalsIgnoreCase("Content-Type"))
      .lastOption
      .flatMap { case (_, value) => ContentType.parse(value).toOption }
      .map(entity.withContentType(_))
      .getOrElse(entity)

    val headers = allHeaders.collect {
      case (key, value) if !key.equalsIgnoreCase("Content-Type") => RawHeader(key, value)
    }.toList

    HttpRequest(method = method, uri = uri, headers = headers, entity = finalEntity)
  }

  private def singleRequestWithTimeout(request: HttpRequest): Future[HttpResponse] = {
    val responseFuture = http.singleRequest(request, settings = poolSettings)

    timeouts.requestTimeout.map { ms =>
      val result = Future.firstCompletedOf(
        Seq(
          responseFuture,
          after(ms.millis, system.scheduler)(
            Future.failed(new TimeoutException(s"Request timed out after $ms ms"))
          )
        )
      )
      // if the timer won, the pool slot stays occupied until the late response's entity is
      // consumed - discard it when it eventually arrives, or bursts exhaust the pool
      result.failed.foreach(_ => responseFuture.foreach(_.entity.discardBytes()))
      result
    }.getOrElse(responseFuture)
  }

  private def execRequest(
    site: SiteBinding,
    method: HttpMethod,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    entity: RequestEntity,
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = {
    val request =
      buildRequest(site, method, endPoint, endPointParam, params, extraHeaders, entity)

    singleRequestWithTimeout(request).flatMap { rawResponse =>
      val readTimeout = timeouts.readTimeout.getOrElse(defaultReadoutTimeout)

      rawResponse.entity.toStrict(readTimeout.millis).map { strictEntity =>
        val body = strictEntity.data.utf8String

        val response =
          if (acceptableStatusCodes.contains(rawResponse.status.intValue))
            Some(StringBackedResponse(body, serviceName(site), Some(endPoint)))
          else None

        SimpleRichResponse(
          response,
          status = StatusData(rawResponse.status.intValue, body),
          headers = rawResponse.headers.groupBy(_.name).map { case (name, headers) =>
            name -> headers.map(_.value)
          }
        )
      }
    }.recover(recoverErrors(site)(serviceAndEndpoint(site, Some(endPoint))))
  }

  protected def serviceAndEndpoint(
    site: SiteBinding,
    endPointForLogging: Option[String]
  ): String =
    s"${serviceName(site)}${endPointForLogging.map("." + _).getOrElse("")}"

  ///////////
  // Close //
  ///////////

  private val closed = new AtomicBoolean(false)

  // this engine shares the actor system's Http() extension and its connection pools - shutting
  // them down here would disrupt unrelated pekko-http clients on the same system unless this
  // engine OWNS the system (the discovery provider's case). Idempotent either way
  override def close(): Unit =
    if (ownsSystem && closed.compareAndSet(false, true)) {
      http.shutdownAllConnectionPools().onComplete(_ => system.terminate())
      ()
    }
}

object PekkoHttpWSClientEngine {

  /**
   * Creates a self-contained engine on the given (caller-supplied) actor system - `ownsSystem`
   * is `false`, matching a shared/caller-owned system: this engine's `close()` is a deliberate
   * no-op, the system remains the caller's responsibility. The discovery provider
   * (`PekkoHttpWSClientEngineProvider`) instead constructs the engine directly with
   * `ownsSystem = true` on a dedicated system it created for that purpose.
   */
  def apply(
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit system: ActorSystem,
    ec: ExecutionContext
  ): PekkoHttpWSClientEngine =
    new PekkoHttpWSClientEngine(transportSettings)(system, ec)

  private[ws] def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
    (serviceEndPointName: String) =>
      {
        case e: TimeoutException =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}."
          )
        case e: Exception
            if ThrowableUtil.hasCause(e, classOf[UnknownHostException]) ||
              ThrowableUtil.hasCause(e, classOf[UnresolvedAddressException]) =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}."
          )
      }
  }
}
