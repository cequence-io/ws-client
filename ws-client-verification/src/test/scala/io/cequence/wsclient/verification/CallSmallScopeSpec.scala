package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.ZioInterop._
import io.cequence.wsclient.stream.{AsyncCall, AsyncStream, Termination}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.stream.ZStream
import zio.{Runtime, Unsafe, ZIO}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Success}

/**
 * A small-scope (bounded-exhaustive) differential of `AsyncCall` plans: EVERY plan of up to
 * `depth` operators over a set of leaves - values, failures, throwing thunks, `Future` leaves,
 * native ZIO leaves (a defect included), `sleep`, `traverse` - with a throw injected at every
 * kind of user function (`map`, `flatMap`, `zip`'s argument, `mapError` / `recover` /
 * `transform` / `transformWith` functions, `traverse`'s, the hooks; not a `timeout`'s error
 * thunk - a timeout never fires here), run on both interpreters (the `Future` one and the ZIO
 * compiler) against a model written from the scaladoc of `AsyncCall` alone: a call is its
 * outcome - a value or a failure - and the terminations its hooks saw, in order.
 *
 * Both interpreters must match the model exactly; there is no sanctioned difference (on ZIO a
 * native leaf's defect that no operator handles stays a defect - observed as the failure it
 * carries).
 *
 * The scope is `-Dwsclient.verify.calls.depth` (default 3: 698,275 plans per interpreter, 30
 * seconds).
 */
class CallSmallScopeSpec extends AnyWordSpec with Matchers {

  import CallSmallScopeSpec._

  private val depth = sys.props.getOrElse("wsclient.verify.calls.depth", "3").toInt

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val limit = 10.seconds

  private def outcomeOf(result: Either[Throwable, Int]): Outcome = result match {
    case Right(value)     => Value(value)
    case Left(boom: Boom) => Failed(boom.tag)
    case Left(other) =>
      Failed(s"UNEXPECTED ${other.getClass.getSimpleName}: ${other.getMessage}")
  }

  private def observed(run: Log => Either[Throwable, Int]): Observed = {
    val log = new Log
    val outcome =
      try outcomeOf(run(log))
      catch { case NonFatal(e) => outcomeOf(Left(e)) }
    Observed(outcome, log.asScala.toVector)
  }

  private val interpreters: Vector[(String, Plan => Observed)] = Vector(
    "Future" -> (plan =>
      observed { log =>
        Await.ready(plan.build(log).toFuture, limit).value.get match {
          case Success(value) => Right(value)
          case Failure(e)     => Left(e)
        }
      }
    ),
    // the run's exit: a failure, or a native leaf's defect nothing handled (still a defect on
    // ZIO, the same exception) - both are the call's failure
    "ZIO" -> (plan =>
      observed { log =>
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe
            .run(plan.build(log).toZIO)
            .foldExit(cause => Left(cause.squash), value => Right(value))
        }
      }
    )
  )

  private def plans(maxDepth: Int): Iterator[Plan] =
    for {
      leaf <- leaves.iterator
      ops <- (0 to maxDepth).iterator.flatMap(sequences)
    } yield Plan(leaf, ops)

  private def sequences(length: Int): Iterator[List[Op]] =
    if (length == 0) Iterator.single(Nil)
    else for { op <- operators.iterator; rest <- sequences(length - 1) } yield op :: rest

  "every call plan in the scope" should {

    interpreters.foreach { case (name, run) =>
      s"give the model's outcome and hook calls on the $name interpreter" in {
        var count = 0
        val differences = Vector.newBuilder[String]
        var found = 0
        plans(depth).foreach { plan =>
          count += 1
          val expected = plan.model
          val actual = run(plan)
          if (actual != expected && found < 20) {
            found += 1
            differences += s"${plan.describe}\n      expected $expected\n      actual   $actual"
          }
        }
        info(s"$count plans (depth $depth)")
        differences.result() shouldBe empty
      }
    }
  }
}

object CallSmallScopeSpec {

  private implicit val runtime: Runtime[Any] = Runtime.default

  type Log = ConcurrentLinkedQueue[String]

  final class Boom(val tag: String) extends RuntimeException(tag) with NoStackTrace

  sealed trait Outcome
  final case class Value(value: Int) extends Outcome
  final case class Failed(tag: String) extends Outcome

  /** What a run shows: its outcome, and what its hooks saw, in order. */
  final case class Observed(
    outcome: Outcome,
    hooks: Vector[String]
  )

  final case class Leaf(
    name: String,
    build: () => AsyncCall[Int],
    model: Outcome
  )

  /** An operator: on a call (with the run's hook log), and on the model. */
  final case class Op(
    name: String,
    build: (AsyncCall[Int], Log) => AsyncCall[Int],
    model: Observed => Observed
  )

  final case class Plan(
    leaf: Leaf,
    ops: List[Op]
  ) {
    def build(log: Log): AsyncCall[Int] =
      ops.foldLeft(leaf.build())(
        (
          call,
          op
        ) => op.build(call, log)
      )

    def model: Observed =
      ops.foldLeft(Observed(leaf.model, Vector.empty))(
        (
          observed,
          op
        ) => op.model(observed)
      )

    def describe: String = (leaf.name :: ops.map(_.name)).mkString(" -> ")
  }

  private def boom(tag: String): Boom = new Boom(tag)

  val leaves: Vector[Leaf] = Vector(
    Leaf("successful(1)", () => AsyncCall.successful(1), Value(1)),
    Leaf("failed(L)", () => AsyncCall.failed(boom("L")), Failed("L")),
    Leaf("fromTry(Success(2))", () => AsyncCall.fromTry(Success(2)), Value(2)),
    Leaf("defer(successful(3))", () => AsyncCall.defer(AsyncCall.successful(3)), Value(3)),
    Leaf("defer(throw)", () => AsyncCall.defer[Int](throw boom("defer")), Failed("defer")),
    Leaf("future(4)", () => AsyncCall.future(Future.successful(4)), Value(4)),
    Leaf(
      "future(failed)",
      () => AsyncCall.future(Future.failed[Int](boom("future"))),
      Failed("future")
    ),
    Leaf("future(throw)", () => AsyncCall.future[Int](throw boom("thunk")), Failed("thunk")),
    Leaf("zio(5)", () => ZIO.succeed(5).toAsyncCall, Value(5)),
    Leaf("zio(fail)", () => ZIO.fail(boom("zio")).toAsyncCall, Failed("zio")),
    // a native leaf's defect counts as a failure
    Leaf("zio(die)", () => ZIO.die(boom("die")).toAsyncCall, Failed("die")),
    Leaf("sleep.map(6)", () => AsyncCall.sleep(Duration.Zero).map(_ => 6), Value(6)),
    Leaf(
      "traverse(1, 2, 3).sum",
      () => AsyncCall.traverse(List(1, 2, 3), 2)(n => AsyncCall.successful(n)).map(_.sum),
      Value(6)
    ),
    Leaf(
      "traverse(throwing f)",
      () =>
        AsyncCall
          .traverse(List(1, 2, 3), 2)(n =>
            if (n == 3) throw boom("traverseThrow") else AsyncCall.successful(n)
          )
          .map(_.sum),
      Failed("traverseThrow")
    ),
    Leaf(
      "traverse(failing item)",
      () =>
        AsyncCall
          .traverse(List(1, 2, 3), 1)(n =>
            if (n == 2) AsyncCall.failed[Int](boom("item")) else AsyncCall.successful(n)
          )
          .map(_.sum),
      Failed("item")
    ),
    // a race: the call that never ends loses (on ZIO it is interrupted, on the Future
    // interpreter abandoned or cancelled) - so the outcome is the other's, on both
    Leaf(
      "firstCompletedOf(never, successful(7))",
      () =>
        AsyncCall.firstCompletedOf(
          Seq(AsyncCall.future(Promise[Int]().future), AsyncCall.successful(7))
        ),
      Value(7)
    ),
    Leaf(
      "firstCompletedOf(never, failed(R))",
      () =>
        AsyncCall.firstCompletedOf(
          Seq(AsyncCall.future(Promise[Int]().future), AsyncCall.failed[Int](boom("R")))
        ),
      Failed("R")
    ),
    Leaf(
      "firstCompletedOf(never, defer(throw))",
      () =>
        AsyncCall.firstCompletedOf(
          Seq(
            AsyncCall.future(Promise[Int]().future),
            AsyncCall.defer[Int](throw boom("raceThrow"))
          )
        ),
      Failed("raceThrow")
    ),
    Leaf(
      "firstCompletedOf(zio never, zio(8))",
      () =>
        AsyncCall.firstCompletedOf(
          Seq((ZIO.never: zio.Task[Int]).toAsyncCall, ZIO.succeed(8).toAsyncCall)
        ),
      Value(8)
    ),
    // the fold of a stream as a call
    Leaf(
      "stream.runFoldCall(sum)",
      () => AsyncStream(1, 2, 3).runFoldCall(0)(_ + _),
      Value(6)
    ),
    Leaf(
      "stream.runFoldCall(throwing f)",
      () =>
        AsyncStream(1, 2, 3).runFoldCall(0)(
          (
            sum,
            a
          ) => if (a == 2) throw boom("fold") else sum + a
        ),
      Failed("fold")
    ),
    Leaf(
      "failed stream.runFoldCall",
      () => AsyncStream.failed[Int](boom("S")).runFoldCall(0)(_ + _),
      Failed("S")
    ),
    Leaf(
      "zio stream.runFoldCall(sum)",
      () => ZStream(1, 2, 3).toAsyncStream.runFoldCall(0)(_ + _),
      Value(6)
    ),
    Leaf(
      "stream.evalMapCall(failing call).runFoldCall",
      () =>
        AsyncStream(1, 2, 3)
          .evalMapCall(n =>
            if (n == 2) AsyncCall.failed[Int](boom("E")) else AsyncCall.successful(n)
          )
          .runFoldCall(0)(_ + _),
      Failed("E")
    ),
    Leaf(
      "unwrapCall.evalMapCall.runFoldCall",
      () =>
        AsyncStream
          .unwrapCall(AsyncCall.successful(AsyncStream(1, 2)))
          .evalMapCall(n => AsyncCall.successful(n * 2))
          .runFoldCall(0)(_ + _),
      Value(6)
    )
  )

  // model helpers: an operator on the outcome only
  private def onValue(f: Int => Outcome): Observed => Observed =
    observed =>
      observed.outcome match {
        case Value(value) => observed.copy(outcome = f(value))
        case _: Failed    => observed
      }

  private def onFailure(f: String => Outcome): Observed => Observed =
    observed =>
      observed.outcome match {
        case Failed(tag) => observed.copy(outcome = f(tag))
        case _: Value    => observed
      }

  private def describe(termination: Termination): String = termination match {
    case Termination.Completed       => "Completed"
    case Termination.Failed(e: Boom) => s"Failed(${e.tag})"
    case Termination.Failed(other)   => s"Failed(UNEXPECTED $other)"
    case Termination.Cancelled       => "Cancelled"
  }

  private def terminationOf(outcome: Outcome): String = outcome match {
    case _: Value    => "Completed"
    case Failed(tag) => s"Failed($tag)"
  }

  // a hook that fails: a completed call fails with it, a failed one keeps its failure
  private val failingHook: Observed => Observed = onValue(_ => Failed("hook"))

  val operators: Vector[Op] = Vector(
    Op(
      "map(+1)",
      (
        call,
        _
      ) => call.map(_ + 1),
      onValue(n => Value(n + 1))
    ),
    Op(
      "map(throw)",
      (
        call,
        _
      ) => call.map[Int](_ => throw boom("map")),
      onValue(_ => Failed("map"))
    ),
    Op(
      "flatMap(successful)",
      (
        call,
        _
      ) => call.flatMap(n => AsyncCall.successful(n * 2)),
      onValue(n => Value(n * 2))
    ),
    Op(
      "flatMap(failed)",
      (
        call,
        _
      ) => call.flatMap[Int](_ => AsyncCall.failed(boom("flatMap"))),
      onValue(_ => Failed("flatMap"))
    ),
    Op(
      "flatMap(throw)",
      (
        call,
        _
      ) => call.flatMap[Int](_ => throw boom("flatMapThrow")),
      onValue(_ => Failed("flatMapThrow"))
    ),
    Op(
      "flatMap(future)",
      (
        call,
        _
      ) => call.flatMap(n => AsyncCall.future(Future.successful(n + 10))),
      onValue(n => Value(n + 10))
    ),
    Op(
      "flatMap(zio)",
      (
        call,
        _
      ) => call.flatMap(n => ZIO.succeed(n + 20).toAsyncCall),
      onValue(n => Value(n + 20))
    ),
    Op(
      "zip(successful)",
      (
        call,
        _
      ) => call.zip(AsyncCall.successful(7)).map { case (a, b) => a + b },
      onValue(n => Value(n + 7))
    ),
    Op(
      "zip(failed)",
      (
        call,
        _
      ) => call.zip(AsyncCall.failed[Int](boom("zip"))).map { case (a, b) => a + b },
      onValue(_ => Failed("zip"))
    ),
    Op(
      "zip(throw)",
      (
        call,
        _
      ) => call.zip[Int](throw boom("zipThrow")).map { case (a, b) => a + b },
      onValue(_ => Failed("zipThrow"))
    ),
    Op(
      "mapError",
      (
        call,
        _
      ) => call.mapError { case e: Boom => boom(e.tag + "'") },
      onFailure(tag => Failed(tag + "'"))
    ),
    Op(
      "mapError(undefined)",
      (
        call,
        _
      ) => call.mapError { case e: IllegalStateException => e },
      identity
    ),
    Op(
      "mapError(throw)",
      (
        call,
        _
      ) => call.mapError { case _: Boom => throw boom("mapErrorThrow") },
      onFailure(_ => Failed("mapErrorThrow"))
    ),
    Op(
      "recover",
      (
        call,
        _
      ) => call.recover { case _: Boom => 100 },
      onFailure(_ => Value(100))
    ),
    Op(
      "recover(undefined)",
      (
        call,
        _
      ) => call.recover { case _: IllegalStateException => 0 },
      identity
    ),
    Op(
      "recover(throw)",
      (
        call,
        _
      ) => call.recover { case _: Boom => throw boom("recoverThrow") },
      onFailure(_ => Failed("recoverThrow"))
    ),
    Op(
      "recoverWith(successful)",
      (
        call,
        _
      ) => call.recoverWith { case _: Boom => AsyncCall.successful(200) },
      onFailure(_ => Value(200))
    ),
    Op(
      "recoverWith(failed)",
      (
        call,
        _
      ) => call.recoverWith { case _: Boom => AsyncCall.failed(boom("recoverWith")) },
      onFailure(_ => Failed("recoverWith"))
    ),
    Op(
      "recoverWith(future)",
      (
        call,
        _
      ) => call.recoverWith { case _: Boom => AsyncCall.future(Future.successful(201)) },
      onFailure(_ => Value(201))
    ),
    Op(
      "transform(swap)",
      (
        call,
        _
      ) =>
        call.transform {
          case Success(_) => Failure(boom("swapped"))
          case Failure(_) => Success(300)
        },
      observed =>
        observed.copy(outcome = observed.outcome match {
          case _: Value  => Failed("swapped")
          case _: Failed => Value(300)
        })
    ),
    Op(
      "transformWith",
      (
        call,
        _
      ) =>
        call.transformWith {
          case Success(n) => AsyncCall.future(Future.successful(n + 400))
          case Failure(_) => AsyncCall.failed(boom("transformWith"))
        },
      observed =>
        observed.copy(outcome = observed.outcome match {
          case Value(n)  => Value(n + 400)
          case _: Failed => Failed("transformWith")
        })
    ),
    Op(
      "transform(throw)",
      (
        call,
        _
      ) => call.transform[Int](_ => throw boom("transformThrow")),
      observed => observed.copy(outcome = Failed("transformThrow"))
    ),
    Op(
      "transformWith(throw)",
      (
        call,
        _
      ) => call.transformWith[Int](_ => throw boom("transformWithThrow")),
      observed => observed.copy(outcome = Failed("transformWithThrow"))
    ),
    Op(
      "attempt.fold",
      (
        call,
        _
      ) => call.attempt.map(_.fold(_ => -1, _ + 1000)),
      observed =>
        observed.copy(outcome = observed.outcome match {
          case Value(n)  => Value(n + 1000)
          case _: Failed => Value(-1)
        })
    ),
    Op(
      "onTerminate",
      (
        call,
        log
      ) =>
        call.onTerminate { termination =>
          log.add(s"a:${describe(termination)}")
          ()
        },
      observed =>
        observed.copy(hooks = observed.hooks :+ s"a:${terminationOf(observed.outcome)}")
    ),
    Op(
      "guaranteeCase(future)",
      (
        call,
        log
      ) =>
        call.guaranteeCase(termination =>
          AsyncCall.future(Future.successful(())).map { _ =>
            log.add(s"b:${describe(termination)}")
            ()
          }
        ),
      observed =>
        observed.copy(hooks = observed.hooks :+ s"b:${terminationOf(observed.outcome)}")
    ),
    Op(
      "onTerminate(throw)",
      (
        call,
        _
      ) => call.onTerminate(_ => throw boom("hook")),
      failingHook
    ),
    Op(
      "guaranteeCase(failed)",
      (
        call,
        _
      ) => call.guaranteeCase(_ => AsyncCall.failed(boom("hook"))),
      failingHook
    ),
    Op(
      "timeout",
      (
        call,
        _
      ) => call.timeout(1.minute),
      identity
    ),
    Op(
      "delay",
      (
        call,
        _
      ) => call.delay(Duration.Zero),
      identity
    )
  )
}
