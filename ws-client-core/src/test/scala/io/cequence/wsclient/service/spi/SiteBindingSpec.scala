package io.cequence.wsclient.service.spi

import io.cequence.wsclient.domain.{
  CequenceWSTimeoutException,
  RichResponse,
  SimpleRichResponse,
  SiteBinding,
  StatusData,
  WsRequestContext
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SiteBindingSpec extends AnyWordSpec with Matchers {

  private def response(
    code: Int,
    message: String
  ): RichResponse =
    SimpleRichResponse(None, StatusData(code, message), Map.empty)

  // a typical engine default: normalizes raw transport failures by throwing Cequence exceptions
  private val default: String => PartialFunction[Throwable, RichResponse] =
    name => { case e: IllegalStateException =>
      throw new CequenceWSTimeoutException(s"$name timed out: ${e.getMessage}.")
    }

  "SiteBinding.resolveRecoverErrors" should {

    "return the default mapping when no user recovery is given" in {
      val resolved = SiteBinding.resolveRecoverErrors(None, default)

      assertThrows[CequenceWSTimeoutException] {
        resolved("svc")(new IllegalStateException("boom"))
      }
    }

    "apply user recovery to the normalized (Cequence) exception" in {
      val user: String => PartialFunction[Throwable, RichResponse] =
        _ => { case _: CequenceWSTimeoutException => response(599, "fallback") }

      val resolved = SiteBinding.resolveRecoverErrors(Some(user), default)

      resolved("svc")(new IllegalStateException("boom")) shouldBe response(599, "fallback")
    }

    "apply user recovery to raw exceptions the default does not cover" in {
      val user: String => PartialFunction[Throwable, RichResponse] =
        _ => { case _: IllegalArgumentException => response(598, "raw-fallback") }

      val resolved = SiteBinding.resolveRecoverErrors(Some(user), default)

      resolved("svc")(new IllegalArgumentException("boom")) shouldBe
        response(598, "raw-fallback")
    }

    "let the normalized exception propagate when user recovery does not cover it" in {
      val user: String => PartialFunction[Throwable, RichResponse] =
        _ => { case _: IllegalArgumentException => response(598, "raw-fallback") }

      val resolved = SiteBinding.resolveRecoverErrors(Some(user), default)

      assertThrows[CequenceWSTimeoutException] {
        resolved("svc")(new IllegalStateException("boom"))
      }
    }

    "not be defined for exceptions neither mapping covers" in {
      val user: String => PartialFunction[Throwable, RichResponse] =
        _ => { case _: IllegalArgumentException => response(598, "raw-fallback") }

      val resolved = SiteBinding.resolveRecoverErrors(Some(user), default)

      resolved("svc").isDefinedAt(new RuntimeException("boom")) shouldBe false
    }
  }

  "SiteBinding.requestContextFn" should {

    "return the fixed context when no function is given" in {
      val context = WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer fixed"))

      SiteBinding("http://x", context).requestContextFn() shouldBe context
    }

    "re-evaluate the function on every call" in {
      val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      val site = SiteBinding(
        "http://x",
        requestContextFun = Some(() =>
          WsRequestContext(
            authHeaders = Seq("Authorization" -> s"Bearer t${counter.incrementAndGet()}")
          )
        )
      )

      site.requestContextFn().authHeaders.head._2 shouldBe "Bearer t1"
      site.requestContextFn().authHeaders.head._2 shouldBe "Bearer t2"
    }

    "ignore the fixed requestContext once requestContextFun is set" in {
      val fixed = WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer fixed"))
      val site = SiteBinding(
        "http://x",
        requestContext = fixed,
        requestContextFun =
          Some(() => WsRequestContext(authHeaders = Seq("Authorization" -> "Bearer dynamic")))
      )

      site.requestContextFn().authHeaders.head._2 shouldBe "Bearer dynamic"
    }
  }

  "SiteBinding defaults" should {

    "default requestContext to an empty WsRequestContext" in {
      SiteBinding("http://x").requestContext shouldBe WsRequestContext()
    }

    "default recoverErrors, requestContextFun and label to None" in {
      val site = SiteBinding("http://x")

      site.recoverErrors shouldBe None
      site.requestContextFun shouldBe None
      site.label shouldBe None
    }

    "carry an explicitly provided label through" in {
      val site = SiteBinding("http://x", label = Some("my-service"))

      site.label shouldBe Some("my-service")
    }
  }

  "SiteBinding.createURL" should {

    "join a coreUrl without a trailing slash and an endpoint with a single slash" in {
      SiteBinding("http://x").createURL(Some("ping")) shouldBe "http://x/ping"
    }

    "not duplicate the slash when coreUrl already ends with one" in {
      SiteBinding("http://x/").createURL(Some("ping")) shouldBe "http://x/ping"
    }

    "return the bare coreUrl when no endpoint is given at all" in {
      SiteBinding("http://x").createURL(None) shouldBe "http://x"
    }

    "return the bare coreUrl when the endpoint is empty and no value is given" in {
      SiteBinding("http://x").createURL(Some("")) shouldBe "http://x"
    }

    "keep the trailing slash from coreUrl when no endpoint or value is given" in {
      SiteBinding("http://x/").createURL(None) shouldBe "http://x/"
    }

    "append a path value after the endpoint, slash-separated" in {
      SiteBinding("http://x").createURL(Some("items"), Some("42")) shouldBe "http://x/items/42"
    }

    "not duplicate the slash for endpoint + value when coreUrl already ends with one" in {
      SiteBinding("http://x/").createURL(Some("items"), Some("42")) shouldBe
        "http://x/items/42"
    }

    "produce a double slash when a value is given without an endpoint (endpoint and value " +
      "are meant to be used together)" in {
        SiteBinding("http://x").createURL(Some(""), Some("42")) shouldBe "http://x//42"
      }
  }
}
