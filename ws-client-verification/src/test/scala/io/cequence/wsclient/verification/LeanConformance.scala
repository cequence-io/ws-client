package io.cequence.wsclient.verification

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.Random

/**
 * The conformance check between the Scala code and its Lean models (`formal/lean`; the
 * `leanConformance` sbt command). The Lean proofs are about the models - transliterations of
 * the Scala code; this check ties each model to its Scala original: every case is run through
 * both, and the outputs must be identical. It reaches what the small-scope specs cannot (long
 * inputs, the framers' buffer compaction and growth beyond 256 bytes, long delimiters), but it
 * samples - the proofs carry the "every input".
 *
 * A plain main (like `ModelChecks`): it runs the Lean executable
 * `formal/lean/.lake/build/bin/conformance` (`lake build` there first) as a child process, one
 * case per line. The exit code is the number of targets with a mismatch (capped at 1).
 *
 * Knobs (system properties):
 *   - `wsclient.verify.lean.exe` - the Lean executable (default: found from the working
 *     directory up)
 *   - `wsclient.verify.lean.cases` - cases per target (default 20,000)
 *   - `wsclient.verify.lean.seed` - the random seed (default 1)
 *   - `wsclient.verify.lean.only` - only the targets whose name contains this text
 */
object LeanConformance {

  /**
   * One kind of case: generates cases, each with the Scala code's output in the Lean format.
   */
  trait Target {
    def name: String

    /** The line for the Lean executable, and the expected output line. */
    def generate(random: Random): (String, String)

    /**
     * Whether the Lean output is the expected one - the same line, unless a target says so.
     */
    def accepts(
      expected: String,
      actual: String
    ): Boolean = expected == actual
  }

  private val targets: List[Target] =
    List(FramingConformance, WireFormatConformance, MultipartConformance, SseFrameConformance)

  private def knob(
    name: String,
    default: String
  ): String = sys.props.getOrElse(s"wsclient.verify.lean.$name", default)

  def main(args: Array[String]): Unit = {
    val exe = sys.props.get("wsclient.verify.lean.exe").map(Paths.get(_)).getOrElse(locate())
    val count = knob("cases", "20000").toInt
    val seed = knob("seed", "1").toLong
    val only = sys.props.get("wsclient.verify.lean.only")
    val selected = targets.filter(t => only.forall(t.name.contains))

    val failed = selected.count { target =>
      val start = System.nanoTime
      val random = new Random(seed)
      val cases = Vector.fill(count)(target.generate(random))
      val mismatches = check(exe, cases, target)
      val seconds = (System.nanoTime - start) / 1e9
      if (mismatches.isEmpty)
        println(f"PASS  ($seconds%5.1f s)  ${target.name}: $count cases identical")
      else {
        println(
          f"FAIL  ($seconds%5.1f s)  ${target.name}: ${mismatches.size} of $count cases differ"
        )
        mismatches.take(5).foreach { case (line, expected, actual) =>
          val at = expected.zip(actual).indexWhere { case (a, b) => a != b } match {
            case -1 => math.min(expected.length, actual.length)
            case i  => i
          }
          println(s"  case:  ${cut(line, 0)}")
          println(s"  Scala: ${cut(expected, at)}")
          println(s"  Lean:  ${cut(actual, at)}")
        }
      }
      mismatches.nonEmpty
    }
    sys.exit(math.min(failed, 1))
  }

  // feeds the cases to the Lean executable from a thread of its own (the pipes are bounded)
  // and compares its output lines in order
  private def check(
    exe: Path,
    cases: Vector[(String, String)],
    target: Target
  ): Vector[(String, String, String)] = {
    val process = new ProcessBuilder(exe.toString).redirectErrorStream(true).start()
    val writer = new Thread(() => {
      val out =
        new BufferedWriter(
          new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
        )
      try cases.foreach { case (line, _) => out.write(line); out.write('\n') }
      finally out.close()
    })
    writer.start()
    val in = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8)
    )
    val mismatches = Vector.newBuilder[(String, String, String)]
    try
      cases.foreach { case (line, expected) =>
        val actual = Option(in.readLine()).getOrElse("<no output: the Lean executable ended>")
        if (!target.accepts(expected, actual)) mismatches += ((line, expected, actual))
      }
    finally {
      in.close()
      writer.join()
      process.waitFor()
    }
    mismatches.result()
  }

  private def locate(): Path = {
    val relative = Paths.get("formal", "lean", ".lake", "build", "bin", "conformance")
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.isExecutable(_))
      .getOrElse(
        sys.error(
          s"$relative not found from the working directory up - run `lake build` in formal/lean"
        )
      )
  }

  // at most 300 characters of a line, around the first difference
  private def cut(
    line: String,
    at: Int
  ): String = {
    val from = math.max(0, at - 150)
    (if (from > 0) "..." else "") + line.slice(from, from + 300) +
      (if (line.length > from + 300) s"... (${line.length} chars)" else "")
  }

  /** Lower-case hex, `.` for empty - the Lean executable's encoding of byte strings. */
  def hex(bytes: Array[Byte]): String =
    if (bytes.isEmpty) "." else bytes.map(b => f"${b & 0xff}%02x").mkString
}
