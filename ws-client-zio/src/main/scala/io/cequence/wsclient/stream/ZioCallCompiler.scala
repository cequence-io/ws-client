package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl.{
  BridgeCounter,
  CallCompiler,
  LateScope,
  NeutralRunner,
  StreamRuntime
}
import zio._

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * A ZIO effect kept native inside an [[AsyncCall]]: compiled for ZIO it is `effect` itself -
 * no bridge, run IN the calling fiber; any other interpreter runs it on `runtime` (one
 * bridge), and cancelling that run interrupts it.
 */
private[wsclient] final class ZioCall[A] private[wsclient] (
  val effect: Task[A],
  runtime: Runtime[Any]
) extends NativeCall[A] {

  override def start(): CallRun[A] = {
    val future = Unsafe.unsafe(implicit unsafe => runtime.unsafe.runToFuture(effect))
    new CallRun[A] {
      override def result: Future[A] = future
      override def cancel(): Unit = {
        future.cancel()
        ()
      }
    }
  }

  override def backend: String = "zio"
}

private[wsclient] object ZioCallCompiler {

  // the enclosing timeouts with `onLate` of the fiber (see LateScope): a `Future` leaf
  // registers there while its Future is in flight
  private val lateScopes: FiberRef[List[LateScope]] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make[List[LateScope]](Nil))

  def toZIO[A](call: AsyncCall[A]): Task[A] =
    new ZioCallCompiler(BridgeCounter.current()).compile(call)
}

/**
 * Compiles an [[AsyncCall]] into a ZIO effect - natively: a ZIO leaf is returned as is, every
 * operator is a ZIO operator, time is ZIO's `Clock`. User functions run under `ZIO.attempt` (a
 * throw is a failure, never a defect); the calls they return are compiled when they are
 * reached (ZIO's run loop keeps the stack flat). A native ZIO leaf's defect counts as a
 * failure for `recover` / `transformWith` / the hooks, as on the streams. A bridge only for a
 * foreign leaf: a `Future` (`ZIO.fromFuture`) or another backend's native call. The visitor
 * gets no scheduler: time is ZIO's `Clock`, which takes any limit.
 */
private[wsclient] final class ZioCallCompiler(bridges: BridgeCounter) extends CallCompiler {

  type R[+A] = Task[A]

  def compile[A](call: AsyncCall[A]): Task[A] =
    CallCompiler.compile(this, call)

  // a call a user function builds, per run: a throw is a failure. The run's bridge counter is
  // the thread's while it builds (a thunk that compiles a stream counts into the scope that
  // compiled this call)
  private def child[A](build: => AsyncCall[A]): Task[A] =
    ZIO.attempt(BridgeCounter.withCounter(bridges)(build)).flatMap { call =>
      if (call == null)
        ZIO.fail(new NullPointerException("A user function returned a null AsyncCall"))
      else
        compile(call)
    }

  private def failureOf(cause: Cause[Throwable]): Option[Throwable] =
    ZioCompiler.failureOf(cause)

  // ------------------------------------------------------------------------------ leaves

  def succeed[A](value: A): Task[A] = ZIO.succeed(value)

  def failed[A](error: Throwable): Task[A] = ZIO.fail(error)

  def defer[A](thunk: () => AsyncCall[A]): Task[A] = child(thunk())

  // the fiber's executor as the ExecutionContext (ZIO.fromFuture's); a throw is a failure.
  // Interrupting the fiber only abandons a Future: below a timeout with `onLate` the leaf is
  // registered while it is in flight, so that timeout leaves the fiber running instead
  def fromFuture[A](f: ExecutionContext => Future[A]): Task[A] = {
    bridges.add(1)
    ZioCallCompiler.lateScopes.get.flatMap { scopes =>
      if (scopes.isEmpty)
        ZIO.fromFuture(ec => f(ec))
      else
        ZIO.uninterruptibleMask { restore =>
          if (LateScope.enter(scopes))
            restore(ZIO.fromFuture(ec => f(ec))).ensuring(ZIO.succeed(LateScope.exit(scopes)))
          else
            // an enclosing timeout is stopping this fiber: no request may start any more
            ZIO.interrupt
        }
    }
  }

  def native[A](leaf: NativeCall[A]): Task[A] = leaf match {
    case own: ZioCall[A @unchecked] =>
      own.effect
    case foreign =>
      bridges.add(1)
      // the run is started and its cancel registered in one uninterruptible step
      ZIO.uninterruptibleMask { restore =>
        ZIO.attempt(BridgeCounter.withCounter(bridges)(foreign.start())).flatMap { run =>
          restore(ZIO.fromFuture(_ => run.result)).onInterrupt(ZIO.succeed(run.cancel()))
        }
      }
  }

  // the neutral preflight (a non-ZIO stream), interrupted with the fiber: the request is aborted
  def runPreflight[A](stream: AsyncStream[A]): Task[AsyncStream[A]] = {
    bridges.add(1)
    ZIO.uninterruptibleMask { restore =>
      ZIO.executor.flatMap { executor =>
        ZIO
          .attempt(
            NeutralRunner.startPreflight(
              stream,
              StreamRuntime.context(executor.asExecutionContext, bridges)
            )
          )
          .flatMap { run =>
            restore(ZIO.fromFuture(_ => run.result)).onInterrupt(ZIO.succeed(run.cancel()))
          }
      }
    }
  }

  // the stream natively (ZioCompiler: a ZIO leaf stays itself), folded by ZIO's own run;
  // `zero` and the user's function run under ZIO.attempt (a throw is a failure)
  def runStream[A, S](
    stream: AsyncStream[A],
    zero: () => S,
    f: (S, A) => S
  ): Task[S] =
    ZIO
      .attempt(zero())
      .flatMap(z =>
        new ZioCompiler(bridges)
          .compile(stream)
          .runFoldZIO(z)(
            (
              s,
              a
            ) => ZIO.attempt(f(s, a))
          )
      )

  def sleep(duration: FiniteDuration): Task[Unit] =
    ZIO.sleep(Duration.fromScala(duration))

  def traverse[A, B](
    items: immutable.Iterable[A],
    parallelism: Int,
    f: A => AsyncCall[B]
  ): Task[immutable.Seq[B]] =
    ZIO
      .attempt(items.toVector)
      .flatMap(inputs => ZIO.foreachPar(inputs)(a => child(f(a))).withParallelism(parallelism))

  // ---------------------------------------------------------------------------- operators

  def map[A, B](
    up: Task[A],
    f: A => B
  ): Task[B] =
    up.flatMap(a => ZIO.attempt(f(a)))

  def flatMap[A, B](
    up: Task[A],
    f: A => AsyncCall[B]
  ): Task[B] =
    up.flatMap(a => child(f(a)))

  def recover[A](
    up: Task[A],
    onFailure: Throwable => AsyncCall[A]
  ): Task[A] =
    up.catchAllCause(cause =>
      failureOf(cause) match {
        case Some(e) => child(onFailure(e))
        case None    => ZIO.refailCause(cause)
      }
    )

  def transform[A, B](
    up: Task[A],
    f: Try[A] => AsyncCall[B]
  ): Task[B] =
    up.foldCauseZIO(
      cause =>
        failureOf(cause) match {
          case Some(e) => child(f(Failure(e)))
          case None    => ZIO.refailCause(cause)
        },
      a => child(f(Success(a)))
    )

  // ZIO's clock takes any limit. The upstream runs on a fiber of its own. Without `onLate` the
  // loser is interrupted - and waited for. With it, when the limit passes, a loser with a
  // Future in flight (LateScope) is left to finish and reported; any other is interrupted,
  // and reported if it still ended with an outcome of its own
  def timeout[A](
    up: Task[A],
    limit: FiniteDuration,
    error: () => Throwable,
    onLate: Option[Try[A] => Unit]
  ): Task[A] = {
    val timedOut: Task[A] =
      ZIO.suspendSucceed(ZIO.fail(CallCompiler.timeoutFailure(limit, error)))

    onLate match {
      // the upstream on a fiber of its own, as ZIO's `timeout` runs it - but an interrupt of
      // this fiber, and a limit that passes, WAIT for it to end (ZIO's own `timeout`
      // interrupts the loser in the background): the hooks below ran, innermost first, before
      // the outcome goes on, as on the `Future` interpreter
      case None =>
        ZIO.uninterruptibleMask { restore =>
          for {
            fiber <- restore(up).forkDaemon
            raced <- restore(fiber.await.timeout(Duration.fromScala(limit)))
              .onInterrupt(fiber.interrupt)
            result <- raced match {
              case Some(exit) => fiber.inheritAll *> ZIO.suspendSucceed(exit)
              case None       => fiber.interrupt *> timedOut
            }
          } yield result
        }

      case Some(late) =>
        def report(exit: Exit[Throwable, A]): UIO[Unit] =
          ZIO.succeed {
            val outcome: Option[Try[A]] = exit.foldExit(
              cause => failureOf(cause).map(Failure(_)),
              a => Some(Success(a))
            )
            outcome.foreach { finished =>
              try late(finished)
              catch { case NonFatal(_) => () }
            }
          }

        ZIO.uninterruptibleMask { restore =>
          val scope = new LateScope
          for {
            fiber <- ZioCallCompiler.lateScopes.locallyWith(scope :: _)(restore(up)).forkDaemon
            raced <- restore(fiber.await.timeout(Duration.fromScala(limit)))
              .onInterrupt(fiber.interrupt)
            result <- raced match {
              case Some(exit) =>
                ZIO.suspendSucceed(exit)
              case None if scope.lockIfIdle() =>
                fiber.interrupt.flatMap(report) *> timedOut
              case None =>
                fiber.await.flatMap(report).forkDaemon *> timedOut
            }
          } yield result
        }
    }
  }

  // a hook's call runs outside the late scopes (it is never stopped)
  private def hook(
    f: Termination => AsyncCall[Unit],
    termination: Termination
  ): Task[Unit] =
    ZioCallCompiler.lateScopes.locally(Nil)(child(f(termination)))

  // the hook runs uninterrupted, after the call ended and before its outcome goes on
  def guarantee[A](
    up: Task[A],
    f: Termination => AsyncCall[Unit]
  ): Task[A] =
    ZIO.uninterruptibleMask { restore =>
      restore(up).foldCauseZIO(
        cause => {
          val termination: Termination =
            failureOf(cause).fold[Termination](Termination.Cancelled)(Termination.Failed(_))
          hook(f, termination).foldCauseZIO(
            hookCause => {
              termination match {
                case Termination.Failed(e) =>
                  failureOf(hookCause).foreach(h => if (h ne e) e.addSuppressed(h))
                case Termination.Completed | Termination.Cancelled =>
                  ()
              }
              ZIO.refailCause(cause)
            },
            _ => ZIO.refailCause(cause)
          )
        },
        a => hook(f, Termination.Completed).as(a)
      )
    }
}
