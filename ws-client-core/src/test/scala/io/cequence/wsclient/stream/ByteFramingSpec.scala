package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.{CequenceWSException, CequenceWSFrameTooLongException}
import io.cequence.wsclient.stream.StreamTransformers.DelimiterFramer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsString, Json}

import java.nio.ByteBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

class ByteFramingSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  private def chunked(
    bytes: Array[Byte],
    random: Random
  ): Seq[Bytes] = {
    val chunks = Seq.newBuilder[Bytes]
    var i = 0
    while (i < bytes.length) {
      val n = 1 + random.nextInt(math.max(1, math.min(bytes.length - i, 40)))
      chunks += Bytes(java.util.Arrays.copyOfRange(bytes, i, i + n))
      i += n
    }
    chunks.result()
  }

  // the engines' DelimiterFramer as the reference: frames, or the failure class
  private def reference(
    chunks: Seq[Bytes],
    delimiter: Array[Byte],
    max: Int
  ): Try[Seq[Seq[Byte]]] = Try {
    val framer = new DelimiterFramer(delimiter, max)
    val frames = chunks.flatMap(c => framer.onInput(ByteBuffer.wrap(c.toArray)).elements) ++
      framer.onUpstreamComplete()
    frames.map { b =>
      val a = new Array[Byte](b.remaining()); b.get(a); a.toSeq
    }
  }

  "ByteFraming.delimiter" should {

    "match the engines' DelimiterFramer (3000 random inputs and chunkings)" in {
      (1 to 3000).foreach { seed =>
        val random = new Random(seed)
        val delimiter = if (random.nextBoolean()) "\n\n".getBytes else Array[Byte](13, 10)
        val max = 1 + random.nextInt(30)
        val alphabet = Array[Byte]('a', 'b', '\n', 13, 10)
        val input = Array.fill(random.nextInt(120))(alphabet(random.nextInt(alphabet.length)))
        val chunks = chunked(input, random)

        val expected = reference(chunks, delimiter, max)
        val actual = Try(
          run(
            AsyncStream
              .fromIterable(chunks.toList)
              .via(ByteFraming.delimiter(Bytes(delimiter), max))
          ).map(_.toArray.toSeq)
        )

        withClue(s"seed $seed: ") {
          (expected, actual) match {
            case (Success(e), Success(a)) => a shouldBe e
            case (Failure(expectedError), Failure(actualError)) =>
              actualError shouldBe a[CequenceWSFrameTooLongException]
              actualError.getClass shouldBe expectedError.getClass
              actualError.getMessage shouldBe expectedError.getMessage
            case other => fail(s"diverged: $other")
          }
        }
      }
    }

    "fail on a truncated final frame without allowTruncation" in {
      val stream = AsyncStream(Bytes.utf8("a\nb"))
        .via(ByteFraming.delimiter(Bytes.utf8("\n"), 10, allowTruncation = false))
      val failure = runFailure(stream)
      failure shouldBe a[CequenceWSException]
      failure should not be a[CequenceWSFrameTooLongException]
    }

    "carry the limit in the typed exception" in {
      val failure = runFailure(
        AsyncStream(Bytes.utf8("x" * 50)).via(ByteFraming.delimiter(Bytes.utf8("\n"), 10))
      )
      failure shouldBe a[CequenceWSFrameTooLongException]
      failure.asInstanceOf[CequenceWSFrameTooLongException].maxFrameLength shouldBe 10
    }

    "stay linear on a large frame arriving in small chunks" in {
      val frame = Array.fill[Byte](8 * 1024 * 1024)('x')
      val chunks = frame.grouped(1460).map(Bytes(_)).toList :+ Bytes.utf8("\n\n")
      val start = System.nanoTime()
      val frames =
        await(
          AsyncStream
            .fromIterable(chunks)
            .via(ByteFraming.delimiter(Bytes.utf8("\n\n"), Int.MaxValue - 16))
            .runToSeq,
          60.seconds
        )
      frames.map(_.length) shouldBe Seq(frame.length)
      ((System.nanoTime() - start) / 1e9) should be < 10.0
    }

    "split lines, stripping CR" in {
      val stream = AsyncStream(Bytes.utf8("one\r\ntw"), Bytes.utf8("o\nthree"))
        .via(ByteFraming.lines(100))
      run(stream) shouldBe Seq("one", "two", "three")
    }
  }

  "JsonStreamPipes.jsonFrames" should {

    "parse SSE events with the engines' semantics" in {
      val sse =
        ": keep-alive\n\ndata: {\"a\":1}\n\nevent: x\r\ndata: {\"b\":2}\r\n\n\ndata: \"[DONE]\"\n\ndata: [DONE]\n\ndata: {\"never\":true}\n\n"
      val random = new Random(1)
      val chunks = chunked(sse.getBytes("UTF-8"), random).toList
      run(AsyncStream.fromIterable(chunks).via(JsonStreamPipes.jsonFrames())) shouldBe
        Seq(Json.obj("a" -> 1), Json.obj("b" -> 2), JsString("[DONE]"))
    }

    "fail a non-JSON frame and an oversized frame" in {
      runFailure(
        AsyncStream(Bytes.utf8("data: nope\n\n")).via(JsonStreamPipes.jsonFrames())
      ) shouldBe
        a[CequenceWSException]
      runFailure(
        AsyncStream(Bytes.utf8("data: " + "1" * 100 + "\n\n"))
          .via(JsonStreamPipes.jsonFrames(maxFrameLength = 50))
      ) shouldBe a[CequenceWSFrameTooLongException]
    }

    "strip a prefix and suffix (Gemini array framing)" in {
      val body = "[{\"a\":1}\n,\r\n{\"a\":2}]"
      val pipe =
        JsonStreamPipes.jsonFrames("\n,\r\n", stripPrefix = Some("["), stripSuffix = Some("]"))
      run(AsyncStream(Bytes.utf8(body)).via(pipe)) shouldBe Seq(
        Json.obj("a" -> 1),
        Json.obj("a" -> 2)
      )
    }
  }
}
