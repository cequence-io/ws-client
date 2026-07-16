package io.cequence.wsclient.service.ws

import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import play.api.libs.ws.{BodyWritable, InMemoryBody, SourceBody}

import java.nio.file.{Files, Paths}

/**
 * Adapted from `play.api.http.writeableOf_MultipartFormData` but more efficient due to the
 * fact that, rather then fully materializing, form data and files are concatenated as
 * sources/streams before sending out.
 */
object PlayWSMultipartWritable {

  /**
   * `Writeable` for `MultipartFormData` using streaming (SourceBody). Efficient for large
   * files but uses chunked transfer encoding, which some servers (e.g. behind Cloudflare) may
   * reject.
   */
  def writeableOf_MultipartFormData(
    charset: String
  )(
    implicit filePartToContent: FilePart => String
  ): BodyWritable[MultipartFormData] = {

    val boundary: String = generateBoundary

    def encode(str: String) = ByteString.apply(str, charset)

    BodyWritable[MultipartFormData](
      transform = { (form: MultipartFormData) =>
        // combined data source
        val dataSource: Source[ByteString, _] =
          Source.single(formatDataParts(form.dataParts, boundary, encode))

        // files as sources
        val fileSources: Seq[Source[ByteString, _]] = form.files.map { file =>
          val fileSource = FileIO.fromPath(Paths.get(file.path))
          Source
            .single(filePartHeader(file, boundary, encode))
            .concat(fileSource)
            .concat(Source.single(encode("\r\n")))
        }

        // file sources combined
        val combinedFileSource =
          fileSources.foldLeft(Source.empty[ByteString])(_.concat(_))

        // all sources concatenated into one
        val finalSource =
          dataSource.concat(combinedFileSource).concat(Source.single(encode(s"--$boundary--")))

        SourceBody(finalSource)
      },
      contentType = s"multipart/form-data; boundary=$boundary"
    )
  }

  /**
   * `Writeable` for `MultipartFormData` using in-memory body (InMemoryBody). Materializes the
   * entire body upfront, avoiding chunked transfer encoding. Use this when the target server
   * does not support chunked requests.
   */
  def writeableOf_MultipartFormDataInMemory(
    charset: String
  )(
    implicit filePartToContent: FilePart => String
  ): BodyWritable[MultipartFormData] = {

    val boundary: String = generateBoundary

    def encode(str: String) = ByteString.apply(str, charset)

    BodyWritable[MultipartFormData](
      transform = { (form: MultipartFormData) =>
        val dataPart = formatDataParts(form.dataParts, boundary, encode)

        val fileParts = form.files.foldLeft(ByteString.empty) {
          (
            acc,
            file
          ) =>
            val header = filePartHeader(file, boundary, encode)
            val fileBytes = ByteString(Files.readAllBytes(Paths.get(file.path)))
            acc ++ header ++ fileBytes ++ encode("\r\n")
        }

        val closing = encode(s"--$boundary--")

        InMemoryBody(dataPart ++ fileParts ++ closing)
      },
      contentType = s"multipart/form-data; boundary=$boundary"
    )
  }

  private def generateBoundary: String =
    "--------" + scala.util.Random.alphanumeric.take(20).mkString("")

  private def formatDataParts(
    data: Map[String, Seq[String]],
    boundary: String,
    encode: String => ByteString
  ): ByteString = {
    val dataParts = data.flatMap { case (name, values) =>
      values.map { value =>
        s"--$boundary\r\n${HttpHeaderNames.CONTENT_DISPOSITION}: form-data; name=$name\r\n\r\n$value\r\n"
      }
    }.mkString("")

    encode(dataParts)
  }

  private def filePartHeader(
    file: FilePart,
    boundary: String,
    encode: String => ByteString
  )(
    implicit filePartToContent: FilePart => String
  ): ByteString =
    encode(
      s"--$boundary\r\n${HttpHeaderNames.CONTENT_DISPOSITION}: form-data; name=${file.name}; filename=${file.filenamePart}\r\n${filePartToContent(file)}\r\n"
    )
}
