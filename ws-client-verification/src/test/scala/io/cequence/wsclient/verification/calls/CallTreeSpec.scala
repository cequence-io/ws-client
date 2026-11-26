package io.cequence.wsclient.verification.calls

import io.cequence.wsclient.stream.ZioInterop._
import io.cequence.wsclient.stream.{AsyncCall, Termination}
import io.cequence.wsclient.verification.calls.CallModel._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.{Cause, Exit, Runtime, Unsafe, ZIO}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CancellationException, ConcurrentLinkedQueue}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Random, Success}

/**
 * Plans as TREES - a continuation, a recovery or a hook holds a plan of its own, anywhere in
 * the plan - and a CANCELLATION from outside, against [[CallModel]]: every plan of a small
 * scope, and a large random sample of deep ones, through the `Future` interpreter and the ZIO
 * compiler, with the run cancelled while it is parked at the first `Gate` it reaches. Each
 * must give exactly `denote(plan)` - the end (a value, a failure, cancelled) and what the
 * hooks saw, in order - and so must the frame-stack machine (the model of `CallFiber`).
 *
 * `CallSmallScopeSpec` covers the leaves that are not modelled here (`traverse`, races,
 * streams, a `timeout`'s real race); this spec is about how operators, hooks and cancellation
 * compose - which the linear plans of that spec cannot reach (a hook inside a continuation
 * inside a recovery, a gate below a hook's owner, ...).
 *
 * The model is the one the proofs are about (`formal/lean`, `Calls/Proofs.lean`), so what this
 * spec compares the code with is what is proved for every plan.
 *
 * Knobs: `-Dwsclient.verify.calltrees.depth` (operators on a leaf; default 2),
 * `-Dwsclient.verify.calltrees.random` (random plans; default 20000),
 * `-Dwsclient.verify.calltrees.seed`.
 */
class CallTreeSpec extends AnyWordSpec with Matchers {

  import CallTreeSpec._

  private val depth = sys.props.getOrElse("wsclient.verify.calltrees.depth", "2").toInt
  private val randomCount =
    sys.props.getOrElse("wsclient.verify.calltrees.random", "20000").toInt
  private val seed = sys.props.getOrElse("wsclient.verify.calltrees.seed", "1").toLong

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val runtime: Runtime[Any] = Runtime.default
  private val limit = 10.seconds

  // ------------------------------------------------------------------------------ running

  // waits until the run ended or is parked at the gate - then cancels it
  private def settle(
    done: => Boolean,
    ctx: Ctx,
    cancel: () => Unit
  ): Unit = {
    val deadline = System.nanoTime() + limit.toNanos
    while (!done && !ctx.reached.get && System.nanoTime() < deadline) Thread.onSpinWait()
    if (!done && ctx.reached.get) cancel()
  }

  private def observe(
    ctx: Ctx,
    res: => Res
  ): Obs = {
    val resolved =
      try res
      catch { case NonFatal(_) => RErr(Unexpected) }
    Obs(resolved, ctx.log.asScala.toList)
  }

  private def futureObs(plan: Plan): Obs = {
    val ctx = new Ctx
    val run = toCall(plan, ctx).run()
    settle(run.result.isCompleted, ctx, () => run.cancel())
    observe(
      ctx,
      Await.ready(run.result, limit).value.get match {
        case Success(value)                    => ROk(value)
        case Failure(boom: Boom)               => RErr(boom.tag)
        case Failure(_: CancellationException) => RCancelled
        case Failure(_)                        => RErr(Unexpected)
      }
    )
  }

  private def zioObs(plan: Plan): Obs = {
    val ctx = new Ctx
    val future =
      Unsafe.unsafe(implicit unsafe =>
        runtime.unsafe.runToFuture(toCall(plan, ctx).toZIO.exit)
      )
    settle(future.isCompleted, ctx, () => { future.cancel(); () })
    observe(
      ctx,
      Await.ready(future, limit).value.get match {
        case Success(Exit.Success(value)) => ROk(value)
        case Success(Exit.Failure(cause)) => ofCause(cause)
        case Failure(e: zio.FiberFailure) => ofCause(e.cause.asInstanceOf[Cause[Throwable]])
        // an interrupted fiber's future fails with its InterruptedException, which Scala's
        // Future boxes
        case Failure(e: java.util.concurrent.ExecutionException)
            if e.getCause.isInstanceOf[InterruptedException] =>
          RCancelled
        case Failure(_) => RErr(Unexpected)
      }
    )
  }

  // an interruption only is a cancellation; otherwise the call's failure (a defect included)
  private def ofCause(cause: Cause[Throwable]): Res =
    if (cause.isInterruptedOnly) RCancelled
    else
      cause.squash match {
        case boom: Boom => RErr(boom.tag)
        case _          => RErr(Unexpected)
      }

  private def machineObs(plan: Plan): Obs = {
    var config = init(plan)
    var steps = 0
    var next = step(config)
    while (next.isDefined && steps < MaxSteps) {
      config = next.get
      steps += 1
      next = step(config)
    }
    withClue(s"the machine did not end within $MaxSteps steps: $plan") {
      next shouldBe None
    }
    result(config)
  }

  private val interpreters: Vector[(String, Plan => Obs)] =
    Vector("Future" -> futureObs, "ZIO" -> zioObs)

  // ------------------------------------------------------------------------------- scopes

  private def check(
    plans: Iterator[Plan],
    run: Plan => Obs
  ): (Int, Vector[String]) = {
    var count = 0
    val differences = Vector.newBuilder[String]
    var found = 0
    plans.foreach { plan =>
      count += 1
      val expected = denote(plan)
      val actual = run(plan)
      if (actual != expected && found < 20) {
        found += 1
        differences += s"$plan\n      expected $expected\n      actual   $actual"
      }
    }
    (count, differences.result())
  }

  "the scope of plans" should {

    "only hold plans whose hooks have no gate" in {
      val bad = scope(depth).filterNot(hooksOk).take(5).toList
      bad shouldBe empty
      randomPlans(seed, 2000).filterNot(hooksOk).take(5).toList shouldBe empty
    }
  }

  "the frame-stack machine" should {

    s"end with the denotation on every plan of depth $depth" in {
      val (count, differences) = check(scope(depth), machineObs)
      info(s"$count plans")
      differences shouldBe empty
    }

    "end with the denotation on random deep plans" in {
      val (count, differences) = check(randomPlans(seed, randomCount), machineObs)
      info(s"$count plans")
      differences shouldBe empty
    }
  }

  interpreters.foreach { case (name, run) =>
    s"the $name interpreter" should {

      s"give the denotation on every plan of depth $depth - cancelled at the first gate" in {
        val (count, differences) = check(scope(depth), run)
        info(s"$count plans")
        differences shouldBe empty
      }

      "give the denotation on random deep plans" in {
        val (count, differences) = check(randomPlans(seed, randomCount), run)
        info(s"$count plans")
        differences shouldBe empty
      }
    }
  }
}

object CallTreeSpec {

  private implicit val runtime: Runtime[Any] = Runtime.default

  /** A failure no plan makes: the call ended in a way the model has no word for. */
  val Unexpected: Int = -999

  val MaxSteps: Int = 1000000

  final class Boom(val tag: Int) extends RuntimeException(s"boom $tag") with NoStackTrace

  /** What a run records: the hooks' log, and whether it parked at a gate. */
  final class Ctx {
    val log = new ConcurrentLinkedQueue[Ev]()
    val reached = new AtomicBoolean(false)
  }

  private def termOf(termination: Termination): Term = termination match {
    case Termination.Completed       => Completed
    case Termination.Failed(e: Boom) => Failed(e.tag)
    case Termination.Failed(_)       => Failed(Unexpected)
    case Termination.Cancelled       => Cancelled
  }

  /** A plan as a real call - the gate parks on a future that never completes. */
  def toCall(
    plan: Plan,
    ctx: Ctx
  )(
    implicit ec: ExecutionContext
  ): AsyncCall[Int] = {
    def build(p: Plan): AsyncCall[Int] = toCall(p, ctx)

    plan match {
      case Value(Plain, v) => AsyncCall.successful(v)
      case Value(Fut, v)   => AsyncCall.future(Future.successful(v))
      case Value(Zio, v)   => ZIO.succeed(v).toAsyncCall
      case Value(_, v)     => AsyncCall.sleep(Duration.Zero).map(_ => v)
      case Fail(Plain, t)  => AsyncCall.failed(new Boom(t))
      case Fail(Fut, t)    => AsyncCall.future(Future.failed[Int](new Boom(t)))
      case Fail(Zio, t)    => ZIO.fail(new Boom(t)).toAsyncCall
      case Fail(_, t)      => ZIO.die(new Boom(t)).toAsyncCall
      case Defer(q)        => AsyncCall.defer(build(q))
      case DeferThrow(t)   => AsyncCall.defer[Int](throw new Boom(t))
      case Gate =>
        AsyncCall.defer {
          ctx.reached.set(true)
          AsyncCall.future(Promise[Int]().future)
        }
      case MapAdd(up, n)   => build(up).map(_ + n)
      case MapThrow(up, t) => build(up).map[Int](_ => throw new Boom(t))
      case FlatInc(up, n)  => build(up).flatMap(v => AsyncCall.successful(v + n))
      case FlatThen(up, q) => build(up).flatMap(_ => build(q))
      case FlatThrow(up, t) =>
        build(up).flatMap[Int](_ => throw new Boom(t))
      case MapError(up) =>
        build(up).mapError { case e: Boom => new Boom(e.tag + 100) }
      case MapErrorThrow(up, t) =>
        build(up).mapError { case _: Boom => throw new Boom(t) }
      case RecoverTo(up, n) => build(up).recover { case _: Boom => n }
      case RecoverUndefined(up) =>
        build(up).recover { case _: IllegalStateException => 0 }
      case RecoverThrow(up, t) =>
        build(up).recover { case _: Boom => throw new Boom(t) }
      case RecoverWith(up, q) => build(up).recoverWith { case _: Boom => build(q) }
      case TransformSwap(up) =>
        build(up).transform {
          case Success(_) => Failure(new Boom(99))
          case Failure(_) => Success(300)
        }
      case TransformWith(up, onOk, onErr) =>
        build(up).transformWith {
          case Success(_) => build(onOk)
          case Failure(_) => build(onErr)
        }
      case TransformThrow(up, t) =>
        build(up).transform[Int](_ => throw new Boom(t))
      case AttemptFold(up) => build(up).attempt.map(_.fold(_ => -1, _ + 1000))
      case GuardLog(up, id) =>
        build(up).onTerminate { termination =>
          ctx.log.add(Ev(id, termOf(termination)))
          ()
        }
      case GuardFail(up, t) =>
        build(up).guaranteeCase(_ => AsyncCall.failed(new Boom(t)))
      case GuardThrow(up, t) =>
        build(up).onTerminate(_ => throw new Boom(t))
      case GuardRun(up, q) =>
        build(up).guaranteeCase(_ => build(q).map(_ => ()))
      case Timeout(up) => build(up).timeout(1.minute)
      case Delay(up)   => build(up).delay(Duration.Zero)
    }
  }

  // --------------------------------------------------------------------- the small scope

  private val leaves: Vector[Plan] = Vector(
    Value(Plain, 1),
    Fail(Plain, 2),
    Value(Fut, 3),
    Fail(Fut, 4),
    Value(Zio, 5),
    Fail(Zio, 6),
    Fail(ZioDie, 7),
    Value(SleepK, 8),
    DeferThrow(9),
    Defer(Value(Plain, 10)),
    Gate
  )

  // the plans an operator's function returns (and the hooks' - without a gate)
  private val children: Vector[Plan] = Vector(
    Value(Plain, 40),
    Fail(Plain, 41),
    Gate,
    GuardLog(Value(Plain, 42), 90),
    MapAdd(Fail(Fut, 43), 1),
    Defer(Value(Zio, 44))
  )

  private val branches: Vector[Plan] = Vector(Value(Plain, 50), Fail(Plain, 51), Gate)

  // one operator over `up`; `position` makes the ids of the hooks tell them apart
  private val operators: Vector[(Plan, Int) => Plan] = {
    val unary: Vector[(Plan, Int) => Plan] = Vector(
      (
        p,
        _
      ) => MapAdd(p, 1),
      (
        p,
        _
      ) => MapThrow(p, 21),
      (
        p,
        _
      ) => FlatInc(p, 10),
      (
        p,
        _
      ) => FlatThrow(p, 22),
      (
        p,
        _
      ) => MapError(p),
      (
        p,
        _
      ) => MapErrorThrow(p, 23),
      (
        p,
        _
      ) => RecoverTo(p, 100),
      (
        p,
        _
      ) => RecoverUndefined(p),
      (
        p,
        _
      ) => RecoverThrow(p, 24),
      (
        p,
        _
      ) => TransformSwap(p),
      (
        p,
        _
      ) => TransformThrow(p, 25),
      (
        p,
        _
      ) => AttemptFold(p),
      (
        p,
        i
      ) => GuardLog(p, i),
      (
        p,
        _
      ) => GuardFail(p, 26),
      (
        p,
        _
      ) => GuardThrow(p, 27),
      (
        p,
        _
      ) => Timeout(p),
      (
        p,
        _
      ) => Delay(p)
    )
    val flatThen: Vector[(Plan, Int) => Plan] = children.map(q =>
      (
        p: Plan,
        _: Int
      ) => FlatThen(p, q)
    )
    val recoverWith: Vector[(Plan, Int) => Plan] =
      children.map(q =>
        (
          p: Plan,
          _: Int
        ) => RecoverWith(p, q)
      )
    val transformWith: Vector[(Plan, Int) => Plan] = for {
      onOk <- branches
      onErr <- branches
    } yield (
      p: Plan,
      _: Int
    ) => TransformWith(p, onOk, onErr)
    val guardRun: Vector[(Plan, Int) => Plan] =
      children
        .filter(gateFree)
        .map(q =>
          (
            p: Plan,
            _: Int
          ) => GuardRun(p, q)
        )
    unary ++ flatThen ++ recoverWith ++ transformWith ++ guardRun
  }

  /** Every plan of up to `maxDepth` operators on a leaf. */
  def scope(maxDepth: Int): Iterator[Plan] = {
    def extend(
      plan: Plan,
      position: Int,
      left: Int
    ): Iterator[Plan] =
      if (left == 0) Iterator.single(plan)
      else
        Iterator.single(plan) ++ operators.iterator.flatMap(op =>
          extend(op(plan, position), position + 1, left - 1)
        )
    // `extend` yields every prefix: take the plans of exactly each length once
    leaves.iterator.flatMap(leaf => extend(leaf, 1, maxDepth))
  }

  // ------------------------------------------------------------------------ random plans

  /** `count` random plans, deep and bushy (the same each run for a seed). */
  def randomPlans(
    seed: Long,
    count: Int
  ): Iterator[Plan] = {
    val random = new Random(seed)
    Iterator.fill(count)(randomPlan(random, 3 + random.nextInt(7), gates = true))
  }

  private def randomLeaf(
    random: Random,
    gates: Boolean
  ): Plan = {
    val pool = if (gates) leaves else leaves.filterNot(_ == Gate)
    pool(random.nextInt(pool.size))
  }

  private def randomPlan(
    random: Random,
    size: Int,
    gates: Boolean
  ): Plan =
    if (size <= 1 || random.nextInt(8) == 0) randomLeaf(random, gates)
    else {
      def sub(share: Int): Plan = randomPlan(random, math.max(1, (size - 1) / share), gates)
      def up: Plan = randomPlan(random, size - 1, gates)
      def hook: Plan = randomPlan(random, math.max(1, (size - 1) / 2), gates = false)
      val n = random.nextInt(60)
      n match {
        case 0            => Defer(up)
        case 1 | 2        => MapAdd(up, random.nextInt(5))
        case 3            => MapThrow(up, 21)
        case 4 | 5        => FlatInc(up, random.nextInt(5))
        case 6            => FlatThrow(up, 22)
        case 7 | 8 | 9    => FlatThen(randomPlan(random, size / 2, gates), sub(2))
        case 10           => MapError(up)
        case 11           => MapErrorThrow(up, 23)
        case 12 | 13      => RecoverTo(up, 100 + random.nextInt(5))
        case 14           => RecoverUndefined(up)
        case 15           => RecoverThrow(up, 24)
        case 16 | 17 | 18 => RecoverWith(randomPlan(random, size / 2, gates), sub(2))
        case 19 | 20      => TransformSwap(up)
        case 21 | 22 | 23 =>
          TransformWith(randomPlan(random, size / 3, gates), sub(3), sub(3))
        case 24                     => TransformThrow(up, 25)
        case 25 | 26                => AttemptFold(up)
        case 27 | 28 | 29 | 30 | 31 => GuardLog(up, random.nextInt(1000))
        case 32 | 33                => GuardFail(up, 26)
        case 34                     => GuardThrow(up, 27)
        case 35 | 36 | 37 | 38 | 39 | 40 =>
          GuardRun(randomPlan(random, size / 2, gates), hook)
        case 41 | 42 => Timeout(up)
        case 43      => Delay(up)
        case _       => MapAdd(up, 1)
      }
    }
}
