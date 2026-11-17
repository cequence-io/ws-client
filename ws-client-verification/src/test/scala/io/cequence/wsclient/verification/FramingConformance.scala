package io.cequence.wsclient.verification

import io.cequence.wsclient.domain.{CequenceWSException, CequenceWSFrameTooLongException}
import io.cequence.wsclient.stream.StreamTransformers.DelimiterFramer
import io.cequence.wsclient.stream.{AsyncStream, ByteFraming, Bytes}

import java.nio.ByteBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Random

/**
 * Delimiter framing: `ByteFraming.delimiter` (on the neutral interpreter) against
 * `ByteFramingModel`, and the engines' `StreamTransformers.DelimiterFramer` against
 * `EngineFramerModel`.
 *
 * Inputs are built from runs of two or three byte values, whole delimiters and delimiter
 * prefixes (the delimiter is drawn from the same bytes, so partial and overlapping matches are
 * frequent); 30% are 200 - 1200 bytes long, cut into chunks of up to 400 bytes - beyond the
 * framers' initial 256-byte buffer, so compaction and growth run. Limits range from 1 to past
 * the input length; empty chunks occur.
 */
object FramingConformance extends LeanConformance.Target {

  val name = "framing"

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val pool: Array[Byte] = "abx\r\n".getBytes("UTF-8")

  def generate(random: Random): (String, String) = {
    val alphabet = random.shuffle(pool.toSeq).take(2 + random.nextInt(2)).toArray
    def pick(): Byte = alphabet(random.nextInt(alphabet.length))

    val delimiter = Array.fill(1 + random.nextInt(4))(pick())
    val long = random.nextInt(10) < 3
    val length = if (long) 200 + random.nextInt(1000) else random.nextInt(25)
    val input = {
      val tokens = Iterator.continually {
        random.nextInt(4) match {
          case 0 => delimiter
          case 1 => delimiter.take(1 + random.nextInt(delimiter.length))
          case _ => Array.fill(1 + random.nextInt(if (long) 60 else 4))(pick())
        }
      }
      tokens.flatten.take(length).toArray
    }
    val chunks = chunk(input, random, long)
    val max = random.nextInt(4) match {
      case 0 => 1 + random.nextInt(4)
      case 1 => 1 + random.nextInt(input.length + 4)
      case 2 => 1 + random.nextInt(80)
      case _ => 5000
    }
    val allowTruncation = random.nextBoolean()

    val line = (Seq(
      "framing",
      LeanConformance.hex(delimiter),
      max.toString,
      if (allowTruncation) "1" else "0",
      chunks.size.toString
    ) ++ chunks.map(LeanConformance.hex)).mkString(" ")
    val expected =
      s"${byteFraming(delimiter, max, allowTruncation, chunks)} | ${engineFramer(delimiter, max, chunks)}"
    (line, expected)
  }

  private def chunk(
    input: Array[Byte],
    random: Random,
    long: Boolean
  ): Seq[Array[Byte]] = {
    val chunks = Seq.newBuilder[Array[Byte]]
    var at = 0
    while (at < input.length) {
      if (random.nextInt(10) == 0) chunks += Array.emptyByteArray
      val size = 1 + random.nextInt(if (long) 400 else 6)
      chunks += input.slice(at, at + size)
      at += size
    }
    if (random.nextInt(10) == 0) chunks += Array.emptyByteArray
    chunks.result()
  }

  private def result(
    frames: Seq[Array[Byte]],
    ending: String
  ): String =
    (Seq(ending, frames.size.toString) ++ frames.map(LeanConformance.hex)).mkString(" ")

  private def byteFraming(
    delimiter: Array[Byte],
    max: Int,
    allowTruncation: Boolean,
    chunks: Seq[Array[Byte]]
  ): String = {
    val frames = Vector.newBuilder[Array[Byte]]
    val stream = AsyncStream
      .fromIterable(chunks.map(Bytes(_)).toList)
      .via(ByteFraming.delimiter(Bytes(delimiter), max, allowTruncation))
    val ending =
      try {
        Await.result(stream.runForeach(frame => frames += frame.toArray), 30.seconds)
        "completed"
      } catch {
        case _: CequenceWSFrameTooLongException => "tooLong"
        case _: CequenceWSException             => "truncated"
      }
    result(frames.result(), ending)
  }

  private def engineFramer(
    delimiter: Array[Byte],
    max: Int,
    chunks: Seq[Array[Byte]]
  ): String = {
    val framer = new DelimiterFramer(delimiter, max)
    val frames = Vector.newBuilder[Array[Byte]]
    def bytes(buffer: ByteBuffer): Array[Byte] = {
      val array = new Array[Byte](buffer.remaining())
      buffer.get(array)
      array
    }
    val ending =
      try {
        chunks.foreach { chunk =>
          framer
            .onInput(ByteBuffer.wrap(chunk))
            .elements
            .foreach(frame => frames += bytes(frame))
        }
        framer.onUpstreamComplete().foreach(frame => frames += bytes(frame))
        "completed"
      } catch { case _: CequenceWSFrameTooLongException => "tooLong" }
    result(frames.result(), ending)
  }
}
