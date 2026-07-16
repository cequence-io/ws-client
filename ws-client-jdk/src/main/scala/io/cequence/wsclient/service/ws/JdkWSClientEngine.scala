package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.{
  JsonStreamFrames,
  WSClientEngine,
  WSClientOutputStreamCore
}
import io.cequence.wsclient.service.spi.TransportSettings
import io.cequence.wsclient.stream.{
  DeferredPublisher,
  ErrorMappedPublisher,
  FuturePublisher,
  StreamTransformers,
  TransformPublisher
}
import play.api.libs.json.{JsValue, Json}

import java.io.File
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.net.{ConnectException, URI, URLEncoder, UnknownHostException}
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CompletionException
import scala.concurrent.{blocking, ExecutionContext, Future, Promise}

/**
 * A dependency-free, SITE-STATELESS engine backed by the JDK 11+ `java.net.http.HttpClient` -
 * needs only `ws-client-core` (no Akka/Pekko/Play). It owns a single, lazily-built
 * `HttpClient` (connect timeout + proxy sourced from `transportSettings`) and serves any
 * number of sites: every call takes a [[io.cequence.wsclient.domain.SiteBinding]] carrying the
 * base URL, auth context, error recovery, and logging label for that call.
 *
 * Notes:
 *   - multipart bodies are always materialized in memory (the `useInMemoryBody` flag is
 *     effectively always on)
 *   - OUTPUT streaming is supported through the backend-agnostic
 *     [[io.cequence.wsclient.service.WSClientOutputStreamCore]] (`java.util.concurrent.Flow`)
 *     contract - `java.net.http` exposes response bodies as Flow publishers natively. The
 *     akka/pekko `Source`-typed `WSClientOutputStreamExtraAkka`/`-Pekko` traits are NOT
 *     implemented (this module is stream-library-free); akka/pekko consumers can wrap via
 *     `Source.fromPublisher`.
 *   - there is no INPUT streaming support (`WSClientInputStreamExtraAkka`/`-Pekko`)
 *   - `Timeouts.readTimeout` and `pooledConnectionIdleTimeout` are ignored - `java.net.http`
 *     has no per-read/idle timeout; `requestTimeout` covers the whole exchange (which for an
 *     indefinite SSE stream means the request timeout bounds the stream duration - widen it
 *     for long-lived streams, mirroring the Play/AHC engines' behavior)
 *   - `close()` is a no-op - `java.net.http.HttpClient` needs no explicit teardown - but is
 *     trivially idempotent, for consistency with the other backends
 */
final class JdkWSClientEngine private[ws] (
  override val transportSettings: TransportSettings
)(
  implicit override protected val ec: ExecutionContext
) extends WSClientEngine
    with WSClientOutputStreamCore {

  // the copy always builds its own HttpClient from the given settings, so it is closed
  // independently of this engine - there is no actor system here (jdk uses only `ec`), so
  // `reuseExecContext` makes no difference either way: the copy always inherits `this.ec`
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): JdkWSClientEngine = new JdkWSClientEngine(transportSettings)(ec)

  private val defaultConnectTimeout: Int = 5 * 1000 // matches the Play engines' AHC default
  private val defaultRequestTimeout: Int = 120 * 1000 // two minutes

  private lazy val client: HttpClient = {
    val builder = HttpClient.newBuilder()
    val connectTimeout =
      transportSettings.timeouts.connectTimeout.orElse(Some(defaultConnectTimeout))
    connectTimeout.foreach(ms => builder.connectTimeout(Duration.ofMillis(ms)))
    transportSettings.proxyURL.foreach { proxyUrl =>
      val (host, port) = ProxyUrlUtil.hostAndPort(proxyUrl)
      builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port)))
    }
    builder.build()
  }

  // resolved per field - java.net.http has no defaults of its own, so a partially-specified
  // Timeouts (e.g. only readTimeout) must not silently drop the request timeout
  // (indefinite hang)
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
      JdkWSClientEngine.defaultRecoverErrors
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
      requestBuilder(site, endPoint, endPointParam, params, extraHeaders).GET(),
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
    execWithJsonBody(
      site,
      "POST",
      endPoint,
      endPointParam,
      params,
      body,
      extraHeaders,
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
    execWithMultipartBody(
      site,
      "POST",
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams,
      extraHeaders,
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
    val bodyString = bodyParams.collect { case (key, Some(value)) =>
      s"${encode(key)}=${encode(value.toString)}"
    }.mkString("&")

    val request = requestBuilder(
      site,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      defaultContentType = Some("application/x-www-form-urlencoded")
    ).method("POST", BodyPublishers.ofString(bodyString))

    execRequest(site, request, Some(endPoint), acceptableStatusCodes)
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
    execWithFileBody(
      site,
      "POST",
      endPoint,
      endPointParam,
      urlParams,
      file,
      extraHeaders,
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
      requestBuilder(site, endPoint, endPointParam, params, extraHeaders).DELETE(),
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
    execWithJsonBody(
      site,
      "PATCH",
      endPoint,
      endPointParam,
      params,
      toJsBodyObject(bodyParams),
      extraHeaders,
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
    execWithJsonBody(
      site,
      "PUT",
      endPoint,
      endPointParam,
      params,
      body,
      extraHeaders,
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
    execWithMultipartBody(
      site,
      "PUT",
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams,
      extraHeaders,
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
    execWithFileBody(
      site,
      "PUT",
      endPoint,
      endPointParam,
      urlParams,
      file,
      extraHeaders,
      acceptableStatusCodes
    )

  ////////////////////////
  // Streaming (output) //
  ////////////////////////

  override def execJsonStreamPublisher(
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
  ): java.util.concurrent.Flow.Publisher[JsValue] = {
    val framed = new TransformPublisher(
      execRawStreamPublisher(
        site,
        endPoint,
        method,
        endPointParam,
        params,
        bodyParams,
        extraHeaders
      ),
      () =>
        new StreamTransformers.DelimiterFramer(
          framingDelimiter.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          maxFrameLength.getOrElse(JsonStreamFrames.DefaultMaxFrameLength)
        )
    )

    val json = new TransformPublisher(
      framed,
      () => new StreamTransformers.JsonFrameParser(stripPrefix, stripSuffix)
    )

    new ErrorMappedPublisher(json, mapStreamError(site, endPoint))
  }

  override def execRawStreamPublisher(
    site: SiteBinding,
    endPoint: String,
    method: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    bodyParams: Seq[(String, Option[JsValue])],
    extraHeaders: Seq[(String, String)]
  ): java.util.concurrent.Flow.Publisher[java.nio.ByteBuffer] =
    // deferred: the request fires only when the first subscriber arrives (cold publisher),
    // mirroring the blueprint laziness of the Source-typed engines
    new DeferredPublisher(() => {
      val builder = requestBuilder(
        site,
        endPoint,
        endPointParam,
        params,
        extraHeaders,
        defaultContentType = if (bodyParams.nonEmpty) Some("application/json") else None
      )

      val request =
        if (bodyParams.nonEmpty)
          builder.method(
            method,
            BodyPublishers.ofString(Json.stringify(toJsBodyObject(bodyParams)))
          )
        else
          builder.method(method, BodyPublishers.noBody())

      val bodyPublisher =
        client
          .sendAsync(request.build(), BodyHandlers.ofPublisher())
          .thenApply[java.util.concurrent.Flow.Publisher[java.util.List[java.nio.ByteBuffer]]](
            response => response.body()
          )

      new ErrorMappedPublisher(
        new TransformPublisher(
          new FuturePublisher(bodyPublisher),
          () => new StreamTransformers.ByteBufferListFlatten
        ),
        mapStreamError(site, endPoint)
      )
    })

  // mirrors the akka/pekko engines' stream handleException taxonomy
  private def mapStreamError(
    site: SiteBinding,
    endPoint: String
  )(
    e: Throwable
  ): Throwable = {
    def label = serviceAndEndpoint(site, Some(endPoint))

    e match {
      case ce: CequenceWSException => ce
      // Reactive Streams contract violations (e.g. a second subscription to a one-shot
      // publisher) are caller errors, not transport failures - pass them through unmapped
      case ise: IllegalStateException => ise
      case e: HttpTimeoutException =>
        new CequenceWSTimeoutException(s"$label: Time out. ${e.getMessage}.")
      case e: java.util.concurrent.TimeoutException =>
        new CequenceWSTimeoutException(s"$label: Time out. ${e.getMessage}.")
      case e: UnknownHostException =>
        new CequenceWSUnknownHostException(
          s"$label: Host name cannot be resolved. ${e.getMessage}."
        )
      case e: ConnectException
          if ThrowableUtil.hasCause(e, classOf[UnresolvedAddressException]) =>
        new CequenceWSUnknownHostException(
          s"$label: Host name cannot be resolved. ${e.getMessage}."
        )
      case e: Throwable =>
        new CequenceWSException(s"$label: Fatal problem! ${e.getMessage}.")
    }
  }

  /////////
  // Aux //
  /////////

  private def execWithJsonBody(
    site: SiteBinding,
    method: String,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    body: JsValue,
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = {
    val request = requestBuilder(
      site,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      defaultContentType = Some("application/json")
    ).method(method, BodyPublishers.ofString(Json.stringify(body)))

    execRequest(site, request, Some(endPoint), acceptableStatusCodes)
  }

  private def execWithFileBody(
    site: SiteBinding,
    method: String,
    endPoint: String,
    endPointParam: Option[String],
    urlParams: Seq[(String, Option[Any])],
    file: java.io.File,
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = {
    val request = requestBuilder(
      site,
      endPoint,
      endPointParam,
      urlParams,
      extraHeaders,
      defaultContentType = Some("application/octet-stream")
    ).method(method, BodyPublishers.ofFile(file.toPath))

    execRequest(site, request, Some(endPoint), acceptableStatusCodes)
  }

  private def execWithMultipartBody(
    site: SiteBinding,
    method: String,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int]
  )(
    implicit filePartToContent: FilePart => String
  ): Future[RichResponse] = {
    val formData = MultipartFormData(
      dataParts = bodyParams.collect { case (key, Some(value)) =>
        (key, Seq(value.toString))
      }.toMap,
      files = fileParams.map { case (key, file, headerFileName) =>
        FilePart(key, file.getPath, headerFileName)
      }
    )

    // materializing the body reads whole files - keep that blocking work off the caller's thread
    Future {
      blocking {
        val boundary = MultipartBodyBuilder.generateBoundary
        (boundary, MultipartBodyBuilder.buildInMemory(formData, boundary))
      }
    }.flatMap { case (boundary, bytes) =>
      val request = requestBuilder(
        site,
        endPoint,
        endPointParam,
        params,
        extraHeaders,
        defaultContentType = Some(MultipartBodyBuilder.contentTypeHeaderValue(boundary))
      ).method(method, BodyPublishers.ofByteArray(bytes))

      execRequest(site, request, Some(endPoint), acceptableStatusCodes)
    }
  }

  private def requestBuilder(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    defaultContentType: Option[String] = None
  ): HttpRequest.Builder = {
    val requestContext = site.requestContextFn()

    val extraStringParams = requestContext.extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }
    val paramsString = (params ++ extraStringParams).collect { case (tag, Some(value)) =>
      s"${encode(tag)}=${encode(value.toString)}"
    }.mkString("&")

    val url = {
      val base = site.createURL(Some(endPoint), endPointParam)
      if (paramsString.isEmpty) base
      // append with '&' when the URL already carries a query (e.g. Azure-style ?api-version=...)
      else base + (if (base.contains("?")) "&" else "?") + paramsString
    }

    val builder = HttpRequest.newBuilder(URI.create(url))
    timeouts.requestTimeout.foreach(ms => builder.timeout(Duration.ofMillis(ms)))

    val allHeaders = requestContext.authHeaders ++ extraHeaders
    allHeaders.foreach { case (key, value) =>
      // setHeader for Content-Type - `header` appends, which would send two Content-Type
      // headers once the body-implied default is added below (or the caller repeats it)
      if (isContentType(key)) builder.setHeader(key, value) else builder.header(key, value)
    }
    // the body-implied content type is a default only - a caller-provided header wins
    if (!allHeaders.exists(h => isContentType(h._1)))
      defaultContentType.foreach(builder.setHeader("Content-Type", _))
    builder
  }

  private def isContentType(headerName: String) = headerName.equalsIgnoreCase("Content-Type")

  private def execRequest(
    site: SiteBinding,
    request: HttpRequest.Builder,
    endPointForLogging: Option[String],
    acceptableStatusCodes: Seq[Int]
  ): Future[RichResponse] = {
    val promise = Promise[HttpResponse[String]]()

    client.sendAsync(request.build(), BodyHandlers.ofString()).whenComplete {
      (
        response,
        error
      ) =>
        if (error != null) promise.failure(unwrap(error)) else promise.success(response)
        ()
    }

    promise.future.map { rawResponse =>
      val response =
        if (acceptableStatusCodes.contains(rawResponse.statusCode))
          Some(
            StringBackedResponse(rawResponse.body, serviceName(site), endPointForLogging)
          )
        else None

      SimpleRichResponse(
        response,
        status = StatusData(rawResponse.statusCode, rawResponse.body),
        headers = headersToMap(rawResponse)
      )
    }.recover(recoverErrors(site)(serviceAndEndpoint(site, endPointForLogging)))
  }

  private def headersToMap(response: HttpResponse[String]): Map[String, Seq[String]] = {
    val builder = Map.newBuilder[String, Seq[String]]
    response.headers.map.forEach {
      (
        key,
        values
      ) =>
        val valuesBuilder = Seq.newBuilder[String]
        values.forEach(value => valuesBuilder += value)
        builder += key -> valuesBuilder.result()
    }
    builder.result()
  }

  private def unwrap(e: Throwable): Throwable = e match {
    case ce: CompletionException if ce.getCause != null => ce.getCause
    case other                                          => other
  }

  private def encode(value: String) = URLEncoder.encode(value, "UTF-8")

  private def serviceAndEndpoint(
    site: SiteBinding,
    endPointForLogging: Option[String]
  ) =
    s"${serviceName(site)}${endPointForLogging.map("." + _).getOrElse("")}"

  // java.net.http.HttpClient needs no explicit teardown - trivially idempotent
  override def close(): Unit = ()
}

object JdkWSClientEngine {

  def apply(
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit ec: ExecutionContext
  ): JdkWSClientEngine = new JdkWSClientEngine(transportSettings)

  private[ws] def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
    (serviceEndPointName: String) =>
      {
        case e: HttpTimeoutException =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}."
          )
        case e: java.util.concurrent.TimeoutException =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}."
          )
        case e: UnknownHostException =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}."
          )
        // java.net.http reports a DNS failure as ConnectException caused by
        // UnresolvedAddressException, never as UnknownHostException
        case e: ConnectException
            if ThrowableUtil.hasCause(e, classOf[UnresolvedAddressException]) =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}."
          )
      }
  }
}
