package io.cequence.wsclient.service.ws

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayOutputStream
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Paths}
import scala.util.Random

/**
 * The Content-Disposition parameter encoding of `MultipartBodyBuilder` (shared with the Play
 * engines' `PlayWSMultipartWritable` via `FilePart` / `MultipartParams`): ordinary names give
 * the bytes of the 1.1.2 builder (a verbatim copy below), a quote or CR/LF cannot leave its
 * parameter.
 */
class MultipartBodyBuilderSpec extends AnyWordSpec with Matchers {

  private val Seed = 20261023L
  private val Forms = 2000
  private val Boundary = "--------boundary0123456789"

  private val contentType: FilePart => String = file =>
    if (file.filenameAux.endsWith(".txt")) "content-type: text/plain\r\n" else ""

  // the 1.1.2 builder, verbatim, with its FilePart.name / filenamePart inlined
  private def previousBuildInMemory(
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
      val name = "\"" + file.key + "\""
      val filenamePart = "\"" + file.filenameAux + "\""
      write(
        s"--$boundary\r\n${HttpHeaderNames.CONTENT_DISPOSITION}: form-data; name=$name; filename=$filenamePart\r\n${filePartToContent(file)}\r\n"
      )
      Files.copy(Paths.get(file.path), out)
      write("\r\n")
    }

    write(s"--$boundary--")

    out.toByteArray
  }

  private def string(
    rnd: Random,
    alphabet: String,
    maxLength: Int
  ) =
    Seq.fill(1 + rnd.nextInt(maxLength))(alphabet(rnd.nextInt(alphabet.length))).mkString

  private val TokenChars =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'*+-.^_`|~"
  // anything but a quote, CR and LF - the characters that are encoded
  private val OrdinaryChars = TokenChars + " ;,=[]{}()<>@:/?\\\té€"
  private val AnyChars = OrdinaryChars + "\"\r\n"

  "MultipartBodyBuilder" should {

    s"build exactly the 1.1.2 bytes for ordinary names ($Forms seeded forms)" in {
      val rnd = new Random(Seed)
      val dir = Files.createTempDirectory("ws-client-multipart-spec")
      try {
        (1 to Forms).foreach { i =>
          val files = (0 until rnd.nextInt(3)).map { j =>
            val path = dir.resolve(s"form-$i-$j.${if (rnd.nextBoolean()) "txt" else "bin"}")
            Files.write(path, string(rnd, AnyChars, 30).getBytes(StandardCharsets.UTF_8))
            FilePart(
              string(rnd, OrdinaryChars, 10),
              path.toString,
              if (rnd.nextBoolean()) Some(string(rnd, OrdinaryChars, 20)) else None
            )
          }
          val form = MultipartFormData(
            dataParts = (0 until rnd.nextInt(4)).map { _ =>
              // data-part names: tokens - only they went out unquoted in 1.1.2 AND now
              string(rnd, TokenChars, 10) -> Seq.fill(1 + rnd.nextInt(2))(
                string(rnd, AnyChars, 30) // values are content: never encoded
              )
            }.toMap,
            files = files
          )

          withClue(s"form #$i $form: ") {
            new String(
              MultipartBodyBuilder.buildInMemory(form, Boundary)(contentType),
              StandardCharsets.UTF_8
            ) shouldBe new String(
              previousBuildInMemory(form, Boundary)(contentType),
              StandardCharsets.UTF_8
            )
          }
        }
      } finally {
        Files.list(dir).forEach(path => Files.delete(path))
        Files.delete(dir)
      }
    }

    "encode a quote, CR and LF in every caller-supplied name, keeping each part ONE part" in {
      val dir = Files.createTempDirectory("ws-client-multipart-spec")
      val path = dir.resolve("upload.txt")
      Files.write(path, "content".getBytes(StandardCharsets.UTF_8))
      try {
        val form = MultipartFormData(
          dataParts = Map("k\"\r\nY: 2" -> Seq("value")),
          files = Seq(
            FilePart("f\"; filename=\"x\r\nZ: 3", path.toString),
            FilePart("file", path.toString, Some("a\"; name=\"x\r\nX: 1\r\n\r\nP"))
          )
        )
        new String(
          MultipartBodyBuilder.buildInMemory(form, Boundary)(contentType),
          StandardCharsets.UTF_8
        ) shouldBe
          // (no \" inside s"..." - Scala 2.12 cannot lex it)
          "--" + Boundary +
          "\r\ncontent-disposition: form-data; name=\"k%22%0D%0AY: 2\"\r\n\r\nvalue\r\n" +
          "--" + Boundary +
          "\r\ncontent-disposition: form-data; name=\"f%22; filename=%22x%0D%0AZ: 3\"; " +
          "filename=\"upload.txt\"\r\ncontent-type: text/plain\r\n\r\ncontent\r\n" +
          "--" + Boundary + "\r\ncontent-disposition: form-data; name=\"file\"; " +
          "filename=\"a%22; name=%22x%0D%0AX: 1%0D%0A%0D%0AP\"\r\n\r\ncontent\r\n" +
          "--" + Boundary + "--"
      } finally {
        Files.delete(path)
        Files.delete(dir)
      }
    }
  }

  "FilePart.extension" should {

    "be the part after the last dot - empty without a dot, after a trailing one, or of only dots" in {
      FilePart("file", "/tmp/report.pdf").extension shouldBe "pdf"
      FilePart("file", "/tmp/archive.tar.gz").extension shouldBe "gz"
      FilePart("file", "/tmp/data.json", Some("upload.TXT")).extension shouldBe "TXT"
      FilePart("file", "/tmp/.env").extension shouldBe "env"
      // a name without a dot was its own extension, a trailing dot gave the part before it
      FilePart("file", "/tmp/README").extension shouldBe ""
      FilePart("file", "/tmp/pdf").extension shouldBe ""
      FilePart("file", "/tmp/notes.").extension shouldBe ""
      FilePart("file", "/tmp/archive.tar.").extension shouldBe ""
      // threw NoSuchElementException - and so did the default content-type lookup
      FilePart("file", "/tmp/data.json", Some("...")).extension shouldBe ""
      FilePart("file", "/tmp/data.json", Some(".")).extension shouldBe ""
      FilePart("file", "/tmp/..").extension shouldBe ""
      FilePart("file", "").extension shouldBe ""
    }
  }

  "MultipartParams.dataPartName" should {

    "keep a token unquoted and quote (and encode) anything else" in {
      MultipartParams.dataPartName("purpose") shouldBe "purpose"
      MultipartParams.dataPartName("a.b-c_d~1") shouldBe "a.b-c_d~1"
      MultipartParams.dataPartName("tags[]") shouldBe "\"tags[]\""
      MultipartParams.dataPartName("my field") shouldBe "\"my field\""
      MultipartParams.dataPartName("a;b=c") shouldBe "\"a;b=c\""
      MultipartParams.dataPartName("") shouldBe "\"\""
      MultipartParams.dataPartName("k\"\r\n") shouldBe "\"k%22%0D%0A\""
    }
  }
}
