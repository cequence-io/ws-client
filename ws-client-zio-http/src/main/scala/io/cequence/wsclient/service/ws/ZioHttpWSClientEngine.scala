package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.spi.TransportSettings
import io.cequence.wsclient.service.{
  JsonStreamFrames,
  WSClientEngine,
  WSClientOutputStreamCore
}
import io.cequence.wsclient.stream.{
  DeferredPublisher,
  ErrorMappedPublisher,
  StreamTransformers,
  TransformPublisher
}
import org.reactivestreams.FlowAdapters
import play.api.libs.json.{JsValue, Json}
import zio.http.netty.NettyConfig
import zio.http.netty.client.NettyClientDriver
import zio.http.{
  Body,
  Boundary,
  Client,
  ClientDriver,
  DnsResolver,
  Form,
  FormField,
  Header,
  Headers,
  MediaType,
  Method,
  QueryParams,
  Request,
  Response,
  URL,
  ZClient
}
import zio.interop.reactivestreams.Adapters
import zio.stream.ZStream
import zio.{Chunk, Exit, Runtime, Scope, Unsafe, ZEnvironment, ZIO, ZLayer}

import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{Channel, ChannelFactory, EventLoopGroup, MultiThreadIoEventLoopGroup}
import io.netty.util.concurrent.{DefaultThreadFactory, ThreadPerTaskExecutor}

import java.io.File
import java.lang.reflect.Constructor
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Flow, TimeoutException}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/**
 * A SITE-STATELESS engine on the [[https://zio.dev/zio-http zio-http]] client (Netty) and the
 * ZIO runtime. Every call takes a [[io.cequence.wsclient.domain.SiteBinding]] carrying the
 * base URL, auth context, error recovery and logging label; the `Future`-based engine contract
 * is served by running each ZIO effect to a `Future`.
 *
 *   - OUTPUT streaming via the backend-agnostic
 *     [[io.cequence.wsclient.service.WSClientOutputStreamCore]] (`java.util.concurrent.Flow`)
 *     contract, with the same framing / `[DONE]` pipeline as the jdk engine. The HTTP status
 *     is checked before any body byte is exposed: a non-2xx response fails the stream with a
 *     structured `CequenceWSHttpStatusException` (status code + bounded body) instead of being
 *     parsed as data. No input streaming (that capability is akka/pekko `Source`-typed).
 *   - timeouts: `connectTimeout` -> zio-http `connectionTimeout` (default 5 s); `readTimeout`
 * -> `idleTimeout`, a read-idle timeout on the channel (default 120 s - it also bounds the gap
 * between SSE events); `pooledConnectionIdleTimeout` -> the idle TTL of the dynamic connection
 * pool (default 60 s); `requestTimeout` (default 120 s) is applied per call and bounds the
 * whole exchange for plain calls, but only the wait for the response headers for streams (a
 * long-lived stream is bounded by the read-idle timeout instead)
 *   - redirects are never followed; query-param values are taken raw and percent-encoded (the
 *     jdk/sttp/pekko-http contract)
 *   - `proxyURL` is honored through Netty's HTTP proxy handler, which tunnels via `CONNECT`.
 *     Unlike the Play/jdk/sttp engines, the TARGET host name is still resolved through local
 *     DNS (the tunnel goes to the resolved address) - on networks where only the proxy can
 *     resolve external names, use one of those engines
 *   - a caller-provided `Content-Type` (auth or extra headers) overrides the default of JSON,
 *     file and URL-encoded bodies; multipart bodies keep their generated boundary type
 *   - multipart file parts are sent under the file's base name (or the given display name) -
 *     never the local path
 *
 * Lifecycle: engines created by discovery (`WSClientEngineRegistry`) OWN their client stack -
 * a Netty driver (event loops on DAEMON threads, so a leaked engine cannot block JVM exit, and
 * a DNS resolver) plus the client's connection pool - and release it on `close()`
 * (idempotent). Engines built on a caller-supplied `Client` (`apply` / `layer`) never close
 * it.
 */
final class ZioHttpWSClientEngine private[ws] (
  client: Client,
  runtime: Runtime[Any],
  override val transportSettings: TransportSettings,
  owned: Option[ZioHttpWSClientEngine.OwnedStack]
)(
  implicit override protected val ec: ExecutionContext
) extends WSClientEngine
    with WSClientOutputStreamCore
    with EngineSupport {

  import ZioHttpWSClientEngine._

  /**
   * A new engine with its own client (connection pool) built from `transportSettings`, closed
   * independently of this one. With `reuseExecContext = true` (default) and an OWNED stack,
   * the copy shares this engine's Netty driver (event loops, DNS resolver) - close this engine
   * after its copies. Otherwise (or for an engine on a caller-supplied `Client`) the copy gets
   * its own complete, owned stack.
   */
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): ZioHttpWSClientEngine = {
    val stack = owned.filter(_ => reuseExecContext) match {
      case Some(original) => clientStack(original.driverEnvironment, transportSettings)
      case None           => fullStack(transportSettings)
    }
    fromStack(stack, transportSettings)
  }

  private val timeouts: Timeouts = resolvedTimeouts(transportSettings)

  private val requestTimeout: zio.Duration =
    zio.Duration.fromMillis(timeouts.requestTimeout.getOrElse(0).toLong)

  // deadline for reading a non-2xx stream's body (for the error message only)
  private val errorBodyReadTimeout: zio.Duration =
    zio.Duration.fromMillis(
      math.min(requestTimeout.toMillis, EngineSupport.ErrorBodyReadTimeoutMs.toLong)
    )

  override protected def defaultRecoverErrors
    : String => PartialFunction[Throwable, RichResponse] =
    ZioHttpWSClientEngine.defaultRecoverErrors

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
      Method.GET,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      noBody
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
      Method.POST,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      jsonBody(body)
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
      Method.POST,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      multipartBody(fileParams, bodyParams, useInMemoryBody)
    )

  override def execPOSTURLEncodedRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequest(
      site,
      Method.POST,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      urlEncodedBody(bodyParams)
    )

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
      Method.POST,
      endPoint,
      endPointParam,
      urlParams,
      extraHeaders,
      acceptableStatusCodes,
      fileBody(file)
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
      Method.DELETE,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      noBody
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
      Method.PATCH,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      jsonBody(toJsBodyObject(bodyParams))
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
      Method.PUT,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      jsonBody(body)
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
      Method.PUT,
      endPoint,
      endPointParam,
      params,
      extraHeaders,
      acceptableStatusCodes,
      multipartBody(fileParams, bodyParams, useInMemoryBody)
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
      Method.PUT,
      endPoint,
      endPointParam,
      urlParams,
      extraHeaders,
      acceptableStatusCodes,
      fileBody(file)
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
  ): Flow.Publisher[JsValue] = {
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
          framingDelimiter.getBytes(StandardCharsets.UTF_8),
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
  ): Flow.Publisher[ByteBuffer] =
    // deferred: the request fires only when the first subscriber arrives (cold publisher),
    // and a second subscription is rejected (single-shot)
    new DeferredPublisher(() => {
      val label = serviceAndEndpoint(site, Some(endPoint))
      val body = if (bodyParams.nonEmpty) jsonBody(toJsBodyObject(bodyParams)) else noBody

      val bytes: ZStream[Any, Throwable, ByteBuffer] = ZStream.unwrapScoped[Any] {
        buildRequest(
          site,
          Method.fromString(method),
          endPoint,
          endPointParam,
          params,
          extraHeaders,
          body
        ).flatMap(request =>
          ZClient.streaming(request).provideSomeEnvironment[Scope](_.add[Client](client))
        ).timeoutFail(timeoutException(label))(requestTimeout)
          .flatMap { response =>
            if (response.status.isSuccess)
              ZIO.succeed(
                response.body.asStream.chunks.map(chunk => ByteBuffer.wrap(chunk.toArray))
              )
            else
              // never expose an error page as stream data - fail with a bounded diagnostic:
              // at most EngineSupport.MaxErrorBodyBytes are read, within the error-body read
              // timeout, so a huge or never-ending error body is neither buffered nor able to
              // stall the stream
              response.body.asStream
                .take(EngineSupport.MaxErrorBodyBytes)
                .runCollect
                .timeout(errorBodyReadTimeout)
                .map(bytes =>
                  new String(bytes.getOrElse(Chunk.empty).toArray, StandardCharsets.UTF_8)
                )
                // a failed diagnostic read must not mask the HTTP status
                .orElseSucceed("")
                .map(errorBody =>
                  ZStream.fail(
                    EngineSupport.streamStatusException(label, response.status.code, errorBody)
                  )
                )
          }
      }

      val publisher = Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(Adapters.streamToPublisher(bytes)).getOrThrowFiberFailure()
      }

      new ErrorMappedPublisher(
        FlowAdapters.toFlowPublisher(publisher),
        mapStreamError(site, endPoint)
      )
    })

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
      case e if isTimeout(e) =>
        new CequenceWSTimeoutException(s"$label: Time out. ${e.getMessage}.", e)
      case e if isUnknownHost(e) =>
        new CequenceWSUnknownHostException(
          s"$label: Host name cannot be resolved. ${e.getMessage}.",
          e
        )
      case e => new CequenceWSException(s"$label: Fatal problem! ${e.getMessage}.", e)
    }
  }

  /////////
  // Aux //
  /////////

  private def execRequest(
    site: SiteBinding,
    method: Method,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    acceptableStatusCodes: Seq[Int],
    body: BodyFactory
  ): Future[RichResponse] = {
    val label = serviceAndEndpoint(site, Some(endPoint))

    val effect = for {
      request <- buildRequest(
        site,
        method,
        endPoint,
        endPointParam,
        params,
        extraHeaders,
        body
      )
      response <- ZClient.batched(request).provideEnvironment(ZEnvironment[Client](client))
      text <- response.body.asString
    } yield toRichResponse(site, endPoint, response, text, acceptableStatusCodes)

    run(effect.timeoutFail(timeoutException(label))(requestTimeout))
      .recover(recoverErrors(site)(label))
  }

  private def buildRequest(
    site: SiteBinding,
    method: Method,
    endPoint: String,
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])],
    extraHeaders: Seq[(String, String)],
    body: BodyFactory
  ): ZIO[Any, Throwable, Request] = {
    // resolved ONCE per request, so its headers and query params always come from the same
    // evaluation of a dynamic context (e.g. the same tenant/token)
    val requestContext = site.requestContextFn()
    val headers = requestContext.authHeaders ++ extraHeaders
    val callerContentType = headers.exists { case (name, _) => isContentType(name) }

    val queryParams = (params ++ requestContext.extraParams.map { case (key, value) =>
      (key, Some(value))
    }).collect { case (key, Some(value)) => (key, value.toString) }

    for {
      baseUrl <- ZIO.fromEither(URL.decode(site.createURL(Some(endPoint), endPointParam)))
      // appended to (never replacing) a query already embedded in coreUrl/endPoint
      url =
        if (queryParams.isEmpty) baseUrl
        else
          baseUrl.addQueryParams(
            QueryParams(queryParams.map { case (key, value) =>
              key -> Chunk.single(value)
            }: _*)
          )
      requestBody <- body(callerContentType)
    } yield Request(
      method = method,
      url = url,
      headers = Headers(headers.map { case (name, value) => Header.Custom(name, value) }),
      body = requestBody
    )
  }

  private def toRichResponse(
    site: SiteBinding,
    endPoint: String,
    response: Response,
    body: String,
    acceptableStatusCodes: Seq[Int]
  ): RichResponse = {
    val code = response.status.code

    SimpleRichResponse(
      if (acceptableStatusCodes.contains(code))
        Some(StringBackedResponse(body, serviceName(site), Some(endPoint)))
      else None,
      status = StatusData(code, body),
      headers = response.headers.toList.groupBy(_.headerName).map { case (name, headers) =>
        name -> headers.map(_.renderedValue)
      }
    )
  }

  // a body, given whether the CALLER already supplies a Content-Type header: zio-http lets a
  // body's own media type win over the request headers, so bodies whose type the caller may
  // override are built without one in that case
  private type BodyFactory = Boolean => ZIO[Any, Throwable, Body]

  private val noBody: BodyFactory = _ => ZIO.succeed(Body.empty)

  private def jsonBody(json: JsValue): BodyFactory = callerContentType => {
    val body = Body.fromString(Json.stringify(json))
    ZIO.succeed(if (callerContentType) body else body.contentType(MediaType.application.json))
  }

  private def fileBody(file: File): BodyFactory = callerContentType =>
    Body
      .fromFile(file)
      .map(body =>
        if (callerContentType) body else body.contentType(MediaType.application.`octet-stream`)
      )

  private def urlEncodedBody(bodyParams: Seq[(String, Option[Any])]): BodyFactory =
    callerContentType => {
      val form = Form(bodyParams.collect { case (key, Some(value)) =>
        FormField.simpleField(key, value.toString)
      }: _*)
      val body = Body.fromString(form.urlEncoded(StandardCharsets.UTF_8))
      ZIO.succeed(
        if (callerContentType) body
        else body.contentType(MediaType.application.`x-www-form-urlencoded`)
      )
    }

  private def multipartBody(
    fileParams: Seq[(String, File, Option[String])],
    bodyParams: Seq[(String, Option[Any])],
    useInMemoryBody: Boolean
  )(
    implicit filePartToContent: FilePart => String
  ): BodyFactory = _ => {
    // sent under the base name (or the given display name) - never the local path
    val fileParts = fileParams.map { case (key, file, displayName) =>
      (FilePart(key, file.getPath, Some(displayName.getOrElse(file.getName))), file)
    }
    val dataParts = bodyParams.collect { case (key, Some(value)) => (key, value.toString) }

    if (useInMemoryBody)
      // fully materialized body with a known Content-Length - avoids chunked transfer
      // encoding, which some backends/proxies (e.g. Cloudflare) reject
      ZIO.attemptBlocking {
        val boundary = MultipartBodyBuilder.generateBoundary
        val formData = MultipartFormData(
          dataParts = EngineSupport.groupValues(dataParts),
          files = fileParts.map(_._1)
        )
        Body
          .fromArray(MultipartBodyBuilder.buildInMemory(formData, boundary))
          .contentType(MediaType.multipart.`form-data`, Boundary(boundary))
      }
    else {
      val fields = dataParts.map { case (key, value) => FormField.simpleField(key, value) } ++
        fileParts.map { case (filePart, file) =>
          FormField.streamingBinaryField(
            filePart.key,
            ZStream.fromFile(file).orDie,
            EngineSupport
              .contentTypeOfHeaderLine(filePartToContent(filePart))
              .flatMap(MediaType.forContentType)
              .getOrElse(MediaType.application.`octet-stream`),
            filename = Some(filePart.filenameAux)
          )
        }

      ZIO.attemptBlocking(fileParts.foreach { case (_, file) =>
        // fail fast (and as a failed Future) instead of as a stream defect mid-upload
        if (!file.canRead) throw new java.io.FileNotFoundException(file.getPath)
      }) *> Body.fromMultipartFormUUID(Form(fields: _*))
    }
  }

  private def isContentType(headerName: String) = headerName.equalsIgnoreCase("Content-Type")

  private def timeoutException(label: String) =
    new TimeoutException(s"$label: no response within ${requestTimeout.toMillis} ms")

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit u => runtime.unsafe.runToFuture(effect))

  ///////////
  // Close //
  ///////////

  private val closed = new AtomicBoolean(false)

  override def close(): Unit =
    owned.foreach(stack =>
      if (closed.compareAndSet(false, true))
        stack.shutdown()
    )
}

object ZioHttpWSClientEngine {

  private val logger = org.slf4j.LoggerFactory.getLogger("ZioHttpWSClientEngine")

  private val DefaultReadTimeoutMs: Int = 120 * 1000
  private val DefaultPooledIdleTimeoutMs: Int = 60 * 1000 // matches the Play engines
  private val MaxConnectionsPerHost = 1024

  /**
   * An engine on a caller-supplied `Client` and `Runtime` - e.g. the ones of a ZIO
   * application, sharing its connection pool. The engine never closes them. The client's own
   * configuration (connect/idle timeouts, proxy) applies; of `transportSettings` only the
   * per-call `requestTimeout` does.
   */
  def apply(
    client: Client,
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit runtime: Runtime[Any],
    ec: ExecutionContext
  ): ZioHttpWSClientEngine =
    new ZioHttpWSClientEngine(client, runtime, transportSettings, None)

  /**
   * The engine as a layer over the application's `Client`, running on the application's
   * runtime and executor (see `apply`).
   */
  def layer(
    transportSettings: TransportSettings = TransportSettings()
  ): ZLayer[Client, Nothing, ZioHttpWSClientEngine] = {
    ZLayer.fromZIO(
      for {
        client <- ZIO.service[Client]
        runtime <- ZIO.runtime[Any]
        executor <- ZIO.executor
      } yield new ZioHttpWSClientEngine(client, runtime, transportSettings, None)(
        executor.asExecutionContext
      )
    )
  }

  // the Netty driver (event loops + DNS resolver) of an owned stack - shared by copies
  private type DriverEnv = ClientDriver with DnsResolver

  private[ws] final class OwnedStack(
    val runtime: Runtime[Client],
    val shutdown: () => Unit,
    val driverEnvironment: ZEnvironment[DriverEnv]
  )

  /**
   * Builds `layer` on a scope of its own, returning its runtime and the scope's release. Not
   * `Runtime.unsafe.fromLayer`: that registers a JVM shutdown hook per call and never removes
   * it, so hooks (each keeping a closed stack reachable) would pile up across engine
   * create/close cycles and copies. On a build failure the scope is closed, releasing whatever
   * was already acquired (e.g. the event loops of a half-built driver).
   */
  private def scopedRuntime[R](layer: ZLayer[Any, Throwable, R]): (Runtime[R], () => Unit) =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run {
        Scope.make.flatMap { scope =>
          scope
            .extend[Any](layer.toRuntime)
            .onError(cause => scope.close(Exit.failCause(cause)))
            .map { runtime =>
              val release = () =>
                Unsafe.unsafe { implicit u =>
                  Runtime.default.unsafe.run(scope.close(Exit.unit)).getOrThrowFiberFailure()
                }
              (runtime, release)
            }
        }
      }.getOrThrowFiberFailure()
    }

  /**
   * A self-contained, OWNED engine (discovery path): its own Netty driver and client, released
   * by `close()`.
   */
  private[ws] def owned(
    transportSettings: TransportSettings
  )(
    implicit ec: ExecutionContext
  ): ZioHttpWSClientEngine =
    fromStack(fullStack(transportSettings), transportSettings)

  private def fromStack(
    stack: OwnedStack,
    transportSettings: TransportSettings
  )(
    implicit ec: ExecutionContext
  ): ZioHttpWSClientEngine =
    new ZioHttpWSClientEngine(
      stack.runtime.environment.get[Client],
      stack.runtime,
      transportSettings,
      Some(stack)
    )

  private def fullStack(transportSettings: TransportSettings): OwnedStack = {
    def build(driver: ZLayer[Any, Throwable, ClientDriver]) = {
      val (runtime, shutdown) =
        scopedRuntime((driver ++ DnsResolver.default) >+> clientLayer(transportSettings))
      new OwnedStack(runtime, shutdown, runtime.environment)
    }

    val nettyConfig = NettyConfig.defaultWithFastShutdown
    def stockDriver = ZLayer.succeed(nettyConfig) >>> NettyClientDriver.live

    DaemonDriver.constructors match {
      case Some(constructors) =>
        try
          // daemon event-loop threads: a never-closed engine must not block JVM exit
          build(DaemonDriver.layer(nettyConfig, constructors))
        catch {
          case e: Throwable if NonFatal(e) || e.isInstanceOf[LinkageError] =>
            logger.warn(
              s"Could not build the daemon-threaded zio-http driver (${e.getMessage}) - using the stock one, whose threads are NOT daemon: close() the engine before JVM exit."
            )
            build(stockDriver)
        }

      case None => build(stockDriver)
    }
  }

  /**
   * A zio-http client driver whose Netty event loops run on DAEMON threads (zio-http's stock
   * driver uses Netty's default, non-daemon ones, and offers no public hook for them). Built
   * from public zio-http / Netty API plus two constructors that are package-private in Scala
   * (public in bytecode) - `NettyClientDriver` and `NettyRuntime` - reached REFLECTIVELY: a
   * zio-http upgrade that changes them cannot break compilation, only the lookup below, which
   * then falls back to the stock driver with a warning.
   */
  private object DaemonDriver {

    final case class Constructors(
      driver: Constructor[_],
      nettyRuntime: Constructor[_]
    )

    lazy val constructors: Option[Constructors] =
      Try {
        val nettyRuntimeClass = Class.forName("zio.http.netty.NettyRuntime")
        Constructors(
          driver = classOf[NettyClientDriver].getConstructor(
            classOf[ChannelFactory[_]],
            classOf[EventLoopGroup],
            nettyRuntimeClass
          ),
          nettyRuntime = nettyRuntimeClass.getConstructor(classOf[Runtime[_]])
        )
      }.fold(
        e => {
          logger.warn(
            s"zio-http internals changed (${e.getMessage}) - using the stock Netty driver, whose threads are NOT daemon: close() the engine before JVM exit."
          )
          None
        },
        Some(_)
      )

    def layer(
      config: NettyConfig,
      constructors: Constructors
    ): ZLayer[Any, Throwable, ClientDriver] =
      ZLayer.scoped {
        for {
          eventLoopGroup <- ZIO.acquireRelease(
            ZIO.succeed(
              new MultiThreadIoEventLoopGroup(
                config.nThreads,
                new ThreadPerTaskExecutor(
                  new DefaultThreadFactory("ws-client-zio-http", true)
                ),
                NioIoHandler.newFactory()
              )
            )
          )(group =>
            ZIO
              .attemptBlocking(
                group
                  .shutdownGracefully(
                    config.shutdownQuietPeriod,
                    config.shutdownTimeOut,
                    config.shutdownTimeUnit
                  )
                  .await()
              )
              .ignore
          )
          runtime <- ZIO.runtime[Any]
          driver <- ZIO.attempt {
            val channelFactory = new ChannelFactory[Channel] {
              override def newChannel(): Channel = new NioSocketChannel()
            }
            constructors.driver
              .newInstance(
                channelFactory,
                eventLoopGroup,
                constructors.nettyRuntime.newInstance(runtime).asInstanceOf[AnyRef]
              )
              .asInstanceOf[ClientDriver]
          }
        } yield driver
      }
  }

  // a new client (connection pool) on an EXISTING driver - the driver stays owned by the
  // engine that created it
  private def clientStack(
    driverEnvironment: ZEnvironment[DriverEnv],
    transportSettings: TransportSettings
  ): OwnedStack = {
    val (runtime, shutdown) =
      scopedRuntime(
        ZLayer.succeedEnvironment(driverEnvironment) >>> clientLayer(transportSettings)
      )
    new OwnedStack(runtime, shutdown, driverEnvironment)
  }

  private def clientLayer(
    transportSettings: TransportSettings
  ): ZLayer[DriverEnv, Throwable, Client] = {
    (ZLayer.succeed(clientConfig(transportSettings)) ++ ZLayer.environment[DriverEnv]) >>>
      Client.customized
  }

  private def resolvedTimeouts(transportSettings: TransportSettings): Timeouts =
    EngineSupport.resolveTimeouts(
      transportSettings.timeouts,
      Timeouts(
        requestTimeout = Some(EngineSupport.DefaultRequestTimeoutMs),
        readTimeout = Some(DefaultReadTimeoutMs),
        connectTimeout = Some(EngineSupport.DefaultConnectTimeoutMs),
        pooledConnectionIdleTimeout = Some(DefaultPooledIdleTimeoutMs)
      )
    )

  private[ws] def clientConfig(transportSettings: TransportSettings): ZClient.Config = {
    val timeouts = resolvedTimeouts(transportSettings)
    def millis(value: Option[Int]) = zio.Duration.fromMillis(value.getOrElse(0).toLong)

    val base = ZClient.Config.default
      .connectionTimeout(millis(timeouts.connectTimeout))
      .idleTimeout(millis(timeouts.readTimeout))
      // zio-http's default is a FIXED pool of 10 connections - too few for concurrent API use
      .dynamicConnectionPool(
        minimum = 1,
        maximum = MaxConnectionsPerHost,
        ttl = millis(timeouts.pooledConnectionIdleTimeout)
      )

    transportSettings.proxyURL.fold(base) { proxyUrl =>
      val (host, port) = ProxyUrlUtil.hostAndPort(proxyUrl)
      val url = URL
        .decode(s"http://$host:$port")
        .fold(
          e => throw new CequenceWSException(s"Invalid proxy: ${e.getMessage}", e),
          identity
        )
      base.proxy(zio.http.Proxy(url))
    }
  }

  private def isTimeout(e: Throwable): Boolean =
    ThrowableUtil.hasCause(e, classOf[TimeoutException]) ||
      ThrowableUtil.hasCause(e, classOf[io.netty.handler.timeout.TimeoutException]) ||
      ThrowableUtil.hasCause(e, classOf[io.netty.channel.ConnectTimeoutException])

  private def isUnknownHost(e: Throwable): Boolean =
    ThrowableUtil.hasCause(e, classOf[UnknownHostException]) ||
      ThrowableUtil.hasCause(e, classOf[UnresolvedAddressException])

  private[ws] def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
    (serviceEndPointName: String) =>
      {
        case e: Exception if isTimeout(e) =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}.",
            e
          )
        case e: Exception if isUnknownHost(e) =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}.",
            e
          )
      }
  }
}
