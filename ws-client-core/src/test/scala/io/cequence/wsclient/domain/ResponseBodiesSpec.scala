package io.cequence.wsclient.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets

class ResponseBodiesSpec extends AnyWordSpec with Matchers {

  private def body(
    raw: Array[Byte],
    charset: java.nio.charset.Charset = StandardCharsets.UTF_8
  ) = ResponseBodies(raw, charset, "svc", Some("endpoint"))

  private val replacement = new String(Character.toChars(0xfffd))

  "ResponseBodies" should {

    "keep only the text of a valid UTF-8 body - bytes re-encode to exactly the received ones" in {
      val text = "plain, " + new String(Character.toChars(0x1f600)) + ", " +
        new String(Character.toChars(0xfeff)) + " a BOM"
      val raw = text.getBytes(StandardCharsets.UTF_8)
      val response = body(raw)

      response shouldBe a[StringBackedResponse]
      response.string shouldBe text
      response.bytes.toArray shouldBe raw
    }

    "keep the raw bytes of a body that is not valid UTF-8" in {
      val raw = Array[Byte](0x7b, 0xc3.toByte, 0x28, 0xff.toByte, 0x00, 0x7d)
      val response = body(raw)

      response shouldBe a[TextAndBytesResponse]
      response shouldBe a[StringBackedResponse] // as every buffered non-Play body, as in 1.1.x
      response.bytes.toArray shouldBe raw
      response.string should include(replacement)
    }

    "tell two bodies apart by their raw bytes, not only by the decoded text" in {
      val invalid = body(Array[Byte](0x61, 0x80.toByte)) // 'a' + an invalid byte
      val literal = body(("a" + replacement).getBytes(StandardCharsets.UTF_8))
      invalid.string shouldBe literal.string
      invalid should not equal literal
      invalid shouldBe body(Array[Byte](0x61, 0x80.toByte))
    }

    "keep the raw bytes of a valid UTF-8 body holding the replacement character itself" in {
      val raw = ("a" + replacement + "b").getBytes(StandardCharsets.UTF_8)
      val response = body(raw)

      response.bytes.toArray shouldBe raw
      response.string shouldBe "a" + replacement + "b"
    }

    "keep the raw bytes of a body in another charset, its text decoded with that charset" in {
      val raw = Array[Byte](0x63, 0x61, 0x66, 0xe9.toByte)
      val response = body(raw, StandardCharsets.ISO_8859_1)

      response shouldBe a[TextAndBytesResponse]
      response.string shouldBe "caf" + new String(Character.toChars(0xe9))
      response.bytes.toArray shouldBe raw
    }

    "parse JSON from the text, with the usual error for a non-JSON body" in {
      body("""{"a":1}""".getBytes(StandardCharsets.UTF_8)).json.toString shouldBe """{"a":1}"""
      val error = the[CequenceWSException] thrownBy body(Array[Byte](0xff.toByte, 0x7b)).json
      error.getMessage should startWith("svc.endpoint: Response is not a JSON")
    }
  }
}
