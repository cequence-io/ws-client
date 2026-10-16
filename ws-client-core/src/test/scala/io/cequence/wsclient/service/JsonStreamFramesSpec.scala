package io.cequence.wsclient.service

import io.cequence.wsclient.domain.CequenceWSException
import io.cequence.wsclient.service.JsonStreamFrames.{EndOfStream, JsonFrame, Skip, parseFrame}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

class JsonStreamFramesSpec extends AnyWordSpec with Matchers {

  "JsonStreamFrames.parseFrame" should {

    "skip an SSE comment frame (keep-alive)" in {
      parseFrame(": OPENROUTER PROCESSING") shouldBe Skip
      parseFrame(":") shouldBe Skip
      parseFrame(": ping\r") shouldBe Skip
      parseFrame(":a\n:b") shouldBe Skip
      parseFrame(": ping\r\n: pong\r\n") shouldBe Skip
    }

    "skip a blank frame" in {
      parseFrame("") shouldBe Skip
      parseFrame("\n") shouldBe Skip
      parseFrame("\r\n") shouldBe Skip
      parseFrame("  \n\t") shouldBe Skip
    }

    "drop comment lines around a data line and still parse its JSON" in {
      parseFrame(": keep-alive\ndata: {\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("data: {\"a\":1}\n: trailing comment") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame(": x\r\ndata: {\"a\":1}\r\n: y\r\n") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame(": x\ndata: [DONE]") shouldBe EndOfStream
    }

    "recognize the data field at a line start only" in {
      // 'data: ' inside a string value is not a field: the raw payload is parsed whole
      parseFrame("{\"a\":\"see data: x\"}") shouldBe JsonFrame(Json.obj("a" -> "see data: x"))
      parseFrame("{\"a\"\n:\"see data: x\"}") shouldBe JsonFrame(
        Json.obj("a" -> "see data: x")
      )
      // ... and the same inside an event's payload
      parseFrame("data: {\"a\":\"see data: x\"}") shouldBe JsonFrame(
        Json.obj("a" -> "see data: x")
      )
      // a comment line is never a data line, whatever it says
      parseFrame(": data: {\"a\":1}") shouldBe Skip
    }

    "accept the data field with or without the space after the colon" in {
      parseFrame("data:{\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame(": ping\ndata:{\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("data:[DONE]") shouldBe EndOfStream
      // only ONE space is part of the field syntax; more is payload (JSON tolerates it)
      parseFrame("data:  {\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      // a custom prefix without a trailing space is matched verbatim
      parseFrame("item:{\"a\":1}", itemPrefix = "item:") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("item: {\"a\":1}", itemPrefix = "item:") shouldBe JsonFrame(
        Json.obj("a" -> 1)
      )
    }

    "drop leading comment lines before a raw payload, keeping its interior ':'-lines" in {
      parseFrame(": keep-alive\n{\"candidates\":[]}") shouldBe
        JsonFrame(Json.obj("candidates" -> Json.arr()))
      parseFrame(": a\n: b\n{\"a\"\n:1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame(": c\r\n[{\"i\":1}", stripPrefix = Some("[")) shouldBe JsonFrame(
        Json.obj("i" -> 1)
      )
    }

    "understand CR and CRLF line breaks inside a frame" in {
      parseFrame(": keepalive\rdata: {\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("data: {\"a\":1}\r: keepalive") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("event: x\r\ndata: {\"a\":1}\r\n") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("data: {\"a\":1}\r\n\r\n") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("data: [DONE]\r\n") shouldBe EndOfStream
    }

    "keep the data-prefix rule: anything before the first 'data: ' line is dropped" in {
      parseFrame("event: message\nid: 7\ndata: {\"a\":1}") shouldBe JsonFrame(
        Json.obj("a" -> 1)
      )
      parseFrame("data: {\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
    }

    "parse a frame without a data prefix as a raw JSON payload, ':'-leading lines included" in {
      parseFrame("{\"a\":1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      // no 'data: ' line = not an SSE event: a line starting with ':' is part of the payload
      parseFrame("{\"a\"\n:1}") shouldBe JsonFrame(Json.obj("a" -> 1))
      parseFrame("{\"a\"\r\n:1}\r") shouldBe JsonFrame(Json.obj("a" -> 1))
      // ... unless the frame holds nothing but comment lines
      parseFrame(": not an event") shouldBe Skip
    }

    "signal the end of the stream at [DONE], also with CRLF line endings" in {
      parseFrame("data: [DONE]") shouldBe EndOfStream
      parseFrame("data: [DONE]\r") shouldBe EndOfStream
      parseFrame("[DONE]") shouldBe EndOfStream
    }

    "strip a configured prefix and suffix" in {
      parseFrame("[{\"i\":1}", stripPrefix = Some("["), stripSuffix = Some("]")) shouldBe
        JsonFrame(Json.obj("i" -> 1))
      parseFrame("{\"i\":2}]", stripPrefix = Some("["), stripSuffix = Some("]")) shouldBe
        JsonFrame(Json.obj("i" -> 2))
    }

    "fail with CequenceWSException on a non-JSON payload, quoting the frame" in {
      val e = intercept[CequenceWSException](parseFrame("data: not-json"))
      e.getMessage should include("not a JSON")
      e.getMessage should include("data: not-json")

      // a comment line does not rescue a frame whose data is not JSON
      intercept[CequenceWSException](parseFrame(": c\ndata: not-json"))

      // inside an SSE event a ':'-leading line IS a comment (SSE spec), so this payload is cut;
      // a JSON serializer never puts a colon at a line start
      intercept[CequenceWSException](parseFrame("data: {\"a\"\n:1}"))
    }
  }
}
