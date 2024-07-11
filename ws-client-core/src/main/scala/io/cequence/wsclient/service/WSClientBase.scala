package io.cequence.wsclient.service

import io.cequence.wsclient.domain._
import io.cequence.wsclient.service.ws.{FilePart, HttpHeaderNames}

import scala.concurrent.ExecutionContext

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
}
