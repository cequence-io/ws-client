package io.cequence.wsclient.service.ws

import io.cequence.wsclient.JsonUtil.toJson
import io.cequence.wsclient.domain.{
  CequenceWSException,
  CequenceWSTimeoutException,
  CequenceWSUnknownHostException,
  PlayWsResponse,
  PlayWsRichResponse,
  Response,
  RichResponse,
  StatusData
}
import io.cequence.wsclient.service.RetryableService
import io.cequence.wsclient.service.ws.MultipartWritable.{
  HttpHeaderNames,
  writeableOf_MultipartFormData
}
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.JsonBodyWritables._
import play.api.libs.ws.{BodyWritable, StandaloneWSRequest}

import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}

/**
 * Base class for web services with handy GET, POST, and DELETE request builders, and response
 * handling
 *
 * @since Jan
 *   2023
 */
protected trait WSRequestHelperBase extends HasWSClient with RetryableService {

  protected val coreUrl: String

  protected implicit val ec: ExecutionContext

  protected type PEP
  protected type PT

  protected val serviceName: String = getClass.getSimpleName

  private val defaultAcceptableStatusCodes = Seq(200, 201, 202)

  private def serviceAndEndpoint(endPointForLogging: Option[PEP]) =
    s"$serviceName.${endPointForLogging.map(_.toString).getOrElse("")}"

  /////////
  // GET //
  /////////

  def execGET(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execGETRich(
      endPoint,
      endPointParam,
      params
    ).map(getResponseOrError)

  def execGETRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, toStringParams(params))

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

  def execPOST(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil
  ): Future[Response] =
    execPOSTRich(
      endPoint,
      endPointParam,
      params,
      bodyParams
    ).map(getResponseOrError)

  def execPOSTRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, toStringParams(params))
    val bodyParamsX = bodyParams.collect { case (fieldName, Some(jsValue)) =>
      (fieldName.toString, jsValue)
    }

    execPOSTWithStatusAux(
      request,
      JsObject(bodyParamsX),
      Some(endPoint),
      acceptableStatusCodes
    )
  }

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  def execPOSTMultipart(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execPOSTMultipartRich(
      endPoint,
      endPointParam,
      params,
      fileParams,
      bodyParams
    ).map(getResponseOrError)

  /**
   * @param fileParams
   *   the third param in a tuple is a display (header) file name
   */
  def execPOSTMultipartRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  )(
    implicit filePartToContent: FilePart => String = contentTypeByExtension
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, toStringParams(params))
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

  // TODO: private
  protected def execPOSTWithStatusAux[B: BodyWritable](
    request: StandaloneWSRequest,
    body: B,
    endPointForLogging: Option[PEP], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] =
    execRequestAux(
      request,
      _.post(body),
      acceptableStatusCodes,
      endPointForLogging
    )

  private def contentTypeByExtension: FilePart => String = file => {
    val fileExtensionContentTypeMap = Map(
      "txt" -> "text/plain",
      "csv" -> "text/csv",
      "json" -> "application/json",
      "xml" -> "application/xml",
      "pdf" -> "application/pdf",
      "zip" -> "application/zip",
      "tar" -> "application/x-tar",
      "gz" -> "application/x-gzip",
      "ogg" -> "application/ogg",
      "mp3" -> "audio/mpeg",
      "wav" -> "audio/x-wav",
      "mp4" -> "video/mp4",
      "webm" -> "video/webm",
      "png" -> "image/png",
      "jpg" -> "image/jpeg",
      "jpeg" -> "image/jpeg",
      "gif" -> "image/gif",
      "svg" -> "image/svg+xml"
    )

    val contentTypeAux = file.contentType.orElse {
      // Azure expects an explicit content type for files
      fileExtensionContentTypeMap.get(file.extension)
    }

    contentTypeAux.map { ct =>
      s"${HttpHeaderNames.CONTENT_TYPE}: $ct\r\n"
    }.getOrElse("")
  }

  // create a multipart form data holder contain classic data (key-value) parts as well as file parts
  private def createMultipartFormData(
    fileParams: Seq[(PT, File, Option[String])] = Nil,
    bodyParams: Seq[(PT, Option[Any])] = Nil
  ) = MultipartFormData(
    dataParts = bodyParams.collect { case (key, Some(value)) =>
      (key.toString, Seq(value.toString))
    }.toMap,
    files = fileParams.map { case (key, file, headerFileName) =>
      FilePart(key.toString, file.getPath, headerFileName)
    }
  )

  ////////////
  // DELETE //
  ////////////

  def execDELETE(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil
  ): Future[Response] =
    execDELETERich(
      endPoint,
      endPointParam,
      params
    ).map(getResponseOrError)

  def execDELETERich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, toStringParams(params))

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

  protected def execPATCH(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil
  ): Future[Response] =
    execPATCRich(
      endPoint,
      endPointParam,
      params,
      bodyParams
    ).map(getResponseOrError)

  protected def execPATCRich(
    endPoint: PEP,
    endPointParam: Option[String] = None,
    params: Seq[(PT, Option[Any])] = Nil,
    bodyParams: Seq[(PT, Option[JsValue])] = Nil,
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ): Future[RichResponse] = {
    val request = getWSRequestOptional(Some(endPoint), endPointParam, toStringParams(params))
    val bodyParamsX = bodyParams.collect { case (fieldName, Some(jsValue)) =>
      (fieldName.toString, jsValue)
    }

    execPATCHAux(request, JsObject(bodyParamsX), Some(endPoint), acceptableStatusCodes)
  }

  // TODO: private
  protected def execPATCHAux[T: BodyWritable](
    request: StandaloneWSRequest,
    body: T,
    endPointForLogging: Option[PEP], // only for logging
    acceptableStatusCodes: Seq[Int] = defaultAcceptableStatusCodes
  ) =
    execRequestAux(
      request,
      _.patch(body),
      acceptableStatusCodes,
      endPointForLogging
    )

  ////////////////
  // WS Request //
  ////////////////

  protected[ws] def getWSRequest(
    endPoint: Option[PEP],
    endPointParam: Option[String],
    params: Seq[(String, Any)]
  ): StandaloneWSRequest = {
    val paramsString = paramsAsString(params)
    val url = createUrl(endPoint, endPointParam) + paramsString

    client.url(url)
  }

  protected[ws] def getWSRequestOptional(
    endPoint: Option[PEP],
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])]
  ): StandaloneWSRequest = {
    val paramsString = paramsOptionalAsString(params)
    val url = createUrl(endPoint, endPointParam) + paramsString

    client.url(url)
  }

  private def execRequestAux(
    request: StandaloneWSRequest,
    exec: StandaloneWSRequest => Future[StandaloneWSRequest#Response],
    acceptableStatusCodes: Seq[Int] = Nil,
    endPointForLogging: Option[PEP] = None // only for logging
  ): Future[RichResponse] = {
    exec(request).map { rawResponse =>
      val playWsResponse =
        if (acceptableStatusCodes.contains(rawResponse.status))
          Some(
            PlayWsResponse(
              rawResponse = rawResponse,
              serviceNameForLogging = serviceName,
              endpointForLogging = endPointForLogging.map(_.toString)
            )
          )
        else None

      PlayWsRichResponse(
        playWsResponse,
        status = StatusData(rawResponse.status, rawResponse.body),
        headers = rawResponse.headers.map { case (k, v) => k -> v.toSeq }
      )
    }
  }.recover(recoverErrors(endPointForLogging))

  // error handling

  protected def recoverErrors(endPointForLogging: Option[PEP] = None)
    : PartialFunction[Throwable, RichResponse] = {
    case e: TimeoutException =>
      throw new CequenceWSTimeoutException(
        s"${serviceAndEndpoint(endPointForLogging)} timed out: ${e.getMessage}."
      )
    case e: UnknownHostException =>
      throw new CequenceWSUnknownHostException(
        s"${serviceAndEndpoint(endPointForLogging)} cannot resolve a host name: ${e.getMessage}."
      )
  }

  protected def getResponseOrError(response: RichResponse): Response =
    response.response.getOrElse(
      handleErrorCodes(response.status.code, response.status.message)
    )

  protected def handleErrorCodes(
    httpCode: Int,
    message: String
  ): Nothing =
    throw new CequenceWSException(s"Code ${httpCode} : ${message}")

  protected def handleNotFoundAndError(response: RichResponse): Option[Response] =
    response.response.orElse(
      if (response.status.code == 404) None else Some(getResponseOrError(response))
    )

  // aux

  protected def jsonBodyParams[T](
    params: (T, Option[Any])*
  ): Seq[(T, Option[JsValue])] =
    params.map { case (paramName, value) => (paramName, value.map(toJson)) }

  protected def paramsAsString(params: Seq[(String, Any)]): String = {
    val string = params.map { case (tag, value) => s"$tag=$value" }.mkString("&")

    if (string.nonEmpty) s"?$string" else ""
  }

  protected def paramsOptionalAsString(params: Seq[(String, Option[Any])]): String = {
    val string = params.collect { case (tag, Some(value)) => s"$tag=$value" }.mkString("&")

    if (string.nonEmpty) s"?$string" else ""
  }

  // TODO: no use
  override def isRetryable(t: Throwable): Boolean = t match {
    // we retry on these
    case _: CequenceWSTimeoutException => true

    // otherwise don't retry
    case _ => false
  }

  protected def createUrl(
    endpoint: Option[PEP],
    value: Option[String] = None
  ): String = {
    val slash = if (coreUrl.endsWith("/")) "" else "/"
    val endpointString = endpoint.map(_.toString).getOrElse("")
    val valueString = value.map("/" + _).getOrElse("")

    coreUrl + slash + endpointString + valueString
  }

  protected def toOptionalParams(
    params: Seq[(PT, Any)]
  ): Seq[(PT, Some[Any])] =
    params.map { case (a, b) => (a, Some(b)) }

  protected def toStringParams(
    params: Seq[(PT, Option[Any])]
  ): Seq[(String, Option[Any])] =
    params.map { case (a, b) => (a.toString, b) }
}
