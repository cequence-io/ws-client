package io.cequence.wsclient.verification

import io.cequence.wsclient.service.ws.{MultipartParams, WireFormat}

import java.nio.charset.StandardCharsets
import scala.util.Random

/**
 * Random texts for the text-level targets: every character a delimiter, an escape or a
 * byte-width boundary.
 */
private object Texts {

  // URL / header delimiters and escapes, controls, the unreserved set, and one character of
  // each UTF-8 width (a supplementary one as a surrogate pair)
  val alphabet: Vector[String] =
    ("aZ9-._~*+ &=#%/?:@!$'(),;\"\\\r\n\t".map(_.toString) ++
      Seq("\u0000", "\u007f", "é", "€", "中", "😀")).toVector

  def text(
    random: Random,
    maxLength: Int
  ): String =
    Seq.fill(random.nextInt(maxLength + 1))(alphabet(random.nextInt(alphabet.size))).mkString

  def hex(text: String): String = LeanConformance.hex(text.getBytes(StandardCharsets.UTF_8))
}

/**
 * `WireFormat.url` against `WireFormat.url` of the Lean model (and so `encodeQueryComponent`).
 */
object WireFormatConformance extends LeanConformance.Target {

  val name = "url"

  private val bases =
    Vector(
      "https://h.example/v1/x",
      "https://h.example/v1/x?api-version=2",
      "http://h/",
      "h?",
      "h?a=1&",
      "h#frag",
      ""
    )

  def generate(random: Random): (String, String) = {
    val base =
      if (random.nextInt(4) == 0) Texts.text(random, 6) else bases(random.nextInt(bases.size))
    val params = Seq.fill(random.nextInt(5)) {
      val value = if (random.nextInt(5) == 0) None else Some(Texts.text(random, 8))
      (Texts.text(random, 6), value)
    }
    val line = (Seq("url", Texts.hex(base), params.size.toString) ++ params.flatMap {
      case (key, value) => Seq(Texts.hex(key), value.fold("-")(Texts.hex))
    }).mkString(" ")
    (line, Texts.hex(WireFormat.url(base, params)))
  }
}

/**
 * `MultipartParams.escape` / `quoted` / `dataPartName` against `Multipart` of the Lean model.
 */
object MultipartConformance extends LeanConformance.Target {

  val name = "multipart"

  // token texts too, so `dataPartName` takes both branches
  private val tokenChars = "abcXYZ019!#$%&'*+-.^_`|~".map(_.toString).toVector

  def generate(random: Random): (String, String) = {
    val text =
      if (random.nextInt(3) == 0)
        Seq.fill(random.nextInt(6))(tokenChars(random.nextInt(tokenChars.size))).mkString
      else Texts.text(random, 10)
    val expected = Seq(
      MultipartParams.escape(text),
      MultipartParams.quoted(text),
      MultipartParams.dataPartName(text)
    ).map(Texts.hex).mkString(" ")
    (s"multipart ${Texts.hex(text)}", expected)
  }
}
