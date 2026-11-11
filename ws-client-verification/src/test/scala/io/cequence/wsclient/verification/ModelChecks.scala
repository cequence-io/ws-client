package io.cequence.wsclient.verification

import org.jetbrains.lincheck.{Lincheck, LincheckSettings}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/**
 * The model checks of the stream layer's concurrent classes, as one main (the `modelCheck` sbt
 * command): every scenario of every suite runs under the interleavings of its threads that
 * Lincheck enumerates within the invocation budget - switch points at shared reads and writes,
 * monitor enter / exit, wait / notify, park / unpark. A deadlock, a hang or a failed `ensure`
 * is reported with the interleaving that produced it.
 *
 * A plain main, not a test framework: the checker runs a scenario in instrumented code, where
 * a framework's own machinery only adds switch points. The exit code is the number of failed
 * scenarios (capped at 1).
 *
 * Knobs (system properties):
 *   - `wsclient.verify.modelcheck.invocations` - interleavings per scenario (default 1000: the
 *     whole run takes about 7 minutes)
 *   - `wsclient.verify.modelcheck.only` - only the scenarios whose name contains this text
 *   - `wsclient.verify.modelcheck.traceLines` - lines of a failure's interleaving to print
 *     (default 60, each cut to the first thread columns); the whole interleaving is written to
 *     `target/model-check/<scenario>.trace`
 */
object ModelChecks {

  private val suites: List[ModelCheckSuite] =
    List(TransformPublisherModelCheck, BridgeModelCheck, HubModelCheck, ZioCursorModelCheck)

  private def knob(
    name: String,
    default: String
  ): String = sys.props.getOrElse(s"wsclient.verify.modelcheck.$name", default)

  def main(args: Array[String]): Unit = {
    val invocations = knob("invocations", "1000").toInt
    val traceLines = knob("traceLines", "60").toInt
    val only = sys.props.get("wsclient.verify.modelcheck.only")
    val selected = suites.flatMap(_.scenarios).filter(s => only.forall(s.name.contains))

    val failed = selected.count { scenario =>
      val start = System.nanoTime
      val failure =
        try {
          check(scenario, invocations)
          None
        } catch { case NonFatal(e) => Some(e) }
      val seconds = (System.nanoTime - start) / 1e9

      failure match {
        case None =>
          println(f"PASS  ($seconds%5.1f s)  ${scenario.name}")
        case Some(e) =>
          println(f"FAIL  ($seconds%5.1f s)  ${scenario.name}")
          val message = String.valueOf(e.getMessage)
          val lines = message.linesIterator.filter(_.trim.nonEmpty)
          println(s"      ${e.getClass.getSimpleName}")
          lines.take(traceLines).foreach(line => println("      " + line.take(260)))
          println(s"      the whole interleaving: ${writeTrace(scenario.name, message)}")
      }
      failure.isDefined
    }

    println(s"${selected.size} scenarios, $invocations interleavings each; failed: $failed")
    // a selection that matches nothing is a typo, not a pass
    sys.exit(if (failed == 0 && selected.nonEmpty) 0 else 1)
  }

  private def check(
    scenario: Scenario,
    invocations: Int
  ): Unit =
    scenario.loopBound match {
      case None =>
        Lincheck.runConcurrentTest(invocations, () => scenario.body())

      case Some(loopBound) =>
        // the public entry point takes no settings: this is the one it delegates to (Kotlin
        // `internal`, hence the mangled name) - to be re-checked on a Lincheck upgrade
        val defaults = new LincheckSettings()
        val settings = defaults.copy(
          defaults.getAnalyzeStdLib,
          defaults.getLoopIterationsBeforeThreadSwitch,
          loopBound,
          defaults.getRecursionBound
        )
        Lincheck.`runConcurrentTestInternal$lincheck`(
          invocations,
          settings,
          () => scenario.body()
        )
    }

  // the forked run's working directory is the module's
  private def writeTrace(
    scenario: String,
    message: String
  ): Path = {
    val name = scenario.replaceAll("[^A-Za-z0-9]+", "-").take(80)
    val file = Paths.get("target", "model-check", s"$name.trace")
    Files.createDirectories(file.getParent)
    Files.write(file, message.getBytes(StandardCharsets.UTF_8)).toAbsolutePath
  }
}

/**
 * One model-checked scenario: `body` is ONE execution - it starts and joins its own threads.
 */
final class Scenario(
  val name: String,
  val loopBound: Option[Int],
  val body: () => Unit
)

/**
 * The scenarios of one class under check. A scenario creates everything it needs (the checked
 * object, its threads, its execution context) inside its body: the checker controls only what
 * is created there.
 *
 * @param loopBound
 *   the iterations after which the checker takes a loop for a livelock, if not its default
 *   (50) - for code that legitimately loops longer, such as an effect interpreter
 */
abstract class ModelCheckSuite(
  subject: String,
  loopBound: Option[Int] = None
) {

  private val registered = ListBuffer.empty[Scenario]

  final def scenarios: List[Scenario] = registered.toList

  protected final def scenario(name: String)(body: => Unit): Unit =
    registered += new Scenario(s"$subject: $name", loopBound, () => body)

  // a plain check: no macro, no resource bundle inside the instrumented code
  protected final def ensure(
    condition: Boolean,
    message: => String
  ): Unit =
    if (!condition) throw new AssertionError(message)

  protected final def thread(body: => Unit): Thread = {
    val started = new Thread(() => body)
    started.start()
    started
  }
}
