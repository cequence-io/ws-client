package io.cequence.wsclient.service.ws

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.WSClientEngine
import io.cequence.wsclient.service.ws.PlayWSMultipartWritable.writeableOf_MultipartFormData
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.JsonBodyWritables._
import play.api.libs.ws.{BodyWritable, DefaultBodyWritables, StandaloneWSRequest}

import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}

/**
 * Base trait for Play-based web services / client with handy GET, POST, and DELETE request
 * builders, and response handling
 *
 * @since Jan
 *   2023
 */
protected trait PlayWSClientEngine extends WSClientEngine with HasPlayWSClient {

  protected val defaultRequestTimeout: Int = 120 * 1000 // two minutes
  protected val defaultReadoutTimeout: Int = 120 * 1000 // two minutes

  protected def timeouts: Timeouts =
    requestContext.explTimeouts.getOrElse(
      Timeouts(
        requestTimeout = Some(defaultRequestTimeout),
        readTimeout = Some(defaultReadoutTimeout)
      )
    )

  protected val serviceName: String = getClass.getSimpleName

  /////////
  // GET //
  /////////

  override def execGETRich(
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)

    execRequestAux(
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
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params, extraHeaders)

    execPOSTWithStatusAux(
      request,
      JsObject(toJsonFields(bodyParams)),
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  override def execPOSTMultipartRich(
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    fileParams: Seq[(String, File, Option[String])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)
    val formData = createMultipartFormData(fileParams, bodyParams)

    implicit val writeable: BodyWritable[MultipartFormData] = writeableOf_MultipartFormData(
      "utf-8"
    )

    execPOSTWithStatusAux(
      request,
      formData,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPOSTURLEncodedRich(
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)
    val bodyData = bodyParams.collect { case (key, Some(value)) =>
      (key, value.toString)
    }.toMap

    implicit val writeable: BodyWritable[Map[String, String]] =
      DefaultBodyWritables.writeableOf_urlEncodedSimpleForm

    execPOSTWithStatusAux(
      request,
      bodyData,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPOSTFileRich(
    endPoint: String,
    endPointParam: Option[String] = None,
    urlParams: Seq[(String, Option[Any])] = Nil,
    file: java.io.File,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(Some(endPoint), endPointParam, urlParams)

    implicit val writable = DefaultBodyWritables.writableOf_File

    execPOSTWithStatusAux(
      request,
      file,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  override def execPOSTSourceRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    urlParams: Seq[(PT, Option[Any])] = Nil,
    source: Source[ByteString, _],
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request =
      getWSRequestOptional(Some(endPoint), endPointParam, urlParams)

    implicit val writable = DefaultBodyWritables.writableOf_Source

    execPOSTWithStatusAux(
      request,
      source,
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  private def execPOSTWithStatusAux[B: BodyWritable](
    request: StandaloneWSRequest,
    body: B,
    endPointForLogging: Option[String], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequestAux(
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
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)

    execRequestAux(
      request,
      _.delete(),
      acceptableStatusCodes,
      Some(endPoint)
    )
  }

  ////////////
  // PATCH //
  ////////////

  override def execPATCRich(
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)
    val jsonFields = toJsonFields(bodyParams)

    execPATCHAux(
      request,
      JsObject(jsonFields),
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  private def execPATCHAux[T: BodyWritable](
    request: StandaloneWSRequest,
    body: T,
    endPointForLogging: Option[String], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ) =
    execRequestAux(
      request,
      _.patch(body),
      acceptableStatusCodes,
      endPointForLogging
    )

  /////////
  // PUT //
  /////////

  override def execPUTRich(
    endPoint: String,
    endPointParam: Option[String] = None,
    params: Seq[(String, Option[Any])] = Nil,
    bodyParams: Seq[(String, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, params)
    val jsonFields = toJsonFields(bodyParams)

    execPUTAux(
      request,
      JsObject(jsonFields),
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  private def execPUTAux[T: BodyWritable](
    request: StandaloneWSRequest,
    body: T,
    endPointForLogging: Option[String], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ) =
    execRequestAux(
      request,
      _.put(body),
      acceptableStatusCodes,
      endPointForLogging
    )

  ////////////////
  // WS Request //
  ////////////////

  protected[ws] def getWSRequestOptional(
    endPoint: Option[String],
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])] = Nil,
    extraHeaders: Seq[(String, String)] = Nil
  ): StandaloneWSRequest#Self = {
    val extraStringParams = requestContext.extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }
    val paramsString = paramsOptionalAsString(params ++ extraStringParams)
    val url = createURL(endPoint, endPointParam) + paramsString

    client.url(url).addHttpHeaders(
      (requestContext.authHeaders ++ extraHeaders): _*
    )
  }

  private def execRequestAux(
    request: StandaloneWSRequest,
    exec: StandaloneWSRequest => Future[StandaloneWSRequest#Response],
    acceptableStatusCodes: Seq[Int] = Nil,
    endPointForLogging: Option[String] = None // only for logging
  ): Future[RichResponse] = {
    exec(request).map { rawResponse =>
      val playWsResponse =
        if (acceptableStatusCodes.contains(rawResponse.status))
          Some(
            PlayWsResponse(
              rawResponse = rawResponse,
              serviceNameForLogging = serviceName,
              endpointForLogging = endPointForLogging
            )
          )
        else None

      PlayWsRichResponse(
        playWsResponse,
        status = StatusData(rawResponse.status, rawResponse.body),
        headers = rawResponse.headers.map { case (k, v) => k -> v.toSeq }
      )
    }
  }.recover(recoverErrors(serviceAndEndpoint(endPointForLogging)))

  // error handling
  protected def recoverErrors: String => PartialFunction[Throwable, RichResponse]

  // aux

  protected def paramsAsString(params: Seq[(String, Any)]): String = {
    val string = params.map { case (tag, value) => s"$tag=$value" }.mkString("&")

    if (string.nonEmpty) s"?$string" else ""
  }

  protected def paramsOptionalAsString(params: Seq[(String, Option[Any])]): String = {
    val string = params.collect { case (tag, Some(value)) => s"$tag=$value" }.mkString("&")

    if (string.nonEmpty) s"?$string" else ""
  }

  protected def createURL(
    endpoint: Option[String],
    value: Option[String] = None
  ): String = {
    val endpointString = endpoint.getOrElse("")
    val valueString = value.map("/" + _).getOrElse("")
    val slash =
      if (coreUrl.endsWith("/") || (endpointString.isEmpty && valueString.isEmpty)) "" else "/"

    coreUrl + slash + endpointString + valueString
  }

  protected def toOptionalParams(
    params: Seq[(String, Any)]
  ): Seq[(String, Some[Any])] =
    params.map { case (a, b) => (a, Some(b)) }

  protected def toJsonFields(
    bodyParams: Seq[(String, Option[JsValue])]
  ) =
    bodyParams.collect { case (fieldName, Some(jsValue)) =>
      if (fieldName.trim.nonEmpty)
        Seq((fieldName, jsValue))
      else
        jsValue match {
          case JsObject(fields) => fields.toSeq
          case _                => throw new CequenceWSException("Empty param name.")
        }
    }.flatten

  protected def serviceAndEndpoint(endPointForLogging: Option[String]) =
    s"$serviceName${endPointForLogging.map("." + _).getOrElse("")}"
}

object PlayWSClientEngine {
  def apply(
    coreUrl: String,
    requestContext: WsRequestContext = WsRequestContext(),
    recoverErrors: String => PartialFunction[Throwable, RichResponse] = defaultRecoverErrors
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): WSClientEngine = new PlayWSClientEngineImpl(coreUrl, requestContext, recoverErrors)

  private final class PlayWSClientEngineImpl(
    override protected val coreUrl: String,
    override protected val requestContext: WsRequestContext,
    override protected val recoverErrors: String => PartialFunction[Throwable, RichResponse]
  )(
    override protected implicit val materializer: Materializer,
    override protected implicit val ec: ExecutionContext
  ) extends PlayWSClientEngine

  private def defaultRecoverErrors: String => PartialFunction[Throwable, RichResponse] = {
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
