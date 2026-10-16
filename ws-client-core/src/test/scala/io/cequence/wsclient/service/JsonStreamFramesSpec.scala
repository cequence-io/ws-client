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

    "keep the data-prefix rule: anything before the first 'data: ' is dropped" in {
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
    }
  }
}
