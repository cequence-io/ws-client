package io.cequence.wsclient.service.ws

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.{Files, Paths}

/**
 * Builds an in-memory multipart/form-data body for backends without native multipart support
 * (e.g. the JDK HttpClient). The wire format matches `PlayWSMultipartWritable` (the Play/Akka
 * backend's writable) part for part.
 */
object MultipartBodyBuilder {

  def generateBoundary: String =
    "--------" + scala.util.Random.alphanumeric.take(20).mkString("")

  def contentTypeHeaderValue(boundary: String): String =
    s"multipart/form-data; boundary=$boundary"

  def buildInMemory(
    form: MultipartFormData,
    boundary: String,
    charset: String = "utf-8"
  )(
    implicit filePartToContent: FilePart => String
  ): Array[Byte] = {
    val cs = Charset.forName(charset)
    val out = new ByteArrayOutputStream()

    def write(str: String): Unit = out.write(str.getBytes(cs))

    form.dataParts.foreach { case (name, values) =>
      values.foreach { value =>
        write(
          s"--$boundary\r\n${HttpHeaderNames.CONTENT_DISPOSITION}: form-data; name=$name\r\n\r\n$value\r\n"
        )
      }
    }

    form.files.foreach { file =>
      write(
        s"--$boundary\r\n${HttpHeaderNames.CONTENT_DISPOSITION}: form-data; name=${file.name}; filename=${file.filenamePart}\r\n${filePartToContent(file)}\r\n"
      )
      // stream the file into the buffer - readAllBytes would transiently hold a second
      // full-size copy of every file
      Files.copy(Paths.get(file.path), out)
      write("\r\n")
    }

    write(s"--$boundary--")

    out.toByteArray
  }
}
