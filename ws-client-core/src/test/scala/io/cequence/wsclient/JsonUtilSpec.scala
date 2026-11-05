package io.cequence.wsclient

import io.cequence.wsclient.JsonUtil.JsonOps
import io.cequence.wsclient.domain.CequenceWSException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json._

class JsonUtilSpec extends AnyWordSpec with Matchers {

  private case class Item(id: String)
  private implicit val itemReads: Reads[Item] = Json.reads[Item]

  // the shape of a remote JSON response that fails the caller's Reads: deep and wide
  private def deepAndWide(
    depth: Int,
    width: Int
  ): JsValue =
    (1 to depth).foldLeft[JsValue](JsArray(Seq.fill(width)(Json.obj("a" -> 0)))) {
      (
        inner,
        _
      ) =>
        Json.obj("a" -> inner)
    }

  "JsonOps.asSafe" should {

    "return the value of a JSON that validates" in {
      Json.obj("id" -> "x").asSafe[Item] shouldBe Item("x")
    }

    "describe a mismatching value with a compact excerpt and the validation errors" in {
      val e = the[CequenceWSException] thrownBy Json.obj("name" -> "x").asSafe[Item]

      e.getMessage should startWith(
        "Unexpected JSON:\n'{\"name\":\"x\"}'. Cannot be parsed due to:"
      )
      e.getMessage should include("/id")
      // rendered once - our own exception is not wrapped a second time
      e.getMessage should not include "Error thrown while processing a JSON"
    }

    "bound the message for a huge, deeply nested value (up to 1.1.4: pretty-printed twice)" in {
      // a 643 KB response of this shape exhausted a 512 MiB heap: the pretty-printed text grew
      // with size x depth
      val e =
        the[CequenceWSException] thrownBy deepAndWide(depth = 900, width = 20000).asSafe[Item]

      e.getMessage.length should be < 1500
      e.getMessage should include("{...}")
    }

    "bound a huge flat array and a long string" in {
      val array = JsArray(Seq.fill(100000)(JsString("x" * 100)))
      (the[CequenceWSException] thrownBy array.asSafe[Item]).getMessage.length should be < 1500

      val string = JsString("y" * 1000000)
      (the[CequenceWSException] thrownBy string
        .asSafe[Item]).getMessage.length should be < 1500
    }

    "wrap an exception thrown by a Reads with the excerpt" in {
      val throwing = Reads[Item](_ => throw new IllegalStateException("boom"))

      val e = the[CequenceWSException] thrownBy Json.obj("id" -> "x").asSafe[Item](throwing)

      e.getMessage shouldBe "Error thrown while processing a JSON '{\"id\":\"x\"}'. Cause: boom"
    }
  }

  "JsonUtil.jsonExcerpt" should {

    "render a small value as compact JSON" in {
      val value = Json.obj("a" -> Json.arr(1, true, JsNull, "s"), "b" -> Json.obj())

      JsonUtil.jsonExcerpt(value) shouldBe Json.stringify(value)
    }

    "elide nesting below 16 levels" in {
      JsonUtil.jsonExcerpt(deepAndWide(depth = 20, width = 1)) shouldBe
        ("{\"a\":" * 16) + "{...}" + ("}" * 16)
    }
  }
}
