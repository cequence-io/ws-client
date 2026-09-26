package io.cequence.wsclient.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

class StringBackedResponseSpec extends AnyWordSpec with Matchers {

  "StringBackedResponse.json" should {

    "parse a JSON body" in {
      StringBackedResponse("""{"a":[1,2]}""").json shouldBe Json.obj("a" -> Json.arr(1, 2))
    }

    "reject absurdly deep nesting with a CequenceWSException, not a JVM error" in {
      // guarded by Jackson's StreamReadConstraints (2.15+; CVE-2025-52999)
      val depth = 100000
      val deep = "[" * depth + "]" * depth

      val e = the[CequenceWSException] thrownBy StringBackedResponse(deep, "svc").json
      e.getMessage should include("Response is not a JSON")
    }
  }
}
