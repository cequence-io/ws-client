package io.cequence.wsclient.verification

import java.net.URLEncoder
import java.util.regex.Pattern
import scala.util.Random

/**
 * The Java / Scala library calls the extracted Lean code makes (`formal/lean/WsClientProofs/
 * Scala.lean`, `Java.lean`, `JavaString.lean`, `WireFormat/JdkUrlEncoder.lean`) against the
 * JVM itself, function by function: the trusted base of the extraction (`ProductionExtract`).
 * The Lean side works on texts of Unicode scalar values, so an index is compared in code
 * points (the JVM counts UTF-16 units).
 *
 * The texts are drawn from a small alphabet of the characters these functions treat specially
 * (white space and controls up to U+0020 for `trim`, the SSE line breaks, a character of every
 * UTF-8 width, a supplementary one among them), so needles occur in their haystacks.
 */
object LibConformance extends LeanConformance.Target {

  val name = "lib"

  private val alphabet: Vector[String] =
    Vector(
      "a",
      "b",
      ":",
      "d",
      " ",
      "\t",
      "\n",
      "\r",
      "\u0000",
      "\u0001",
      "\u000b",
      "\u001f",
      "%",
      "+",
      "*",
      "~",
      "é",
      "€",
      "中",
      "😀"
    )

  // characters a `char` argument can be (a Java char is one UTF-16 unit)
  private val bmp: Vector[String] = alphabet.filter(_.length == 1)

  private def text(
    random: Random,
    maxLength: Int
  ): String =
    Seq.fill(random.nextInt(maxLength + 1))(alphabet(random.nextInt(alphabet.size))).mkString

  // a text that is a piece of `haystack` (so that it occurs) or a fresh one
  private def needle(
    random: Random,
    haystack: String
  ): String =
    if (haystack.nonEmpty && random.nextBoolean()) {
      val points = haystack.codePoints.toArray
      val from = random.nextInt(points.length)
      val to = from + random.nextInt(math.min(3, points.length - from) + 1)
      new String(points, from, to - from)
    } else text(random, 3)

  // the index in code points of a UTF-16 index (-1 stays -1)
  private def points(
    s: String,
    index: Int
  ): Int = if (index < 0) index else s.codePointCount(0, index)

  private def hex(s: String): String = Texts.hex(s)

  private val Lines = Pattern.compile("\r\n|\r|\n")

  def generate(random: Random): (String, String) = {
    val s = text(random, 10)
    random.nextInt(20) match {
      case 0 => (s"lib trim ${hex(s)}", hex(s.trim))
      case 1 =>
        val p = needle(random, s)
        (s"lib startsWith ${hex(s)} ${hex(p)}", s.startsWith(p).toString)
      case 2 =>
        val p = needle(random, s)
        (s"lib stripPrefix ${hex(s)} ${hex(p)}", hex(s.stripPrefix(p)))
      case 3 =>
        val p = needle(random, s)
        (s"lib stripSuffix ${hex(s)} ${hex(p)}", hex(s.stripSuffix(p)))
      case 4 =>
        val t = needle(random, s)
        (s"lib containsText ${hex(s)} ${hex(t)}", s.contains(t).toString)
      case 5 =>
        val t = needle(random, s)
        (s"lib indexOfText ${hex(s)} ${hex(t)}", points(s, s.indexOf(t)).toString)
      case 6 =>
        val c = bmp(random.nextInt(bmp.size))
        (
          s"lib indexOfChar ${hex(s)} ${hex(c)}",
          points(s, s.indexOf(c.charAt(0).toInt)).toString
        )
      case 7 =>
        val k = random.nextInt(s.codePointCount(0, s.length) + 1)
        (
          s"lib substring ${hex(s)} $k",
          hex(s.substring(s.offsetByCodePoints(0, k)))
        )
      case 8 =>
        val t = needle(random, s)
        val target = if (t.isEmpty) "a" else t
        val replacement = text(random, 3)
        (
          s"lib replace ${hex(s)} ${hex(target)} ${hex(replacement)}",
          hex(s.replace(target, replacement))
        )
      case 9 =>
        // CR, LF and CRLF mixed in
        val mixed = Seq
          .fill(random.nextInt(8)) {
            Vector("a", "\r", "\n", "\r\n", "b", ":", "")(random.nextInt(7))
          }
          .mkString
        (
          s"lib splitLines ${hex(mixed)}",
          Lines.split(mixed, -1).map(hex).mkString(" ") match {
            case "" => "."
            case x  => x
          }
        )
      case 10 =>
        (s"lib urlEncode ${hex(s)}", hex(URLEncoder.encode(s, "UTF-8")))
      case 11 =>
        val sep = text(random, 2)
        val parts = Seq.fill(random.nextInt(4))(text(random, 3))
        (
          s"lib mkString ${hex(sep)} ${parts.size} ${parts.map(hex).mkString(" ")}".trim,
          hex(parts.mkString(sep))
        )
      // ---- the Java failures of the CHECKED extraction (`Java.*Checked`, `Scala.int32?`)
      case 12 =>
        val a = bytes(random)
        val i = random.nextInt(a.length + 4) - 2
        (s"lib readChecked ${hexOf(a)} $i", outcome(a(i).toInt & 0xff).toString)
      case 13 =>
        val a = bytes(random)
        val from = random.nextInt(a.length + 5) - 2
        val to = random.nextInt(a.length + 7) - 2
        (
          s"lib copyOfRangeChecked ${hexOf(a)} $from $to",
          bytesOutcome(java.util.Arrays.copyOfRange(a, from, to))
        )
      case 14 =>
        val a = bytes(random)
        val n = random.nextInt(a.length + 6) - 2
        (s"lib copyOfChecked ${hexOf(a)} $n", bytesOutcome(java.util.Arrays.copyOf(a, n)))
      case 15 =>
        val n = random.nextInt(8) - 2
        (s"lib newArrayChecked $n", bytesOutcome(new Array[Byte](n)))
      case 16 =>
        val src = bytes(random)
        val dst = bytes(random)
        val sp = random.nextInt(src.length + 4) - 2
        val dp = random.nextInt(dst.length + 4) - 2
        val len = random.nextInt(8) - 2
        (
          s"lib arraycopyChecked ${hexOf(src)} $sp ${hexOf(dst)} $dp $len",
          bytesOutcome {
            val target = dst.clone()
            System.arraycopy(src, sp, target, dp, len)
            target
          }
        )
      case 17 =>
        val a = bytes(random)
        val b = bytes(random)
        (s"lib concatChecked ${hexOf(a)} ${hexOf(b)}", bytesOutcome(a ++ b))
      case 18 =>
        // int arithmetic near the boundaries (Java wraps; the checked extraction fails)
        val x = edge(random)
        val y = edge(random)
        val (op, run) = random.nextInt(3) match {
          case 0 => ("add", () => Math.addExact(x, y))
          case 1 => ("sub", () => Math.subtractExact(x, y))
          case _ => ("mul", () => Math.multiplyExact(x, y))
        }
        (s"lib int32 $op $x $y", outcome(run()))
      case _ =>
        val x = edgeLong(random)
        val y = edgeLong(random)
        val (op, run) = random.nextInt(3) match {
          case 0 => ("add", () => Math.addExact(x, y))
          case 1 => ("sub", () => Math.subtractExact(x, y))
          case _ => ("mul", () => Math.multiplyExact(x, y))
        }
        (s"lib int64 $op $x $y", outcome(run()))
    }
  }

  // what a Java call returns, or the name of what it throws (the failures the checked
  // extraction models: `Failure.indexOutOfBounds` / `illegalArgument` / `negativeSize` /
  // `intOverflow` - `longOverflow` for the long case)
  private def outcome[A](run: => A): String =
    try run.toString
    catch {
      case _: ArrayIndexOutOfBoundsException | _: IndexOutOfBoundsException =>
        "indexOutOfBounds"
      case _: IllegalArgumentException   => "illegalArgument"
      case _: NegativeArraySizeException => "negativeSize"
      case _: ArithmeticException        => "overflow"
    }

  private def bytesOutcome(run: => Array[Byte]): String =
    try hexOf(run)
    catch {
      case _: ArrayIndexOutOfBoundsException | _: IndexOutOfBoundsException =>
        "indexOutOfBounds"
      case _: IllegalArgumentException   => "illegalArgument"
      case _: NegativeArraySizeException => "negativeSize"
    }

  private def hexOf(a: Array[Byte]): String = LeanConformance.hex(a)

  private def bytes(random: Random): Array[Byte] =
    Array.fill(random.nextInt(6))((random.nextInt(256) - 128).toByte)

  private def edge(random: Random): Int =
    random.nextInt(4) match {
      case 0 =>
        Vector(
          0,
          1,
          -1,
          2,
          Int.MaxValue,
          Int.MinValue,
          Int.MaxValue - 1,
          Int.MinValue + 1,
          46340,
          46341,
          65536,
          -65536,
          1 << 30
        )(random.nextInt(13))
      case 1 => random.nextInt(11) - 5
      case _ => random.nextInt()
    }

  private def edgeLong(random: Random): Long =
    random.nextInt(4) match {
      case 0 =>
        Vector(
          0L,
          1L,
          -1L,
          Long.MaxValue,
          Long.MinValue,
          Long.MaxValue - 1,
          Long.MinValue + 1,
          3037000499L,
          3037000500L,
          1L << 32,
          -(1L << 32),
          1L << 62
        )(random.nextInt(12))
      case 1 => random.nextInt(11).toLong - 5
      case 2 => random.nextInt().toLong
      case _ => random.nextLong()
    }
}
