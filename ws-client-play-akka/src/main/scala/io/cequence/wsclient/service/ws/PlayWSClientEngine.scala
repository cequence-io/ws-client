package io.cequence.wsclient.service.ws

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.{WSClientEngine, WSClientInputStreamExtraAkka}
import io.cequence.wsclient.service.spi.TransportSettings
import io.cequence.wsclient.service.ws.PlayWSMultipartWritable.{
  writeableOf_MultipartFormData,
  writeableOf_MultipartFormDataInMemory
}
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.JsonBodyWritables._
import play.api.libs.ws.{
  BodyWritable,
  DefaultBodyWritables,
  DefaultWSProxyServer,
  StandaloneWSClient,
  StandaloneWSRequest
}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.ShutdownHookThread

/**
 * The Play WS (Akka) flavor of the site-stateless [[WSClientEngine]] - owns the lazily-created
 * `StandaloneWSClient` (a shaded AsyncHttpClient underneath) and, optionally, the actor system
 * it runs on. Every call takes a [[SiteBinding]] first, so ONE engine instance (one client,
 * one connection pool) serves any number of sites/providers.
 *
 * @param transportSettings
 *   client-level settings (timeouts, proxy) baked into the underlying AHC client on first use
 * @param ownedSystem
 *   terminated on `close()` - set only by a discovery provider whose engine owns a dedicated
 *   actor system (see `PlayAkkaWSClientEngineProvider`); a caller-supplied system/materializer
 *   (the default, via [[PlayWSClientEngine.apply]]) is never touched
 * @param newExecEnv
 *   factory for a fresh owned daemon actor system, used by `copy(reuseExecContext = false)` -
 *   set only by a discovery provider (mirrors `ownedSystem`, and is threaded into every copy
 *   so copies-of-copies keep the ability); `None` for a caller-supplied environment, in which
 *   case that flavor of `copy` throws a `CequenceWSException`
 *
 * @since Jan
 *   2023
 */
class PlayWSClientEngine(
  val transportSettings: TransportSettings = TransportSettings(),
  ownedSystem: Option[ActorSystem] = None,
  newExecEnv: Option[() => ActorSystem] = None
)(
  implicit val materializer: Materializer,
  val ec: ExecutionContext
) extends WSClientEngine
    with WSClientInputStreamExtraAkka {

  // qualified-private on purpose: plain-private trait members are excluded from zinc's API
  // hash, so downstream implementers would not be recompiled -> AbstractMethodError
  private[ws] val clientCreated = new AtomicBoolean(false)
  private[ws] var closedFlag = false // guarded by the instance monitor (lazy init + close())
  @volatile private[ws] var shutdownHook: Option[ShutdownHookThread] = None

  // defaults in ms
  private object DefaultTimeouts {
    val readTimeout = 60000
    val requestTimeout = 60000
    val connectTimeout = 5000
    val pooledConnectionIdleTimeout = 60000
  }

  // the explicit synchronized block (not just the lazy val's own thread-safety) is what
  // serializes creation against close(): Scala 2 lazy init happens to hold the instance
  // monitor, but Scala 3's CAS-based lazy vals do NOT - so take it explicitly
  private[ws] lazy val client: StandaloneWSClient = synchronized {
    import play.shaded.ahc.org.asynchttpclient._

    // close() takes the same monitor - so a client can never be created after close() ran
    // (nothing would ever close it)
    if (closedFlag)
      throw new IllegalStateException("The WS client engine is already closed.")

    val timeouts = transportSettings.timeouts

    val asyncHttpClientConfig = new DefaultAsyncHttpClientConfig.Builder()
      .setConnectTimeout(timeouts.connectTimeout.getOrElse(DefaultTimeouts.connectTimeout))
      .setReadTimeout(timeouts.readTimeout.getOrElse(DefaultTimeouts.readTimeout))
      .setPooledConnectionIdleTimeout(
        timeouts.pooledConnectionIdleTimeout
          .getOrElse(DefaultTimeouts.pooledConnectionIdleTimeout)
      )
      .setRequestTimeout(timeouts.requestTimeout.getOrElse(DefaultTimeouts.requestTimeout))
//      .setEnabledProtocols(Array("TLSv1.2", "TLSv1.1", "TLSv1")
      .build
    val asyncHttpClient = new DefaultAsyncHttpClient(asyncHttpClientConfig)
    val client = new StandaloneAhcWSClient(asyncHttpClient)

    // a safety net for engines that are never closed explicitly; removed again in close() -
    // otherwise repeated create/close cycles would accumulate hooks (each retaining a client)
    shutdownHook = Some(scala.sys.addShutdownHook(client.close()))
    clientCreated.set(true)

    client
  }

  /**
   * A copy with the given client-level settings - always builds its own HTTP client (from
   * `transportSettings`), so it is closed independently of this engine (see
   * `WSClientEngine.copy` for the full contract).
   *
   *   - `reuseExecContext = true`: the copy runs on THIS engine's actor system / materializer
   *     / execution context (`ownedSystem = None` - the copy's `close()` never terminates the
   *     shared system). Close the environment-owning original only AFTER copies that reuse it.
   *   - `reuseExecContext = false`: the copy creates its OWN owned daemon actor system via the
   *     `newExecEnv` factory - available only on engines built through a discovery provider;
   *     an engine built via the explicit `apply` (or otherwise on a caller-supplied
   *     environment) has none and throws a [[CequenceWSException]].
   */
  override def copy(
    transportSettings: TransportSettings = this.transportSettings,
    reuseExecContext: Boolean = true
  ): PlayWSClientEngine =
    if (reuseExecContext)
      new PlayWSClientEngine(transportSettings, ownedSystem = None, newExecEnv)(
        materializer,
        ec
      )
    else {
      val mkSystem = newExecEnv.getOrElse(
        throw new CequenceWSException(
          "This engine's execution environment was caller-supplied - create a new engine " +
            "via the explicit factory or the discovery registry."
        )
      )
      val system = mkSystem()

      new PlayWSClientEngine(transportSettings, ownedSystem = Some(system), newExecEnv)(
        Materializer(system),
        system.dispatcher
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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)

    execRequestAux(
      site,
      request,
      _.get(),
      acceptableStatusCodes,
      Some(endPoint)
    )
  }

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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)

    execPOSTWithStatusAux(
      site,
      request,
      body,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)
    val formData = createMultipartFormData(fileParams, bodyParams)

    implicit val writeable: BodyWritable[MultipartFormData] =
      if (useInMemoryBody)
        writeableOf_MultipartFormDataInMemory("utf-8")
      else
        writeableOf_MultipartFormData("utf-8")

    execPOSTWithStatusAux(
      site,
      request,
      formData,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPOSTURLEncodedRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)
    val bodyData = bodyParams.collect { case (key, Some(value)) =>
      (key, value.toString)
    }.toMap

    implicit val writeable: BodyWritable[Map[String, String]] =
      DefaultBodyWritables.writeableOf_urlEncodedSimpleForm

    execPOSTWithStatusAux(
      site,
      request,
      bodyData,
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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, urlParams, extraHeaders)

    implicit val writable = DefaultBodyWritables.writableOf_File

    execPOSTWithStatusAux(
      site,
      request,
      file,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPOSTSourceRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    source: Source[ByteString, _],
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, urlParams, extraHeaders)

    implicit val writable = DefaultBodyWritables.writableOf_Source

    execPOSTWithStatusAux(
      site,
      request,
      source,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  private def execPOSTWithStatusAux[B: BodyWritable](
    site: SiteBinding,
    request: StandaloneWSRequest,
    body: B,
    endPointForLogging: Option[String], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequestAux(
      site,
      request,
      _.post(body),
      acceptableStatusCodes,
      endPointForLogging
    )

  // create a multipart form data holder contain classic data (key-value) parts as well as file parts
  private def createMultipartFormData(
    fileParams: Seq[(String, File, Option[String])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil
  ) = MultipartFormData(
    dataParts = bodyParams.collect { case (key, Some(value)) =>
      (key, Seq(value.toString))
    }.toMap,
    files = fileParams.map { case (key, file, headerFileName) =>
      FilePart(key, file.getPath, headerFileName)
    }
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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)

    execRequestAux(
      site,
      request,
      _.delete(),
      acceptableStatusCodes,
      Some(endPoint)
    )
  }

  ////////////
  // PATCH //
  ////////////

  override def execPATCHRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)
    val jsonBody = toJsBodyObject(bodyParams)

    execPATCHAux(
      site,
      request,
      jsonBody,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  private def execPATCHAux[T: BodyWritable](
    site: SiteBinding,
    request: StandaloneWSRequest,
    body: T,
    endPointForLogging: Option[String], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ) =
    execRequestAux(
      site,
      request,
      _.patch(body),
      acceptableStatusCodes,
      endPointForLogging
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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)

    execPUTAux(
      site,
      request,
      body,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
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
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, params, extraHeaders)
    val formData = createMultipartFormData(fileParams, bodyParams)

    implicit val writeable: BodyWritable[MultipartFormData] =
      if (useInMemoryBody)
        writeableOf_MultipartFormDataInMemory("utf-8")
      else
        writeableOf_MultipartFormData("utf-8")

    execPUTAux(
      site,
      request,
      formData,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPUTFileRich(
    site: SiteBinding,
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    file: java.io.File,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(site, Some(endPoint), endPointParam, urlParams, extraHeaders)

    implicit val writable = DefaultBodyWritables.writableOf_File

    execPUTAux(
      site,
      request,
      file,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  private def execPUTAux[T: BodyWritable](
    site: SiteBinding,
    request: StandaloneWSRequest,
    body: T,
    endPointForLogging: Option[String], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ) =
    execRequestAux(
      site,
      request,
      _.put(body),
      acceptableStatusCodes,
      endPointForLogging
    )

  ////////////////
  // WS Request //
  ////////////////

  protected[ws] def getWSRequestOptional(
    site: SiteBinding,
    endPoint: Option[String],
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): StandaloneWSRequest#Self = {
    val requestContext = site.requestContextFn()

    val extraStringParams = requestContext.extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }
    val paramsString = paramsOptionalAsString(params ++ extraStringParams)
    val baseUrl = site.createURL(endPoint, endPointParam)
    // append with '&' when the URL already carries a query (e.g. Azure-style ?api-version=...)
    val url =
      if (baseUrl.contains("?") && paramsString.startsWith("?"))
        baseUrl + "&" + paramsString.drop(1)
      else
        baseUrl + paramsString

    val request = client.url(url)

    val requestWithProxy = transportSettings.proxyURL.map { proxyUrl =>
      val (host, port) = ProxyUrlUtil.hostAndPort(proxyUrl)
      request.withProxyServer(DefaultWSProxyServer(host, port))
    }.getOrElse(request)

    requestWithProxy.addHttpHeaders(
      (requestContext.authHeaders ++ extraHeaders): _*
    )
  }

  private def execRequestAux(
    site: SiteBinding,
    request: StandaloneWSRequest,
    exec: StandaloneWSRequest => Future[StandaloneWSRequest#Response],
    acceptableStatusCodes: Seq[Int] = Nil,
    endPointForLogging: Option[String] = None // only for logging
  ): Future[RichResponse] = {
    val serviceEndPointName = serviceAndEndpoint(site, endPointForLogging)

    exec(request).map { rawResponse =>
      val playWsResponse =
        if (acceptableStatusCodes.contains(rawResponse.status))
          Some(
            PlayWsResponse(
              rawResponse = rawResponse,
              serviceNameForLogging = serviceName(site),
              endpointForLogging = endPointForLogging
            )
          )
        else None

      PlayWsRichResponse(
        playWsResponse,
        status = StatusData(rawResponse.status, rawResponse.body),
        headers = rawResponse.headers.map { case (k, v) => k -> v.toSeq }
      )
    }.recover(
      SiteBinding.resolveRecoverErrors(
        site.recoverErrors,
        PlayWSClientEngine.defaultRecoverErrors
      )(serviceEndPointName)
    )
  }

  // aux

  protected def paramsAsString(params: Seq[(String, Any)]): String = {
    val string = params.map { case (tag, value) => s"$tag=$value" }.mkString("&")

    if (string.nonEmpty) s"?$string" else ""
  }

  protected def paramsOptionalAsString(params: Seq[(String, Option[Any])]): String = {
    val string = params.collect { case (tag, Some(value)) => s"$tag=$value" }.mkString("&")

    if (string.nonEmpty) s"?$string" else ""
  }

  protected def serviceName(site: SiteBinding): String =
    site.label.getOrElse(getClass.getSimpleName)

  protected def serviceAndEndpoint(
    site: SiteBinding,
    endPointForLogging: Option[String]
  ): String =
    s"${serviceName(site)}${endPointForLogging.map("." + _).getOrElse("")}"

  ///////////
  // CLOSE //
  ///////////

  // synchronized on the instance monitor - the same monitor the client-creation block takes -
  // so close() cannot interleave with an in-flight first-request client creation (it waits,
  // then sees clientCreated=true and closes properly); closedFlag stops any LATER first use
  // from creating a client nothing would ever close
  override def close(): Unit = {
    synchronized {
      closedFlag = true
      // closing an engine whose client was never used must not force its creation
      if (clientCreated.get()) {
        client.close()
        shutdownHook.foreach { hook =>
          // removal fails benignly if the JVM is already shutting down
          try hook.remove()
          catch { case _: IllegalStateException => }
        }
        shutdownHook = None
      }
    }
    // terminating the actor system is independent of (and always follows) the client close -
    // only set when THIS engine owns the system (see the class doc)
    ownedSystem.foreach(_.terminate())
  }
}

object PlayWSClientEngine {

  def apply(
    transportSettings: TransportSettings = TransportSettings()
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): PlayWSClientEngine = new PlayWSClientEngine(transportSettings)

  private[ws] def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
    (serviceEndPointName: String) =>
      {
        case e: TimeoutException =>
          throw new CequenceWSTimeoutException(
            s"${serviceEndPointName} timed out: ${e.getMessage}."
          )
        case e: UnknownHostException =>
          throw new CequenceWSUnknownHostException(
            s"${serviceEndPointName} cannot resolve a host name: ${e.getMessage}."
          )
      }
  }
}
