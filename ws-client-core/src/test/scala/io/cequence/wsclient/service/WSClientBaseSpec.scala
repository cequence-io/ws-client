package io.cequence.wsclient.service

import io.cequence.wsclient.domain.{
  CequenceWSHttpStatusException,
  SimpleRichResponse,
  StatusData
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext

class WSClientBaseSpec extends AnyWordSpec with Matchers {

  private class DefaultClient extends WSClientBase {
    override protected implicit val ec: ExecutionContext = ExecutionContext.global
    override def close(): Unit = ()
    def mapped(e: Throwable): Option[Throwable] = mapHttpStatusErrors.lift(e)
  }

  private final class ClassifiedException(message: String) extends RuntimeException(message)

  private class ClassifyingClient extends DefaultClient {
    override protected def handleErrorCodes(
      httpCode: Int,
      message: String
    ): Nothing =
      throw new ClassifiedException(s"classified $httpCode: $message")
  }

  private val rateLimited = SimpleRichResponse(None, StatusData(429, "slow down"), Map.empty)

  "WSClientBase.handleErrorCodes (default)" should {

    "fail a non-acceptable response with a structured, backward-compatible exception" in {
      val e = the[CequenceWSHttpStatusException] thrownBy new DefaultClient()
        .getResponseOrError(rateLimited)

      e.statusCode shouldBe 429
      e.body shouldBe "slow down"
      e.getMessage shouldBe "Code 429 : slow down"
    }
  }

  "WSClientBase.mapHttpStatusErrors" should {

    "route a structured status failure through the service's handleErrorCodes" in {
      val streamFailure =
        new CequenceWSHttpStatusException("svc: HTTP 429 - slow down", 429, "slow down")
      val mapped = new ClassifyingClient().mapped(streamFailure)

      mapped.map(_.getClass) shouldBe Some(classOf[ClassifiedException])
      mapped.map(_.getMessage) shouldBe Some("classified 429: slow down")
    }

    "leave any other failure alone" in {
      new ClassifyingClient().mapped(new IllegalStateException("boom")) shouldBe None
    }
  }
}
