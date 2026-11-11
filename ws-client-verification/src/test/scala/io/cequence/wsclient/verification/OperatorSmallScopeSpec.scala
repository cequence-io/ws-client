package io.cequence.wsclient.verification

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.effect.unsafe.implicits.{global => ioRuntime}
import com.typesafe.config.ConfigFactory
import io.cequence.wsclient.stream.AsyncStream
import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.Fs2Interop._
import io.cequence.wsclient.stream.SourceInterop._
import io.cequence.wsclient.stream.ZioInterop._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.jdk.CollectionConverters._
import scala.util.control.{NoStackTrace, NonFatal}

/**
 * A small-scope (bounded-exhaustive) differential of operator pipelines: EVERY pipeline of up
 * to `depth` operators (failures injected at every user function) over a set of small sources,
 * run on all four backends - the neutral interpreter and the Akka, fs2 and ZIO compilers -
 * against a list model written from the scaladoc of `AsyncStream` alone: a stream is its
 * elements, then `Completed` or `Failed`.
 *
 * The random differential specs of the interop modules sample this space; here nothing in the
 * scope is skipped. The neutral interpreter, fs2 and ZIO must match the model exactly; Akka
 * may differ in the one sanctioned way (`FailedFast`), nothing else.
 *
 * The scope is `-Dwsclient.verify.operators.depth` (default 2: about 26,000 pipelines on each
 * backend, 20 s; 3 is the scope the operators were audited with - about 270,000 pipelines on
 * each backend over four of the sources, 2 minutes).
 */
class OperatorSmallScopeSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import OperatorSmallScopeSpec._

  private val depth = sys.props.getOrElse("wsclient.verify.operators.depth", "2").toInt

  // the injected failures are the point: no stage-error logging
  private implicit val system: ActorSystem = ActorSystem(
    "operator-small-scope",
    ConfigFactory.parseString("akka.loglevel = OFF, akka.stdout-loglevel = OFF")
  )
  private implicit val ec: ExecutionContext = ExecutionContext.global

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds)
    super.afterAll()
  }

  private val limit = 5.seconds

  private def endOf(e: Throwable): End = e match {
    case boom: Boom          => Failed(boom.tag)
    case _: TimeoutException => Hang
    case other => Failed(s"UNEXPECTED ${other.getClass.getSimpleName}: ${other.getMessage}")
  }

  private def observed(run: ConcurrentLinkedQueue[Int] => End): Outcome = {
    val seen = new ConcurrentLinkedQueue[Int]()
    val end =
      try run(seen)
      catch { case NonFatal(e) => endOf(e) }
    Outcome(seen.asScala.toVector, end)
  }

  private val backends: Vector[Backend] = Vector(
    Backend(
      "neutral",
      Set(Same),
      stream =>
        observed { seen =>
          Await.result(stream.runForeach { a => seen.add(a); () }, limit)
          Completed
        }
    ),
    // Akka's asynchronous stages fail fast: elements in flight when a failure arrives are
    // dropped (the neutral interpreter, fs2 and ZIO deliver them first)
    Backend(
      "akka",
      Set(Same, FailedFast),
      stream =>
        observed { seen =>
          Await
            .result(stream.toSource.runWith(Sink.foreach[Int] { a => seen.add(a); () }), limit)
          Completed
        }
    ),
    Backend(
      "fs2",
      Set(Same),
      stream =>
        observed { seen =>
          val drained = stream.toFs2[IO].evalMap(a => IO { seen.add(a); () }).compile.drain
          if (drained.unsafeRunTimed(limit).isDefined) Completed else Hang
        }
    ),
    Backend(
      "zio",
      Set(Same),
      stream =>
        observed { seen =>
          val effect = stream.toZStream
            .runForeach(a => zio.ZIO.succeed { seen.add(a); () })
            .timeout(zio.Duration.fromScala(limit))
          zio.Unsafe.unsafe(implicit unsafe => zio.Runtime.default.unsafe.run(effect)) match {
            case zio.Exit.Success(Some(_)) => Completed
            case zio.Exit.Success(None)    => Hang
            case zio.Exit.Failure(cause) =>
              cause.failureOption
                .map(endOf)
                .orElse(cause.dieOption.map(e => Failed(s"DEFECT $e")))
                .getOrElse(Failed("INTERRUPTED"))
          }
        }
    )
  )

  // all four backends go through the same pipelines, once
  private lazy val tallies: Map[String, Tally] =
    differential(depth, sources, operators, backends)

  "Every pipeline of the scope" should {

    backends.foreach { backend =>
      val sanctioned = (backend.allowed - Same).mkString(", ")
      val exception = if (sanctioned.isEmpty) "" else s" (but for $sanctioned)"

      s"run on the ${backend.name} backend as the list model says$exception" in {
        val tally = tallies(backend.name)
        val counts = tally.counts.map { case (verdict, n) => s"$verdict $n" }.mkString(", ")
        info(s"${tally.runs} pipelines of up to $depth operators; $counts")
        withClue(tally.examples.mkString("\n", "\n", "\n")) {
          tally.examples shouldBe empty
        }
        tally.counts.getOrElse(Same, 0) should be > 0
      }
    }

    // the sanctioned difference is real: if Akka stops showing it, drop the allowance
    "still show a failure overtaking the elements an Akka stage holds" in {
      tallies("akka").counts.getOrElse(FailedFast, 0) should be > 0
    }
  }

  "The scope" should {

    "hold every pipeline of up to 2 operators over every source" in {
      val lastOnly = operators.count(_.lastOnly)
      val perSource = 1 + operators.size + (operators.size - lastOnly) * operators.size
      pipelines(2, sources, operators).size shouldBe sources.size * perSource
    }

    def pipelineOf(
      source: String,
      names: String*
    ): (Src, Vector[Op]) =
      (
        sources.find(_.name == source).get,
        names.map(name => operators.find(_.name == name).get).toVector
      )

    "model a pipeline as the operators' list functions, applied in order" in {
      def model(
        source: String,
        names: String*
      ): Outcome = {
        val (src, ops) = pipelineOf(source, names: _*)
        modelOf(src, ops)
      }

      model("emits(1,2,3)", "map(throws@2)", "recover(-1)") shouldBe
        Outcome(Vector(1, -1), Completed)
      model("emits(1,2,3)", "flatMapConcat(i,i+10)", "take(2)") shouldBe
        Outcome(Vector(1, 11), Completed)
      model("iterator(1,2,throws)", "fold(+)", "mapError") shouldBe
        Outcome(Vector.empty, Failed("mapped"))
      model("emits(1,2)++failed", "recoverWith(-1,-2)", "stateful(count at end)") shouldBe
        Outcome(Vector(1, 2, -1, -2, 1004), Completed)
    }

    "take for failing fast only what a failure overtaking the elements a stage holds yields" in {
      def verdict(
        actual: Outcome,
        source: String,
        names: String*
      ): Verdict = {
        val (src, ops) = pipelineOf(source, names: _*)
        classify(modelOf(src, ops), actual, failFastOutcomes(src, ops))
      }
      val evalThenRecover = Seq("evalMap(failed@2)", "flatMapConcat(i,i+10)", "recover(-1)")

      // the model's failure, an element short
      verdict(
        Outcome(Vector(101), Failed("src")),
        "emits(1,2)++failed",
        "evalMap(async)"
      ) shouldBe
        FailedFast
      // the upstream's failure overtakes the inner stream of the element 2 (which fails
      // itself in the model)
      verdict(
        Outcome(Vector(1), Failed("end")),
        "emits(1,2)",
        "stateful(end throws)",
        "flatMapConcat(inner fails@2)"
      ) shouldBe FailedFast
      // what follows the stage sees the stream cut short: a recover, a take that never stops
      verdict(Outcome(Vector(-1), Completed), "emits(1,2,3)", evalThenRecover: _*) shouldBe
        FailedFast
      verdict(
        Outcome(Vector.empty, Failed("eval")),
        "emits(1,2,3)",
        "evalMap(failed@2)",
        "take(1)"
      ) shouldBe FailedFast

      // no stage that holds elements: nothing may be cut short
      verdict(Outcome(Vector(11), Failed("src")), "emits(1,2)++failed", "map(+10)") shouldBe
        Unexpected
      verdict(Outcome(Vector(1), Failed("src")), "emits(1,2)++failed", "recover(-1)") shouldBe
        Unexpected
      // at a stage that holds elements: only the last ones before a failure go, and it stays
      // that failure
      verdict(
        Outcome(Vector(102), Failed("src")),
        "emits(1,2)++failed",
        "evalMap(async)"
      ) shouldBe
        Unexpected
      verdict(Outcome(Vector(101), Completed), "emits(1,2)++failed", "evalMap(async)") shouldBe
        Unexpected
      verdict(Outcome(Vector(101), Hang), "emits(1,2)++failed", "evalMap(async)") shouldBe
        Unexpected
      verdict(
        Outcome(Vector(101), Completed),
        "emits(1,2,3)",
        "evalMap(async)",
        "take(2)"
      ) shouldBe
        Unexpected
    }
  }
}

object OperatorSmallScopeSpec {

  /**
   * The only failure the scope injects; anything else an implementation raises is unexpected.
   */
  final class Boom(val tag: String) extends RuntimeException(tag) with NoStackTrace

  sealed trait End
  case object Completed extends End
  final case class Failed(tag: String) extends End
  // no end within the time limit
  case object Hang extends End

  /** What a run shows: the elements, then how it ended. Also the model's value of a stream. */
  final case class Outcome(
    elements: Vector[Int],
    end: End
  ) {
    override def toString: String = elements.mkString("[", ",", "] ") + end
  }

  final case class Src(
    name: String,
    build: () => AsyncStream[Int],
    model: Outcome
  )

  /**
   * An operator and its list function. A `lastOnly` one is tried only as the last operator; a
   * `failsFast` one is a stage that holds elements on Akka (see `failFastOutcomes`).
   */
  final case class Op(
    name: String,
    build: AsyncStream[Int] => AsyncStream[Int],
    model: Outcome => Outcome,
    lastOnly: Boolean = false,
    failsFast: Boolean = false
  )

  sealed trait Verdict
  case object Same extends Verdict
  // a failure overtook elements in flight (see `failFastOutcomes`)
  case object FailedFast extends Verdict
  case object Unexpected extends Verdict

  final case class Backend(
    name: String,
    allowed: Set[Verdict],
    run: AsyncStream[Int] => Outcome
  )

  /** A run against the model: the same, one of `failFast` (its other outcomes), or neither. */
  def classify(
    expected: Outcome,
    actual: Outcome,
    failFast: => Set[Outcome]
  ): Verdict =
    if (actual == expected) Same
    else if (failFast(actual)) FailedFast
    else Unexpected

  /** The model: the operators' list functions, applied in order to the source's outcome. */
  def modelOf(
    source: Src,
    pipeline: Vector[Op]
  ): Outcome = pipeline.foldLeft(source.model) { case (m, op) => op.model(m) }

  /**
   * Every outcome the sanctioned difference of Akka / Pekko allows a pipeline (the model's own
   * included): a stage that holds elements - `evalMap` (mapAsync: the futures in flight),
   * `flatMapConcat` (the running inner stream), `mapConcat` (the rest of an element's
   * iterable) - fails at once, so a failure overtakes the elements it holds. Such a stage may
   * see a failing input without its last elements, and its failing output may lose its last
   * elements; the stages after it run on that shorter stream, as the model says (a `recover`
   * recovers earlier, a `take` that would have stopped the stream first does not).
   */
  def failFastOutcomes(
    source: Src,
    pipeline: Vector[Op]
  ): Set[Outcome] =
    pipeline.foldLeft(Set(source.model)) { case (inputs, op) =>
      if (op.failsFast) inputs.flatMap(cutShort).map(op.model).flatMap(cutShort)
      else inputs.map(op.model)
    }

  // a failing outcome, and it again without each number of its last elements
  private def cutShort(outcome: Outcome): Set[Outcome] =
    outcome.end match {
      case Failed(_) =>
        (0 to outcome.elements.size)
          .map(n => outcome.copy(elements = outcome.elements.take(n)))
          .toSet
      case Completed | Hang =>
        Set(outcome)
    }

  // ------------------------------------------------------------------ the list model

  // the elements in order, each replaced by the stream it maps to; a failure ends it there
  private def each(m: Outcome)(f: Int => Outcome): Outcome = {
    val out = Vector.newBuilder[Int]
    val elements = m.elements.iterator
    var end = Option.empty[End]
    while (end.isEmpty && elements.hasNext) {
      val inner =
        try f(elements.next())
        catch { case boom: Boom => Outcome(Vector.empty, Failed(boom.tag)) }
      out ++= inner.elements
      if (inner.end != Completed) end = Some(inner.end)
    }
    Outcome(out.result(), end.getOrElse(m.end))
  }

  private def one(i: Int): Outcome = Outcome(Vector(i), Completed)

  private def onCompleted(m: Outcome)(f: Vector[Int] => Outcome): Outcome =
    if (m.end == Completed) f(m.elements) else m

  // a failed stream, replaced by what f makes of (its elements, its failure)
  private def onFailed(m: Outcome)(f: ((Vector[Int], String)) => Outcome): Outcome =
    m.end match {
      case Failed(tag)      => f((m.elements, tag))
      case Completed | Hang => m
    }

  private def firstN(
    m: Outcome,
    n: Int
  ): Outcome =
    if (n <= 0) Outcome(Vector.empty, Completed)
    else if (m.elements.size >= n) Outcome(m.elements.take(n), Completed)
    else m

  // the elements while p holds (p may throw)
  private def whileP(
    m: Outcome,
    inclusive: Boolean
  )(
    p: Int => Boolean
  ): Outcome = {
    val out = Vector.newBuilder[Int]
    val elements = m.elements.iterator
    var end = Option.empty[End]
    while (end.isEmpty && elements.hasNext) {
      val i = elements.next()
      try
        if (p(i)) out += i
        else {
          if (inclusive) out += i
          end = Some(Completed)
        }
      catch { case boom: Boom => end = Some(Failed(boom.tag)) }
    }
    Outcome(out.result(), end.getOrElse(m.end))
  }

  // f, failing at the element 2
  private def at2[A](tag: String)(f: Int => A): Int => A =
    i => if (i == 2) throw new Boom(tag) else f(i)

  private def boom(tag: String): Nothing = throw new Boom(tag)

  // ------------------------------------------------------------------ the scope

  def sources(
    implicit ec: ExecutionContext
  ): Vector[Src] = {
    def failingAfter12: Iterator[Int] =
      Iterator(1, 2) ++ Iterator.continually[Int](boom("src"))
    val failed = Outcome(Vector.empty, Failed("src"))

    Vector(
      Src("emits()", () => AsyncStream[Int](), Outcome(Vector.empty, Completed)),
      Src("emits(1)", () => AsyncStream(1), Outcome(Vector(1), Completed)),
      Src("emits(1,2)", () => AsyncStream(1, 2), Outcome(Vector(1, 2), Completed)),
      Src("emits(1,2,3)", () => AsyncStream(1, 2, 3), Outcome(Vector(1, 2, 3), Completed)),
      Src(
        "iterator(1,2,throws)",
        () => AsyncStream.fromIterator(() => failingAfter12),
        Outcome(Vector(1, 2), Failed("src"))
      ),
      Src(
        "iterator(create throws)",
        () => AsyncStream.fromIterator[Int](() => boom("src")),
        failed
      ),
      Src(
        "emits(1,2)++failed",
        () => AsyncStream(1, 2) ++ AsyncStream.failed(new Boom("src")),
        Outcome(Vector(1, 2), Failed("src"))
      ),
      Src("failed", () => AsyncStream.failed[Int](new Boom("src")), failed),
      Src(
        "future(3)",
        () => AsyncStream.future(Future.successful(3)),
        Outcome(Vector(3), Completed)
      ),
      Src(
        "future(async 3)",
        () => AsyncStream.future(Future(3)),
        Outcome(Vector(3), Completed)
      ),
      Src(
        "future(failed)",
        () => AsyncStream.future[Int](Future.failed(new Boom("src"))),
        failed
      ),
      Src("future(thunk throws)", () => AsyncStream.future[Int](boom("src")), failed),
      Src("defer(thunk throws)", () => AsyncStream.defer[Int](boom("src")), failed),
      Src(
        "futureStream(1,2)",
        () => AsyncStream.futureStream(Future(AsyncStream(1, 2))),
        Outcome(Vector(1, 2), Completed)
      ),
      Src(
        "blockingIterator(1,2,3)",
        () => AsyncStream.fromBlockingIterator(() => Iterator(1, 2, 3)),
        Outcome(Vector(1, 2, 3), Completed)
      ),
      Src(
        "blockingIterator(1,2,throws)",
        () => AsyncStream.fromBlockingIterator(() => failingAfter12),
        Outcome(Vector(1, 2), Failed("src"))
      )
    )
  }

  // the sources a pipeline of more than 2 operators runs over
  private val deepSources =
    Set("emits(1,2,3)", "iterator(1,2,throws)", "emits(1,2)++failed", "future(3)")

  def operators(
    implicit ec: ExecutionContext
  ): Vector[Op] = {
    // the steps of the stateful operators: the state counts the elements
    def counting(
      count: Int,
      i: Int
    ): Step[Int, Int] = Step(count + 1, List(i))

    def stoppingAtSecond(
      count: Int,
      i: Int
    ): Step[Int, Int] = Step(count + 1, List(i), stop = count + 1 >= 2)

    Vector(
      Op("map(+10)", _.map(_ + 10), each(_)(i => one(i + 10))),
      Op(
        "map(throws@2)",
        _.map(at2("map")(identity)),
        each(_)(i => one(at2("map")(identity)(i)))
      ),
      Op(
        "filter(odd)",
        _.filter(_ % 2 != 0),
        m => m.copy(elements = m.elements.filter(_ % 2 != 0))
      ),
      Op(
        "mapConcat(dup)",
        _.mapConcat(i => List(i, i)),
        each(_)(i => Outcome(Vector(i, i), Completed)),
        failsFast = true
      ),
      Op(
        "mapConcat(throws@2)",
        _.mapConcat(at2("mapConcat")(i => List(i, i))),
        each(_)(i => Outcome(at2("mapConcat")(i => Vector(i, i))(i), Completed)),
        failsFast = true
      ),
      Op("take(0)", _.take(0), firstN(_, 0)),
      Op("take(1)", _.take(1), firstN(_, 1)),
      Op("take(2)", _.take(2), firstN(_, 2)),
      Op("drop(1)", _.drop(1), m => m.copy(elements = m.elements.drop(1))),
      Op("takeWhile(<2)", _.takeWhile(_ < 2), whileP(_, inclusive = false)(_ < 2)),
      Op(
        "takeWhile(<2,inclusive)",
        _.takeWhile(_ < 2, inclusive = true),
        whileP(_, inclusive = true)(_ < 2)
      ),
      Op(
        "takeWhile(throws@2)",
        _.takeWhile(at2("takeWhile")(_ => true)),
        whileP(_, inclusive = false)(at2("takeWhile")(_ => true))
      ),
      Op(
        "scan(+)",
        _.scan(0)(_ + _),
        m => m.copy(elements = m.elements.scanLeft(0)(_ + _))
      ),
      Op(
        "fold(+)",
        _.fold(0)(_ + _),
        m =>
          if (m.end == Completed) Outcome(Vector(m.elements.sum), Completed)
          else Outcome(Vector.empty, m.end)
      ),
      Op(
        "zipWithIndex",
        _.zipWithIndex.map { case (a, i) => a * 10 + i.toInt },
        m => m.copy(elements = m.elements.zipWithIndex.map { case (a, i) => a * 10 + i })
      ),
      Op(
        "evalMap(now)",
        _.evalMap(i => Future.successful(i + 100)),
        each(_)(i => one(i + 100)),
        failsFast = true
      ),
      Op(
        "evalMap(async)",
        _.evalMap(i => Future(i + 100)),
        each(_)(i => one(i + 100)),
        failsFast = true
      ),
      Op(
        "evalMap(failed@2)",
        _.evalMap(i => if (i == 2) Future.failed(new Boom("eval")) else Future.successful(i)),
        each(_)(i => one(at2("eval")(identity)(i))),
        failsFast = true
      ),
      Op(
        "evalMap(throws@2)",
        _.evalMap(at2("evalThrow")(i => Future.successful(i))),
        each(_)(i => one(at2("evalThrow")(identity)(i))),
        failsFast = true
      ),
      Op(
        "++(7,8)",
        _ ++ AsyncStream(7, 8),
        onCompleted(_)(es => Outcome(es ++ Vector(7, 8), Completed))
      ),
      Op(
        "++(thunk throws)",
        _.concat[Int](boom("thunk")),
        onCompleted(_)(es => Outcome(es, Failed("thunk")))
      ),
      Op(
        "++failed",
        _ ++ AsyncStream.failed(new Boom("tail")),
        onCompleted(_)(es => Outcome(es, Failed("tail")))
      ),
      Op("(7)++_", s => AsyncStream(7) ++ s, m => m.copy(elements = 7 +: m.elements)),
      Op(
        "flatMapConcat(i,i+10)",
        _.flatMapConcat(i => AsyncStream(i, i + 10)),
        each(_)(i => Outcome(Vector(i, i + 10), Completed)),
        failsFast = true
      ),
      Op(
        "flatMapConcat(throws@2)",
        _.flatMapConcat(at2("fmc")(i => AsyncStream(i))),
        each(_)(i => one(at2("fmc")(identity)(i))),
        failsFast = true
      ),
      Op(
        "flatMapConcat(inner fails@2)",
        _.flatMapConcat { i =>
          if (i == 2) AsyncStream(2) ++ AsyncStream.failed(new Boom("inner"))
          else AsyncStream(i)
        },
        each(_)(i => if (i == 2) Outcome(Vector(2), Failed("inner")) else one(i)),
        failsFast = true
      ),
      Op(
        "recover(-1)",
        _.recover { case _: Boom => -1 },
        onFailed(_) { case (es, _) => Outcome(es :+ -1, Completed) }
      ),
      Op(
        "recover(src only)",
        _.recover { case b: Boom if b.tag == "src" => -1 },
        onFailed(_) { case (es, tag) =>
          if (tag == "src") Outcome(es :+ -1, Completed) else Outcome(es, Failed(tag))
        }
      ),
      Op(
        "recoverWith(-1,-2)",
        _.recoverWith { case _: Boom => AsyncStream(-1, -2) },
        onFailed(_) { case (es, _) => Outcome(es ++ Vector(-1, -2), Completed) }
      ),
      Op(
        "recoverWith(pf throws)",
        _.recoverWith { case _: Boom => boom("pf") },
        onFailed(_) { case (es, _) => Outcome(es, Failed("pf")) }
      ),
      Op(
        "recoverWith(failed)",
        _.recoverWith { case _: Boom => AsyncStream.failed(new Boom("again")) },
        onFailed(_) { case (es, _) => Outcome(es, Failed("again")) }
      ),
      Op(
        "mapError",
        _.mapError { case _: Boom => new Boom("mapped") },
        onFailed(_) { case (es, _) => Outcome(es, Failed("mapped")) }
      ),
      Op(
        "mapError(pf throws)",
        _.mapError { case _: Boom => boom("pf") },
        onFailed(_) { case (es, _) => Outcome(es, Failed("pf")) }
      ),
      Op(
        "stateful(count at end)",
        _.stateful(() => 0)(counting, c => List(1000 + c)),
        onCompleted(_)(es => Outcome(es :+ (1000 + es.size), Completed))
      ),
      Op(
        "stateful(stop@2nd)",
        _.stateful(() => 0)(stoppingAtSecond, c => List(1000 + c)),
        m =>
          if (m.elements.size >= 2) Outcome(m.elements.take(2), Completed)
          else onCompleted(m)(es => Outcome(es :+ (1000 + es.size), Completed))
      ),
      Op(
        "stateful(end throws)",
        _.stateful(() => 0)(counting, _ => boom("end")),
        onCompleted(_)(es => Outcome(es, Failed("end")))
      ),
      Op(
        "statefulMapConcat(i*n)",
        _.statefulMapConcat { () =>
          var n = 0
          i => {
            n += 1
            List(i * n)
          }
        },
        m => m.copy(elements = m.elements.zipWithIndex.map { case (i, k) => i * (k + 1) })
      ),
      Op("onTerminate(noop)", _.onTerminate(_ => ()), identity),
      Op("defer", s => AsyncStream.defer(s), identity),
      // a throwing hook fails a completed run - whatever follows it would see that failure
      // only on some backends' schedules, so it is tried in the last position only
      Op(
        "onTerminate(hook throws)",
        _.onTerminate(_ => boom("hook")),
        onCompleted(_)(es => Outcome(es, Failed("hook"))),
        lastOnly = true
      )
    )
  }

  /**
   * Every (source, operators) of the scope: up to 2 operators over every source, more over 4.
   */
  def pipelines(
    depth: Int,
    sources: Vector[Src],
    operators: Vector[Op]
  ): Iterator[(Src, Vector[Op])] = {
    def ofLength(n: Int): Iterator[Vector[Op]] =
      if (n == 0) Iterator(Vector.empty)
      else ofLength(n - 1).flatMap(prefix => operators.iterator.map(prefix :+ _))

    for {
      length <- (0 to depth).iterator
      source <- sources.iterator if length <= 2 || deepSources(source.name)
      pipeline <- ofLength(length) if pipeline.dropRight(1).forall(!_.lastOnly)
    } yield (source, pipeline)
  }

  // ------------------------------------------------------------------ the differential

  /** One backend's result: runs per verdict, and the minimal pipelines it must not show. */
  final class Tally {
    val counts: mutable.Map[Verdict, Int] = mutable.LinkedHashMap.empty[Verdict, Int]
    val examples: mutable.Buffer[String] = mutable.ListBuffer.empty[String]
    var runs = 0
    // the pipelines with a verdict the backend is not allowed
    private[OperatorSmallScopeSpec] val bad = mutable.HashSet.empty[String]
  }

  private val MaxExamples = 25

  def differential(
    depth: Int,
    sources: Vector[Src],
    operators: Vector[Op],
    backends: Vector[Backend]
  ): Map[String, Tally] = {
    val tallies = backends.map(_.name -> new Tally).toMap

    pipelines(depth, sources, operators).foreach { case (source, pipeline) =>
      val expected = modelOf(source, pipeline)
      val names = pipeline.map(_.name)
      def key(ops: Vector[String]): String = (source.name +: ops).mkString(" -> ")

      backends.foreach { backend =>
        val tally = tallies(backend.name)
        // a fresh stream per backend: the runs stay independent
        val stream = pipeline.foldLeft(source.build()) { case (s, op) => op.build(s) }
        val actual = backend.run(stream)
        val verdict = classify(expected, actual, failFastOutcomes(source, pipeline))
        tally.runs += 1
        tally.counts(verdict) = tally.counts.getOrElse(verdict, 0) + 1

        if (!backend.allowed(verdict)) {
          // reported only when minimal: neither the pipeline without its last operator nor
          // the one without its first already differs
          val minimal =
            names.isEmpty || (!tally.bad(key(names.init)) && !tally.bad(key(names.tail)))
          tally.bad += key(names)
          if (minimal && tally.examples.size < MaxExamples)
            tally.examples += s"${key(names)}: expected $expected, got $actual ($verdict)"
        }
      }
    }
    tallies
  }
}
