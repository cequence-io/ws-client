package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.CequenceWSException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProxyUrlUtilSpec extends AnyWordSpec with Matchers {

  "ProxyUrlUtil.hostAndPort" should {

    "parse host:port" in {
      ProxyUrlUtil.hostAndPort("proxy.example.com:3128") shouldBe
        ("proxy.example.com" -> 3128)
    }

    "parse scheme://host:port" in {
      ProxyUrlUtil.hostAndPort("http://proxy.example.com:8080") shouldBe
        ("proxy.example.com" -> 8080)
      ProxyUrlUtil.hostAndPort("https://proxy.example.com:443") shouldBe
        ("proxy.example.com" -> 443)
    }

    "fail without a port" in {
      assertThrows[CequenceWSException](ProxyUrlUtil.hostAndPort("proxy.example.com"))
    }

    "fail without a parsable host" in {
      assertThrows[CequenceWSException](ProxyUrlUtil.hostAndPort("://"))
    }

    "never echo the URL (it may carry credentials) in its errors" in {
      Seq(
        "http://user:s3cret@proxy.example.com",
        "http://user:s3cret@[bad",
        "s3cret^^"
      ).foreach { url =>
        val e = the[CequenceWSException] thrownBy ProxyUrlUtil.hostAndPort(url)
        e.getMessage should not include "s3cret"
      }
    }
  }

  "ProxyUrlUtil.redacted" should {

    "keep only host and port - no credentials, path or query" in {
      ProxyUrlUtil.redacted("http://user:s3cret@proxy.example.com:8080/p?token=t0k") shouldBe
        "proxy.example.com:8080"
      ProxyUrlUtil.redacted("proxy.example.com:3128") shouldBe "proxy.example.com:3128"
      ProxyUrlUtil.redacted("http://user:s3cret@[bad") shouldBe "<unparseable proxy URL>"
    }
  }
}
