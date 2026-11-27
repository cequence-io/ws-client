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
    random.nextInt(12) match {
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
      case _ =>
        val sep = text(random, 2)
        val parts = Seq.fill(random.nextInt(4))(text(random, 3))
        (
          s"lib mkString ${hex(sep)} ${parts.size} ${parts.map(hex).mkString(" ")}".trim,
          hex(parts.mkString(sep))
        )
    }
  }
}
