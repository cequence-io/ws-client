package io.cequence.wsclient.stream

import com.typesafe.scalalogging.Logger
import io.cequence.wsclient.concurrent.AsyncScheduler
import io.cequence.wsclient.stream.impl._
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * A backend-neutral, lazy computation of ONE value (since 1.3.0) - the single-value
 * counterpart of [[AsyncStream]]. Every engine produces it (`execGETRichCall`, ... - a service
 * gets `execGETCall`, ...), and every consumer runs it: as a `Future` (`toFuture`, `run`), or
 * natively as a ZIO effect (`toZIO`, ws-client-zio).
 *
 * Semantics:
 *   - COLD: building a call does no I/O; every user thunk and function runs per run. A run of
 *     an HTTP call is a new request (a re-run is a new, possibly billed, request).
 *   - RE-RUNNABLE: runs are independent.
 *   - INTERRUPTION travels to the leaf. Compiled for ZIO, interrupting the fiber interrupts
 *     the leaf - the zio-http engine's request is aborted. The `Future` interpreter stops a
 *     cancelled run (`run().cancel()`, the loser of a `timeout`, the rest of a failed
 *     `traverse`) at its next step: a native leaf in flight is cancelled (a ZIO leaf is
 *     interrupted, so a zio-http request is aborted - the interpreter does not wait for that
 *     to finish), a `Future` in flight runs on - a `Future` cannot be cancelled: the requests
 *     of the `Future`-based engines (Play, jdk, sttp, pekko-http) complete, on ZIO too - but
 *     nothing after it starts. The cancelled parts' hooks have run before the outcome goes on.
 *   - FAILURE: a non-fatal exception thrown by a user function (or a `future` thunk) fails the
 *     call with that exception, unwrapped; fatal errors are never caught. An interruption is
 *     NOT a failure: `recover`, `transformWith` and `attempt` never see it (only the
 *     termination hooks do, as `Cancelled`).
 *   - STACK SAFETY: long `flatMap` chains and recursion through `flatMap` (retry loops) run in
 *     constant stack on every interpreter.
 *   - EXECUTION: the `Future` interpreter runs user functions on the given `ExecutionContext`,
 *     never on an IO thread. Compiled for ZIO they run on the fiber, and a ZIO leaf runs IN
 *     the calling fiber - FiberRefs, log annotations and spans reach it.
 *   - TIME: `sleep`, `delay` and `timeout` use the implicit [[AsyncScheduler]] on the `Future`
 *     interpreter only - the one in scope where the call is BUILT (it is part of the plan);
 *     compiled for ZIO they use ZIO's `Clock` (so `TestClock` drives them).
 *
 * A call is an immutable plan (an AST) with NATIVE leaves: a ZIO engine's call compiled for
 * ZIO is the engine's own effect, with the operators applied as ZIO operators; a `Future` leaf
 * (what every `Future`-based engine produces) is a `Future` to the `Future` interpreter. A
 * BRIDGE - a hop between a backend's effect and a `Future` - happens only at a foreign leaf;
 * [[CallDiagnostics.counted]] counts them.
 */
abstract class AsyncCall[+A] private[wsclient] () {

  import AsyncCall._

  // -------------------------------------------------------------------------- transformations

  def map[B](f: A => B): AsyncCall[B] = MapCall(this, f)

  def flatMap[B](f: A => AsyncCall[B]): AsyncCall[B] = FlatMapCall(this, f)

  /** Replaces a failure `pf` is defined for; a throw in `pf` is the new failure. */
  def mapError(pf: PartialFunction[Throwable, Throwable]): AsyncCall[A] =
    RecoverCall[A](this, e => FailedCall(pf.applyOrElse(e, IdentityError)), "mapError")

  /** On a failure `pf` is defined for: its value. */
  def recover[B >: A](pf: PartialFunction[Throwable, B]): AsyncCall[B] =
    RecoverCall[B](
      this,
      e => {
        val b: Any = pf.applyOrElse(e, NoMatchFunction)
        if (b.asInstanceOf[AnyRef] eq NoMatch) FailedCall(e)
        else SucceedCall(b.asInstanceOf[B])
      },
      "recover"
    )

  /** On a failure `pf` is defined for: continues with `pf(e)`. */
  def recoverWith[B >: A](pf: PartialFunction[Throwable, AsyncCall[B]]): AsyncCall[B] =
    RecoverCall[B](
      this,
      e => pf.applyOrElse(e, (e: Throwable) => FailedCall(e)),
      "recoverWith"
    )

  /** `f` on the outcome - a value or a failure, never an interruption. */
  def transform[B](f: Try[A] => Try[B]): AsyncCall[B] =
    TransformCall[A, B](this, outcome => fromTry(f(outcome)), "transform")

  /** Continues with `f(outcome)` - a value or a failure, never an interruption. */
  def transformWith[B](f: Try[A] => AsyncCall[B]): AsyncCall[B] =
    TransformCall(this, f, "transformWith")

  /** The outcome as a value: `Right(a)`, or `Left(failure)` (an interruption stays one). */
  def attempt: AsyncCall[Either[Throwable, A]] =
    TransformCall[A, Either[Throwable, A]](
      this,
      outcome => SucceedCall(outcome.toEither),
      "attempt"
    )

  /** Both values, one after the other: `that` (by-name, evaluated per run) runs after this. */
  def zip[B](that: => AsyncCall[B]): AsyncCall[(A, B)] =
    flatMap(a => that.map(b => (a, b)))

  // ------------------------------------------------------------------------------------- time

  /** This call, started after `duration` (per run). */
  def delay(
    duration: FiniteDuration
  )(
    implicit scheduler: AsyncScheduler
  ): AsyncCall[A] = {
    val call = this
    AsyncCall.sleep(duration).flatMap(_ => call)
  }

  /**
   * This call, or a failure with a `java.util.concurrent.TimeoutException` once `limit`
   * passed: the call is then cancelled (on ZIO interrupted - an HTTP request is aborted; on
   * the `Future` interpreter stopped at its next step, see the class comment). A timer the
   * scheduler refuses (a limit beyond what it can take, a scheduler shut down) leaves the call
   * unlimited, with a warning logged - never on ZIO, whose clock takes any limit.
   */
  def timeout(
    limit: FiniteDuration
  )(
    implicit scheduler: AsyncScheduler
  ): AsyncCall[A] =
    TimeoutCall[A](
      this,
      limit,
      DefaultTimeoutError,
      None,
      logUnscheduled(limit),
      scheduler
    )

  /** As `timeout(limit)`, failing with `error` (by-name, evaluated when the limit passes). */
  def timeout(
    limit: FiniteDuration,
    error: => Throwable
  )(
    implicit scheduler: AsyncScheduler
  ): AsyncCall[A] =
    TimeoutCall[A](this, limit, () => error, None, logUnscheduled(limit), scheduler)

  /**
   * As `timeout(limit, error)`, keeping the outcome of a loser that could not be stopped - on
   * both interpreters. A `Future` cannot be cancelled: a loser with a `Future` leaf in flight
   * when the limit passes (a `Future`-based engine's request, anywhere inside it - below a
   * `traverse` too) is left to finish in the background, its remaining steps included, as a
   * plain `Future` chain would, and its outcome goes to `onLate`; so does the outcome of a
   * loser that was past its last step and ended on its own. Any other loser is stopped (on ZIO
   * interrupted - a zio-http request is aborted) and not reported. A loser left to finish runs
   * outside this call: nothing bounds or cancels it any more (an enclosing `traverse`'s
   * parallelism does not count it) - put this timeout on the single request, inside a retry,
   * not around the retry. A throw in `onLate` is ignored. On the `Future` interpreter a timer
   * the scheduler refuses leaves the call unlimited and goes to `onUnscheduled` instead of the
   * log.
   */
  def timeout(
    limit: FiniteDuration,
    error: => Throwable,
    onLate: Try[A] => Unit,
    onUnscheduled: Throwable => Unit
  )(
    implicit scheduler: AsyncScheduler
  ): AsyncCall[A] =
    TimeoutCall[A](this, limit, () => error, Some(onLate), onUnscheduled, scheduler)

  // ------------------------------------------------------------------------------- lifecycle

  /** `guaranteeCase` with a synchronous hook. */
  def onTerminate(f: Termination => Unit): AsyncCall[A] =
    GuaranteeCall[A](
      this,
      termination => {
        f(termination)
        AsyncCall.unit
      },
      "onTerminate"
    )

  /**
   * `f` runs exactly once per run that reached this call, after the call ended - `Completed`,
   * `Failed(e)` or `Cancelled` (cancelled or interrupted) - and before its outcome goes on: a
   * failing hook fails a completed call, and is added (suppressed) to a failure. The hook
   * itself is not interrupted. A run cancelled (interrupted) before it reached this call never
   * started it, and does not run the hook.
   */
  def guaranteeCase(f: Termination => AsyncCall[Unit]): AsyncCall[A] =
    GuaranteeCall(this, f, "guaranteeCase")

  // ---------------------------------------------------------------------------------- running

  /**
   * Runs the call on the `Future` interpreter (each call of `toFuture` is a new run): user
   * functions on `ec`; a native leaf (e.g. the zio-http engine's request) through its
   * fallback, on its own runtime.
   */
  def toFuture(
    implicit ec: ExecutionContext
  ): Future[A] =
    CallRunner.start(this, ec).result

  /** Runs the call as `toFuture` does, and returns the run, which can be cancelled. */
  def run(
  )(
    implicit ec: ExecutionContext
  ): CallRun[A] =
    CallRunner.start(this, ec)

  /**
   * The plan of this call, leaf first (e.g. `native(zio) -> map -> recoverWith -> timeout(30
   * seconds)`) - never its values. The calls user functions return are not shown: they are
   * built per run.
   */
  def describe: String = CallCompiler.describe(this)

  // a call is a plan: identity equality (structural equality would compare functions)
  final override def equals(other: Any): Boolean =
    other.isInstanceOf[AnyRef] && (this eq other.asInstanceOf[AnyRef])

  final override def hashCode(): Int = System.identityHashCode(this)

  final override def toString: String = s"AsyncCall($describe)"
}

object AsyncCall {

  private val logger = Logger(LoggerFactory.getLogger(classOf[AsyncCall[_]]))

  private object NoMatch
  private val NoMatchFunction: Any => Any = _ => NoMatch
  private val IdentityError: Throwable => Throwable = e => e
  // the interpreters' default: `CallCompiler.timeoutFailure`
  private val DefaultTimeoutError: () => Throwable = () => null

  private def logUnscheduled(limit: FiniteDuration): Throwable => Unit =
    e =>
      logger.warn(
        s"AsyncCall.timeout($limit): the timer could not be scheduled - unlimited",
        e
      )

  def successful[A](value: A): AsyncCall[A] = SucceedCall(value)

  val unit: AsyncCall[Unit] = SucceedCall(())

  def failed[A](error: Throwable): AsyncCall[A] = FailedCall(error)

  def fromTry[A](outcome: Try[A]): AsyncCall[A] = outcome match {
    case Success(value) => SucceedCall(value)
    case Failure(error) => FailedCall(error)
  }

  /** `thunk` is evaluated once per run - the place for per-run state. */
  def defer[A](thunk: => AsyncCall[A]): AsyncCall[A] = DeferCall(() => thunk)

  /**
   * The future's value; `f` (by-name) is evaluated once per run - a throw fails the call.
   * Compiled for ZIO this leaf is a bridge (`ZIO.fromFuture`).
   */
  def future[A](f: => Future[A]): AsyncCall[A] = FromFutureCall(_ => f)

  /**
   * As `future`, given the run's `ExecutionContext` (on ZIO the fiber's executor, as
   * `ZIO.fromFuture` gives it).
   */
  def fromFuture[A](f: ExecutionContext => Future[A]): AsyncCall[A] = FromFutureCall(f)

  /** A call in its producer's native representation - see [[NativeCall]]. */
  def fromNative[A](leaf: NativeCall[A]): AsyncCall[A] = NativeCallLeaf(leaf)

  /** Completes after `duration` (per run). */
  def sleep(
    duration: FiniteDuration
  )(
    implicit scheduler: AsyncScheduler
  ): AsyncCall[Unit] =
    SleepCall(duration, scheduler)

  /**
   * `f` for every item (`items` read once per run), at most `parallelism` at once - the next
   * starting as soon as one finished - with the results in the order of `items`. The first
   * failure fails the whole: no further item starts, and the ones still running are cancelled
   * (on ZIO interrupted). `parallelism = 1` runs them one after the other.
   */
  def traverse[A, B](
    items: immutable.Iterable[A],
    parallelism: Int
  )(
    f: A => AsyncCall[B]
  ): AsyncCall[immutable.Seq[B]] = {
    require(parallelism > 0, s"parallelism must be positive: $parallelism")
    TraverseCall(items, parallelism, f)
  }
}
