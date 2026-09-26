package io.cequence.wsclient.service.ws

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DirectWSClientSpec extends AnyWordSpec with Matchers {

  "DirectWSClient.normalizeUrl" should {

    "default a scheme-less URL to https" in {
      DirectWSClient.normalizeUrl("api.example.com/v1") shouldBe "https://api.example.com/v1"
      DirectWSClient.normalizeUrl("localhost:9000") shouldBe "https://localhost:9000"
      DirectWSClient.normalizeUrl("  api.example.com ") shouldBe "https://api.example.com"
      // a host that merely starts with "http" used to be taken for a scheme
      DirectWSClient.normalizeUrl("httpbin.org/get") shouldBe "https://httpbin.org/get"
    }

    "keep an explicit http or https scheme (case-insensitively)" in {
      DirectWSClient.normalizeUrl("https://api.example.com") shouldBe "https://api.example.com"
      DirectWSClient.normalizeUrl("http://localhost:9000") shouldBe "http://localhost:9000"
      DirectWSClient.normalizeUrl("HTTPS://api.example.com") shouldBe "HTTPS://api.example.com"
    }

    "reject other schemes instead of treating them as http" in {
      // previously accepted by a plain startsWith("http") check
      an[IllegalArgumentException] should be thrownBy DirectWSClient.normalizeUrl(
        "httpx://api.example.com"
      )
      an[IllegalArgumentException] should be thrownBy DirectWSClient.normalizeUrl(
        "ftp://files.example.com"
      )
    }
  }
}
