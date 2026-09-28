package io.cequence.wsclient.service

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.{FilePart, HttpHeaderNames}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

trait WSClientBase extends CloseableService {

  protected implicit val ec: ExecutionContext

  protected val defaultAcceptableStatusCodes = Seq(200, 201, 202)

  protected def contentTypeByExtension: FilePart => String = file => {
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
      "svg" -> "image/svg+xml",
      "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    val contentTypeAux = file.contentType.orElse {
      // Azure expects an explicit content type for files
      fileExtensionContentTypeMap.get(file.extension)
    }

    contentTypeAux.map { ct =>
      s"${HttpHeaderNames.CONTENT_TYPE}: $ct\r\n"
    }.getOrElse("")
  }

  def getResponseOrError(response: RichResponse): Response =
    response.response.getOrElse(
      handleErrorCodes(response.status.code, response.status.message)
    )

  /**
   * Turns a non-acceptable HTTP status into this service's exception - override to classify
   * (rate limit, auth, overload, ...). The default throws a [[CequenceWSHttpStatusException]]
   * carrying the status and the body.
   */
  protected def handleErrorCodes(
    httpCode: Int,
    message: String
  ): Nothing =
    throw new CequenceWSHttpStatusException(
      s"Code ${httpCode} : ${message}",
      httpCode,
      message
    )

  /**
   * Routes a structured HTTP-status failure - e.g. a STREAMED call's non-2xx response -
   * through [[handleErrorCodes]], so streams get the same error classification as the
   * non-streamed calls. For engine-level streams: `engine.execJsonStream(site,
   * ...).mapError(mapHttpStatusErrors)` (the service-level streaming methods of
   * `WSClientWithEngineOutputStreamingBase` apply it already).
   */
  protected def mapHttpStatusErrors: PartialFunction[Throwable, Throwable] = {
    case e: CequenceWSHttpStatusException =>
      try handleErrorCodes(e.statusCode, e.body)
      catch { case NonFatal(classified) => classified }
  }

  protected def handleNotFoundAndError(response: RichResponse): Option[Response] =
    response.response.orElse(
      if (response.status.code == 404) None else Some(getResponseOrError(response))
    )
}
