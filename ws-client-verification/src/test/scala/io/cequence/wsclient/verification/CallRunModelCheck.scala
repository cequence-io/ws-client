package io.cequence.wsclient.verification

import io.cequence.wsclient.concurrent.{AsyncCancellable, AsyncScheduler}
import io.cequence.wsclient.stream.{AsyncCall, AsyncStream, CallRun, NativeCall, Termination}

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * A run of an `AsyncCall` on the `Future` interpreter (`CallFiber`) against the scaladoc of
 * `AsyncCall` / `CallRun`: a run ends exactly once; `cancel()` either comes too late (the run
 * ends with its outcome) or stops the run (a `CancellationException`, the hooks see
 * `Cancelled`, nothing after the step in flight runs) - never something in between; a native
 * leaf's run that was started is never left running by a cancel; a `timeout`'s loser is
 * cancelled, or - with `onLate` - left to finish and reported exactly once, and the outcome of
 * a request that was sent is never lost; a failed `traverse` cancels what it started.
 *
 * The run's tasks go to a thread each (`ThreadPerTask`), so the checker interleaves the run
 * loop with the completions, the timer and `cancel()`.
 */
object CallRunModelCheck extends ModelCheckSuite("AsyncCall run") {

  private final class Boom(message: String) extends RuntimeException(message)

  /** A native leaf whose runs end when the scenario (or a cancel) says so. */
  private final class ControlledNative extends NativeCall[Int] {
    val started = new AtomicInteger()
    val cancelled = new AtomicInteger()
    val promise: Promise[Int] = Promise[Int]()

    override def start(): CallRun[Int] = {
      started.incrementAndGet()
      new CallRun[Int] {
        override def result: Future[Int] = promise.future
        override def cancel(): Unit = {
          cancelled.incrementAndGet()
          promise.tryFailure(new CancellationException("native run cancelled"))
          ()
        }
      }
    }

    override def backend: String = "controlled"
  }

  /** A timer the scenario fires from a thread of its own; a cancel that came first wins. */
  private final class ManualScheduler extends AsyncScheduler {
    @volatile private var task: () => Unit = _
    @volatile private var cancelledFlag = false

    override def scheduleOnce(delay: FiniteDuration)(scheduled: () => Unit)
      : AsyncCancellable = {
      task = scheduled
      new AsyncCancellable {
        override def cancel(): Boolean = {
          cancelledFlag = true
          true
        }
        override def isCancelled: Boolean = cancelledFlag
      }
    }

    def fire(): Unit = {
      val scheduled = task
      if (scheduled != null && !cancelledFlag) scheduled()
    }
  }

  private def isCancellation(outcome: Option[Try[Any]]): Boolean =
    outcome.exists {
      case Failure(_: CancellationException) => true
      case _                                 => false
    }

  scenario("cancel() racing a Future leaf's completion: the outcome, or a clean stop") {
    val ec = new ThreadPerTask
    val upstream = Promise[Int]()
    @volatile var continued = false
    val hooks = new AtomicInteger()
    @volatile var seen: Termination = null

    val run = AsyncCall
      .future(upstream.future)
      .map { n =>
        continued = true
        n + 1
      }
      .onTerminate { termination =>
        hooks.incrementAndGet()
        seen = termination
      }
      .run()(ec)

    val canceller = thread(run.cancel())
    val completer = thread {
      upstream.trySuccess(1)
      ()
    }
    canceller.join()
    completer.join()
    ec.joinAll()

    val outcome = run.result.value
    ensure(outcome.isDefined, "the run did not end")
    outcome match {
      case Some(Success(2)) =>
        ensure(hooks.get == 1, s"a value, but the hook ran ${hooks.get} times")
        ensure(continued && seen == Termination.Completed, s"a value, but the hook saw $seen")
      case other =>
        ensure(isCancellation(other), s"neither the value nor a cancellation: $other")
        ensure(!continued, "a cancelled run went on after its step in flight")
        // a cancel before the run reached the hook's call: the call never started, no hook
        ensure(hooks.get <= 1, s"cancelled, and the hook ran ${hooks.get} times")
        ensure(
          hooks.get == 0 || seen == Termination.Cancelled,
          s"cancelled, but the hook saw $seen"
        )
    }
  }

  scenario("cancel() racing the start of a native leaf: a started run is always cancelled") {
    val ec = new ThreadPerTask
    val native = new ControlledNative

    val run = AsyncCall.fromNative(native).run()(ec)
    val canceller = thread(run.cancel())
    canceller.join()
    ec.joinAll()

    ensure(isCancellation(run.result.value), s"the run: ${run.result.value}")
    ensure(native.started.get <= 1, s"the native leaf was started ${native.started.get} times")
    ensure(
      native.cancelled.get == native.started.get,
      s"started ${native.started.get} native runs, cancelled ${native.cancelled.get}"
    )
  }

  scenario(
    "a timeout's timer racing the call's completion: one outcome, the loser accounted for"
  ) {
    val ec = new ThreadPerTask
    val scheduler = new ManualScheduler
    val upstream = Promise[Int]()
    @volatile var continued = false
    @volatile var requested = false
    val late = new AtomicInteger()
    @volatile var lateOutcome: Try[Int] = null

    val run = AsyncCall.future {
      requested = true
      upstream.future
    }.map { n =>
      continued = true
      n + 1
    }.timeout(
      1.second,
      new Boom("limit"),
      (outcome: Try[Int]) => {
        late.incrementAndGet()
        lateOutcome = outcome
      },
      _ => ()
    )(scheduler)
      .run()(ec)

    val timer = thread(scheduler.fire())
    val completer = thread {
      upstream.trySuccess(1)
      ()
    }
    timer.join()
    completer.join()
    ec.joinAll()

    run.result.value match {
      case None =>
        ensure(condition = false, "the run did not end")
      case Some(Success(value)) =>
        ensure(value == 2, s"the value: $value")
        ensure(late.get == 0, "a call that made it was also reported late")
      case Some(Failure(_: Boom)) =>
        // the loser was cancelled (it stops where it is), or left to finish and reported
        ensure(late.get <= 1, s"reported late ${late.get} times")
        ensure(
          continued == (late.get == 1),
          s"the loser ${if (continued) "ran on" else "stopped"}, reported late: ${late.get}"
        )
        ensure(late.get == 0 || lateOutcome == Success(2), s"the late outcome: $lateOutcome")
        // a request that was sent cannot be stopped: its outcome is never lost
        ensure(!requested || late.get == 1, "a request was sent, and its outcome not reported")
      case Some(other) =>
        ensure(condition = false, s"neither the value nor the timeout: $other")
    }
  }

  scenario(
    "traverse: an element's failure racing the start of another: all started are cancelled"
  ) {
    val ec = new ThreadPerTask
    val native = new ControlledNative
    val failing = Promise[Int]()

    val run = AsyncCall
      .traverse(List(0, 1), parallelism = 2) { index =>
        if (index == 0) AsyncCall.fromNative(native) else AsyncCall.future(failing.future)
      }
      .run()(ec)

    val failer = thread {
      failing.tryFailure(new Boom("element 1"))
      ()
    }
    failer.join()
    ec.joinAll()

    run.result.value match {
      case Some(Failure(boom: Boom)) =>
        ensure(boom.getMessage == "element 1", s"the failure: $boom")
      case other =>
        ensure(
          condition = false,
          s"the traverse did not fail with the element's failure: $other"
        )
    }
    ensure(
      native.cancelled.get == native.started.get,
      s"started ${native.started.get} native runs, cancelled ${native.cancelled.get}"
    )
  }

  scenario("firstCompletedOf: the winner's outcome, and every started loser cancelled") {
    val ec = new ThreadPerTask
    val native = new ControlledNative
    val winner = Promise[Int]()

    val run = AsyncCall
      .firstCompletedOf(Seq(AsyncCall.fromNative(native), AsyncCall.future(winner.future)))
      .run()(ec)

    val completer = thread {
      winner.trySuccess(7)
      ()
    }
    completer.join()
    ec.joinAll()

    ensure(run.result.value.contains(Success(7)), s"the race: ${run.result.value}")
    ensure(
      native.cancelled.get == native.started.get,
      s"started ${native.started.get} native runs, cancelled ${native.cancelled.get}"
    )
  }

  scenario("cancel() racing the completion of a stream's fold (runFoldCall): value or stop") {
    val ec = new ThreadPerTask
    val upstream = Promise[Int]()

    val run = AsyncStream.future(upstream.future).runFoldCall(10)(_ + _).run()(ec)

    val canceller = thread(run.cancel())
    val completer = thread {
      upstream.trySuccess(5)
      ()
    }
    canceller.join()
    completer.join()
    ec.joinAll()

    run.result.value match {
      case Some(Success(15)) => ()
      case other =>
        ensure(isCancellation(other), s"neither the fold nor a cancellation: $other")
    }
  }

  scenario(
    "fromCall: cancel() racing the start of its call: a started native run is cancelled"
  ) {
    val ec = new ThreadPerTask
    val native = new ControlledNative
    val elements = new AtomicInteger()

    val streamRun =
      AsyncStream
        .fromCall(AsyncCall.fromNative(native))
        .run(_ => elements.incrementAndGet())(ec)

    val canceller = thread(streamRun.cancel())
    canceller.join()
    ec.joinAll()

    ensure(streamRun.done.value.exists(_.isSuccess), s"the stream: ${streamRun.done.value}")
    ensure(elements.get == 0, s"${elements.get} elements from a cancelled stream")
    ensure(
      native.started.get <= 1 && native.cancelled.get == native.started.get,
      s"started ${native.started.get} native runs, cancelled ${native.cancelled.get}"
    )
  }

  scenario("fromCall: cancel() racing the call's completion: one end, at most one element") {
    val ec = new ThreadPerTask
    val upstream = Promise[Int]()
    val elements = new AtomicInteger()

    val streamRun =
      AsyncStream
        .fromCall(AsyncCall.future(upstream.future))
        .run(_ => elements.incrementAndGet())(ec)

    val canceller = thread(streamRun.cancel())
    val completer = thread {
      upstream.trySuccess(1)
      ()
    }
    canceller.join()
    completer.join()
    ec.joinAll()

    ensure(streamRun.done.value.exists(_.isSuccess), s"the stream: ${streamRun.done.value}")
    ensure(elements.get <= 1, s"${elements.get} elements from a single call")
  }
}
