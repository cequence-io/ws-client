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
  }
}
