package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.{CequenceWSException, CequenceWSFrameTooLongException}
import io.cequence.wsclient.stream.StreamTransformers.DelimiterFramer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.collection.mutable

/**
 * A small-scope (bounded-exhaustive) check of delimiter framing: EVERY string up to a length
 * bound over a tiny alphabet x EVERY chunking of it x a few frame limits, against a list model
 * written from the scaladoc of `ByteFraming.delimiter` alone. The model is a function of the
 * whole input, so it is independent of the chunking by construction - the property the random
 * specs only sample.
 *
 * Both framers are checked: `ByteFraming.delimiter` (run through the neutral interpreter) and
 * the engines' `StreamTransformers.DelimiterFramer` (jdk / zio-http). Two divergences from the
 * model are known and allowed here, nothing else (see `classify`).
 *
 * The scope is `-Dwsclient.verify.framing.maxLength` (default 5: about 130,000 cases in 5 s; 6
 * is the scope the framers were audited with - about 590,000 cases, 15 s).
 */
class FramingSmallScopeSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  import FramingSmallScopeSpec._

  private val maxLength = sys.props.getOrElse("wsclient.verify.framing.maxLength", "5").toInt

  // (alphabet, delimiter): a 1-byte delimiter, a 2-byte one, and self-overlapping ones
  private val scopes =
    Seq(("ax", "a"), ("abx", "ab"), ("ax", "aa"), ("ab", "aab"), ("ab", "aba"))
  private val limits = Seq(1, 2, 3, 100)

  private def byteFraming(
    chunks: Seq[String],
    delimiter: String,
    max: Int,
    allowTruncation: Boolean
  ): Result = {
    val seen = Vector.newBuilder[String]
    val stream = AsyncStream
      .fromIterable(chunks.map(Bytes.utf8).toList)
      .via(ByteFraming.delimiter(Bytes.utf8(delimiter), max, allowTruncation))
    val end =
      try {
        await(stream.runForeach(frame => seen += frame.utf8String))
        Completed
      } catch {
        case _: CequenceWSFrameTooLongException => TooLong
        case _: CequenceWSException             => Truncated
      }
    Result(seen.result(), end)
  }

  // the jdk / zio-http engines' framer (it always emits an unterminated rest)
  private def engineFramer(
    chunks: Seq[String],
    delimiter: String,
    max: Int
  ): Result = {
    val framer = new DelimiterFramer(delimiter.getBytes("UTF-8"), max)
    val seen = Vector.newBuilder[String]
    def text(buffer: ByteBuffer): String = {
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      new String(bytes, "UTF-8")
    }
    val end =
      try {
        chunks.foreach { chunk =>
          framer.onInput(ByteBuffer.wrap(chunk.getBytes("UTF-8"))).elements.foreach { frame =>
            seen += text(frame)
          }
        }
        framer.onUpstreamComplete().foreach(frame => seen += text(frame))
        Completed
      } catch { case _: CequenceWSFrameTooLongException => TooLong }
    Result(seen.result(), end)
  }

  private final class Tally {
    val counts: mutable.Map[String, Int] = mutable.LinkedHashMap.empty[String, Int]
    val unexpected: mutable.Buffer[String] = mutable.ListBuffer.empty[String]
    var cases = 0

    def note(
      framer: String,
      verdict: Verdict,
      detail: => String
    ): Unit = {
      val key = s"$framer: $verdict"
      counts(key) = counts.getOrElse(key, 0) + 1
      if (verdict == Unexpected && unexpected.size < 5) unexpected += s"$framer: $detail"
    }
  }

  // every (input, limit, truncation flag, chunking) of one scope
  private def check(
    alphabet: String,
    delimiter: String
  ): Tally = {
    val tally = new Tally
    val length = if (alphabet.length == 2) maxLength + 1 else maxLength
    for {
      input <- strings(alphabet, length)
      max <- limits
      truncation <- Seq(true, false)
    } {
      val expected = reference(input, delimiter, max, truncation)
      chunkings(input).foreach { chunks =>
        tally.cases += 1
        def detail(actual: Result): String =
          s"input ${chunks.mkString("|")}, delimiter $delimiter, max $max, " +
            s"allowTruncation $truncation: expected $expected, got $actual"

        val neutral = byteFraming(chunks, delimiter, max, truncation)
        tally.note("ByteFraming", classify(expected, neutral, delimiter, max), detail(neutral))
        if (truncation) {
          val engine = engineFramer(chunks, delimiter, max)
          tally.note(
            "DelimiterFramer",
            classify(expected, engine, delimiter, max),
            detail(engine)
          )
          if (engine != neutral)
            tally.note("ByteFraming vs DelimiterFramer", Unexpected, detail(engine))
        }
      }
    }
    tally
  }

  "Delimiter framing (ByteFraming.delimiter and the engines' DelimiterFramer)" should {

    scopes.foreach { case (alphabet, delimiter) =>
      s"match the list model on every input, chunking and limit - alphabet '$alphabet', delimiter '$delimiter'" in {
        val tally = check(alphabet, delimiter)
        info(s"${tally.cases} cases; " + tally.counts.map { case (k, n) => s"$k $n" }
          .mkString(", "))
        withClue(tally.unexpected.mkString("\n", "\n", "\n")) {
          tally.unexpected shouldBe empty
        }
        tally.counts.getOrElse(s"ByteFraming: $Same", 0) should be > 0
      }
    }

    // the two allowed divergences are real: when the framers are fixed (both together), this
    // fails - then drop the allowance from `classify`, so the model holds without exception
    "still show the two known edges (exact-limit split delimiter, frames dropped before a failure)" in {
      val tally = check("abx", "ab")
      Seq("ByteFraming", "DelimiterFramer").foreach { framer =>
        tally.counts.getOrElse(s"$framer: $ExactLimitEdge", 0) should be > 0
        tally.counts.getOrElse(s"$framer: $FramesDropped", 0) should be > 0
      }
    }
  }

  "The scope" should {

    "hold every chunking of a string, each one the string again" in {
      val all = chunkings("abcd").toVector
      all.foreach(_.mkString shouldBe "abcd")
      all.distinct.size shouldBe 9 // 2^3 cuts + the variant with empty chunks
      strings("ab", 2).toVector shouldBe Vector("", "a", "b", "aa", "ba", "ab", "bb")
    }
  }
}

object FramingSmallScopeSpec {

  sealed trait End
  case object Completed extends End
  case object TooLong extends End
  case object Truncated extends End

  final case class Result(
    frames: Vector[String],
    end: End
  ) {
    override def toString: String = frames.mkString("['", "','", "'] ") + end
  }

  sealed trait Verdict
  case object Same extends Verdict
  // a frame within delimiter.length - 1 bytes of the limit fails when a chunk boundary splits
  // its delimiter (documented in ByteFraming.delimiter's scaladoc)
  case object ExactLimitEdge extends Verdict
  // frames completed in the same chunk as an oversized one are not emitted before the failure
  case object FramesDropped extends Verdict
  case object Unexpected extends Verdict

  /**
   * The model, from the scaladoc: "Splits the bytes on `delimiter` (not included in the
   * frames; consecutive delimiters yield empty frames). A frame longer than `maxFrameLength`
   * fails the stream - also one whose delimiter arrives, and an unterminated rest that
   * outgrows the limit. At the end, an unterminated non-empty rest is emitted as the last
   * frame (`allowTruncation`) or fails the stream."
   */
  def reference(
    input: String,
    delimiter: String,
    max: Int,
    allowTruncation: Boolean
  ): Result = {
    @tailrec
    def split(
      from: Int,
      frames: Vector[String]
    ): Result = {
      val at = input.indexOf(delimiter, from)
      if (at >= 0) {
        if (at - from > max) Result(frames, TooLong)
        else split(at + delimiter.length, frames :+ input.substring(from, at))
      } else {
        val rest = input.substring(from)
        if (rest.length > max) Result(frames, TooLong)
        else if (rest.isEmpty) Result(frames, Completed)
        else if (allowTruncation) Result(frames :+ rest, Completed)
        else Result(frames, Truncated)
      }
    }
    split(0, Vector.empty)
  }

  /** Every string over `alphabet` of at most `maxLength` characters. */
  def strings(
    alphabet: String,
    maxLength: Int
  ): Iterator[String] =
    (0 to maxLength).iterator.flatMap { length =>
      Iterator.range(0, math.pow(alphabet.length, length).toInt).map { code =>
        val digits = Iterator.iterate(code)(_ / alphabet.length).take(length)
        digits.map(digit => alphabet(digit % alphabet.length)).mkString
      }
    }

  /** Every cut of `s` into non-empty chunks (2^(n-1)), plus one variant with empty chunks. */
  def chunkings(s: String): Iterator[Vector[String]] =
    if (s.isEmpty)
      Iterator(Vector.empty, Vector(""))
    else {
      val cuts = Iterator.range(0, 1 << (s.length - 1)).map { mask =>
        val bounds =
          0 +: (1 until s.length).filter(i => (mask & (1 << (i - 1))) != 0) :+ s.length
        bounds.zip(bounds.tail).map { case (from, until) => s.substring(from, until) }.toVector
      }
      cuts ++ Iterator(Vector("") ++ s.toVector.flatMap(c => Vector(c.toString, "")))
    }

  /** An implementation's result against the model's: the same, a known edge, or unexpected. */
  def classify(
    expected: Result,
    actual: Result,
    delimiter: String,
    max: Int
  ): Verdict = {
    // a frame the model emits, but a split delimiter can push past the limit
    def atLimit(frame: String): Boolean =
      frame.length <= max && frame.length + delimiter.length - 1 > max

    val prefixThenTooLong = actual.end == TooLong && expected.frames.startsWith(actual.frames)
    val missing = expected.frames.drop(actual.frames.size)

    if (actual == expected) Same
    else if (prefixThenTooLong && expected.end == TooLong) FramesDropped
    else if (prefixThenTooLong && missing.exists(atLimit)) ExactLimitEdge
    else Unexpected
  }
}
