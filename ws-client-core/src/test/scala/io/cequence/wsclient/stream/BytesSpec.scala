package io.cequence.wsclient.stream

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.util.Random

class BytesSpec extends AnyWordSpec with Matchers {

  private val all = Bytes((0 until 256).map(_.toByte).toArray)

  "Bytes" should {

    "copy on apply and toArray, wrap without copying on unsafeWrap" in {
      val array = Array[Byte](1, 2, 3)
      val copied = Bytes(array)
      val wrapped = Bytes.unsafeWrap(array, 1, 2)
      array(1) = 9
      copied.toArray.toSeq shouldBe Seq[Byte](1, 2, 3)
      wrapped.toArray.toSeq shouldBe Seq[Byte](9, 3)
      copied.toArray should not be theSameInstanceAs(copied.toArray)
      an[IndexOutOfBoundsException] should be thrownBy Bytes.unsafeWrap(array, 2, 2)
    }

    "slice as views, clamped to the bounds" in {
      all.slice(10, 13).toArray.toSeq shouldBe Seq[Byte](10, 11, 12)
      all.slice(250, 300).length shouldBe 6
      all.slice(-5, 2).toArray.toSeq shouldBe Seq[Byte](0, 1)
      all.slice(5, 2).isEmpty shouldBe true
      all.take(3).toArray.toSeq shouldBe Seq[Byte](0, 1, 2)
      all.drop(254).toArray.toSeq shouldBe Seq[Byte](-2, -1)
      val (left, right) = all.splitAt(100)
      (left ++ right) shouldBe all
      all.slice(0, 256) should be theSameInstanceAs all
      all.slice(3, 10).slice(2, 4).toArray.toSeq shouldBe Seq[Byte](5, 6)
      all(255) shouldBe (-1).toByte
      an[IndexOutOfBoundsException] should be thrownBy all.slice(0, 3)(3)
    }

    "search" in {
      val text = Bytes.utf8("data: a\n\ndata: b\n\n")
      text.indexOf('\n'.toByte) shouldBe 7
      text.indexOf('\n'.toByte, 8) shouldBe 8
      text.indexOfSlice(Bytes.utf8("\n\n")) shouldBe 7
      text.indexOfSlice(Bytes.utf8("\n\n"), 8) shouldBe 16
      text.indexOfSlice(Bytes.utf8("zz")) shouldBe -1
      text.indexOfSlice(Bytes.empty, 3) shouldBe 3
      text.drop(9).startsWith(Bytes.utf8("data:")) shouldBe true
      text.startsWith(text ++ Bytes.utf8("x")) shouldBe false
    }

    "compare by content, show only a prefix" in {
      Bytes.utf8("abc") shouldBe Bytes(Array[Byte](97, 98, 99))
      Bytes.utf8("abc").hashCode shouldBe Bytes.utf8("xabc").drop(1).hashCode
      Bytes.utf8("abc") should not be Bytes.utf8("abd")
      val secret = Bytes.utf8("secret-token-1234567890")
      secret.toString should not include "secret"
      secret.toString should include("23 bytes")
    }

    "decode and convert" in {
      val text = "zażółć 🚀"
      Bytes.utf8(text).utf8String shouldBe text
      Bytes.utf8(text).decodeString(StandardCharsets.UTF_8) shouldBe text

      val buffer = ByteBuffer.wrap(Array[Byte](1, 2, 3, 4))
      buffer.get()
      Bytes.fromByteBuffer(buffer).toArray.toSeq shouldBe Seq[Byte](2, 3, 4)
      buffer.position() shouldBe 1 // not consumed

      val view = all.slice(10, 20).asByteBuffer
      view.isReadOnly shouldBe true
      view.remaining() shouldBe 10
      view.get(0) shouldBe 10.toByte
    }

    "concatenate and build" in {
      val random = new Random(7)
      val parts =
        Seq.fill(100)(Bytes(Array.fill(random.nextInt(300))(random.nextInt().toByte)))
      val expected = parts.flatMap(_.toArray.toSeq)
      Bytes.concat(parts).toArray.toSeq shouldBe expected
      val builder = new Bytes.Builder
      parts.foreach(builder += _)
      builder.length shouldBe expected.size
      builder.result().toArray.toSeq shouldBe expected
      Bytes.concat(Nil) shouldBe Bytes.empty
    }
  }
}
