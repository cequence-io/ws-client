package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.concurrent.{AsyncCancellable, AsyncScheduler}
import io.cequence.wsclient.stream.{AsyncCall, CallRun, Termination}

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** The neutral (`Future`) interpreter of [[AsyncCall]]. */
private[wsclient] object CallRunner {

  /** Starts a run of `call` on `ec`; the bridge counter is the current scope's. */
  def start[A](
    call: AsyncCall[A],
    ec: ExecutionContext
  ): CallRun[A] = {
    val fiber = new CallFiber(call, ec, BridgeCounter.current(), Nil)
    fiber.start()
    fiber.asInstanceOf[CallRun[A]]
  }
}

/**
 * The `Future` leaves in flight below a `timeout` with `onLate` - what decides, when its limit
 * passes, whether the loser can be stopped: a `Future` cannot be cancelled, so a loser with
 * one in flight is left to finish (and reported), any other is cancelled. The decision is
 * atomic against a leaf starting: a leaf ENTERS before it creates its `Future` and is refused
 * once the scope is locked (the loser is being stopped - nothing may start a request any
 * more), and the timer LOCKS only an idle scope. Shared by both interpreters.
 */
private[wsclient] final class LateScope {

  // >= 0: the Future leaves in flight; -1: locked
  private val inFlight = new AtomicInteger(0)

  def enter(): Boolean = {
    var entered = false
    var decided = false
    while (!decided) {
      val current = inFlight.get()
      if (current < 0) decided = true
      else if (inFlight.compareAndSet(current, current + 1)) {
        entered = true
        decided = true
      }
    }
    entered
  }

  def exit(): Unit = {
    var done = false
    while (!done) {
      val current = inFlight.get()
      done = current <= 0 || inFlight.compareAndSet(current, current - 1)
    }
  }

  /** True if no `Future` leaf is in flight: none can start from now on. */
  def lockIfIdle(): Boolean = inFlight.compareAndSet(0, -1)
}

private[wsclient] object LateScope {

  /** Enters every scope, or none: false if one of them is locked. */
  def enter(scopes: List[LateScope]): Boolean = {
    var entered: List[LateScope] = Nil
    var rest = scopes
    var ok = true
    while (ok && rest.nonEmpty) {
      if (rest.head.enter()) entered = rest.head :: entered
      else ok = false
      rest = rest.tail
    }
    if (!ok) entered.foreach(_.exit())
    ok
  }

  def exit(scopes: List[LateScope]): Unit = scopes.foreach(_.exit())
}

/**
 * One run of a call on the neutral interpreter: a run loop over the AST with an explicit
 * continuation stack (`frames`), so neither a long spine nor recursion through `flatMap` grows
 * the thread's stack. Synchronous steps run in the loop; at an asynchronous step (a `Future`,
 * a native leaf, a timer, child runs) the loop SUSPENDS and returns, and the step's completion
 * RESUMES it on `ec` - so user functions always run on `ec` (a timer's task only hands over to
 * `ec`).
 *
 * At most one loop runs at a time: a suspension is resumed exactly once - by its completion or
 * by `cancel()`, whichever claims it first - and the hand-over goes through `ec.execute`
 * (happens-before), so `frames` needs no lock.
 *
 * Cancellation: `cancel()` sets a flag the loop checks before every step, and claims the
 * current suspension, if any: its handle cancels what it waits for (a native run, a timer,
 * child runs; a `Future` cannot be cancelled - it is abandoned), and the loop resumes to
 * unwind - running the termination hooks with `Cancelled` - and fails the run with a
 * `CancellationException`. Child RUNS are waited for: the loop resumes once the cancelled
 * children ended (their hooks ran); a native run's own cancellation is not (its `cancel()` may
 * be a no-op - the run would never stop). A hook runs to its end, uncancelled.
 *
 * `lateScopes`: the enclosing `timeout`s with `onLate` (see [[LateScope]]); every `Future`
 * leaf of this run and of its child runs registers there. Hooks run outside them.
 */
private[wsclient] final class CallFiber(
  root: AsyncCall[Any],
  ec: ExecutionContext,
  bridges: BridgeCounter,
  lateScopes: List[LateScope]
) extends CallRun[Any] {

  import CallFiber._

  private val promise = Promise[Any]()

  @volatile private var cancelRequested = false

  // the latest suspension (resumed or not) - `cancel()` claims it if it is still open
  private val current = new AtomicReference[Suspension]()

  // confined to the loop (see the class comment)
  private val frames = new ArrayDeque[Frame]()

  override def result: Future[Any] = promise.future

  def start(): Unit = resume(new Eval(root))

  override def cancel(): Unit =
    if (!cancelRequested) {
      cancelRequested = true
      val suspension = current.get()
      if (suspension != null) suspension.cancel()
    }

  private def resume(signal: Signal): Unit =
    try
      ec.execute(new Runnable {
        override def run(): Unit = loop(signal)
      })
    catch {
      // the run's ec rejects (e.g. shut down): nothing can run user code any more
      case NonFatal(e) => promise.tryFailure(e)
    }

  private def loop(initial: Signal): Unit = {
    var signal = initial
    while (signal ne null)
      signal = signal match {
        case eval: Eval => if (cancelRequested) Stop else step(eval.call)
        case ok: Ok     => onValue(ok.value)
        case err: Err   => onFailure(err.error)
        case Stop       => onStop()
      }
  }

  // one node: the next signal, or null when the loop suspended. The two inner matches are on
  // the sealed node sets: a node added later does not compile until it is handled there
  private def step(call: AsyncCall[Any]): Signal = call match {
    case null =>
      new Err(new NullPointerException("A user function returned a null AsyncCall"))
    case leaf: CallLeaf[Any] => stepLeaf(leaf)
    case op: CallOp[_, _]    => stepOp(op.asInstanceOf[CallOp[Any, Any]])
    case other =>
      new Err(new IllegalStateException(s"Not an AsyncCall node: ${other.getClass.getName}"))
  }

  private def stepLeaf(leaf: CallLeaf[Any]): Signal = leaf match {
    case SucceedCall(value) => new Ok(value)

    case FailedCall(error) => new Err(error)

    case DeferCall(thunk) => attempt(new Eval(thunk()))

    case FromFutureCall(f) =>
      if (!LateScope.enter(lateScopes))
        // an enclosing timeout is stopping this run: no request may start any more
        Stop
      else
        Try(f(ec)) match {
          case Failure(e) =>
            LateScope.exit(lateScopes)
            new Err(e)
          case Success(null) =>
            LateScope.exit(lateScopes)
            new Err(new NullPointerException("A future thunk returned null"))
          case Success(future) =>
            future.value match {
              case Some(outcome) =>
                LateScope.exit(lateScopes)
                signalOf(outcome)
              case None =>
                val suspension = suspend(Cancellable, NothingToCancel)
                future.onComplete { outcome =>
                  LateScope.exit(lateScopes)
                  suspension.complete(signalOf(outcome))
                }(Trampoline)
                checkCancelled(suspension)
                null
            }
        }

    case NativeCallLeaf(native) =>
      bridges.add(1)
      Try(native.start()) match {
        case Failure(e)   => new Err(e)
        case Success(run) =>
          // the native run's cancellation is not waited for (see the class comment)
          val suspension = suspend(
            Cancellable,
            () => {
              run.cancel()
              Nil
            }
          )
          run.result.onComplete(outcome => suspension.complete(signalOf(outcome)))(Trampoline)
          checkCancelled(suspension)
          null
      }

    case SleepCall(duration, scheduler) =>
      val timer = new AtomicReference[AsyncCancellable]()
      val suspension = suspend(
        Cancellable,
        () => {
          val scheduled = timer.get()
          if (scheduled != null) scheduled.cancel()
          Nil
        }
      )
      Try(scheduler.scheduleOnce(duration)(() => suspension.complete(new Ok(())))) match {
        case Success(scheduled) =>
          timer.set(scheduled)
          // a cancel that claimed the suspension before the timer was known
          if (cancelRequested) scheduled.cancel()
          checkCancelled(suspension)
        case Failure(e) =>
          suspension.complete(new Err(e))
      }
      null

    case TraverseCall(items, parallelism, f) =>
      Try(items.iterator.toVector) match {
        case Failure(e)                        => new Err(e)
        case Success(inputs) if inputs.isEmpty => new Ok(Vector.empty)
        case Success(inputs) =>
          val traversal =
            new Traversal(inputs, parallelism, f.asInstanceOf[Any => AsyncCall[Any]])
          val suspension = suspend(Cancellable, () => traversal.cancelAll())
          traversal.start(suspension)
          checkCancelled(suspension)
          null
      }
  }

  private def stepOp(op: CallOp[Any, Any]): Signal = op match {
    case MapCall(up, f) =>
      frames.push(new MapFrame(f.asInstanceOf[Any => Any]))
      new Eval(up)

    case FlatMapCall(up, f) =>
      frames.push(new FlatMapFrame(f.asInstanceOf[Any => AsyncCall[Any]]))
      new Eval(up)

    case RecoverCall(up, onFailure, _) =>
      frames.push(new RecoverFrame(onFailure.asInstanceOf[Throwable => AsyncCall[Any]]))
      new Eval(up)

    case TransformCall(up, f, _) =>
      frames.push(new TransformFrame(f.asInstanceOf[Try[Any] => AsyncCall[Any]]))
      new Eval(up)

    case GuaranteeCall(up, f, _) =>
      frames.push(new GuaranteeFrame(f))
      new Eval(up)

    case TimeoutCall(up, limit, error, onLate, onUnscheduled, scheduler) =>
      startTimeout(
        up,
        limit,
        error,
        onLate.asInstanceOf[Option[Try[Any] => Unit]],
        onUnscheduled,
        scheduler
      )
  }

  private def onValue(value: Any): Signal = frames.poll() match {
    case null =>
      promise.trySuccess(value)
      null
    case frame: MapFrame       => attempt(new Ok(frame.f(value)))
    case frame: FlatMapFrame   => attempt(new Eval(frame.f(value)))
    case _: RecoverFrame       => new Ok(value)
    case frame: TransformFrame => attempt(new Eval(frame.f(Success(value))))
    case frame: GuaranteeFrame => runHook(frame.f, Termination.Completed, new Ok(value))
  }

  private def onFailure(error: Throwable): Signal = frames.poll() match {
    case null =>
      promise.tryFailure(error)
      null
    case _: MapFrame | _: FlatMapFrame => new Err(error)
    case frame: RecoverFrame           => attempt(new Eval(frame.onFailure(error)))
    case frame: TransformFrame         => attempt(new Eval(frame.f(Failure(error))))
    case frame: GuaranteeFrame =>
      runHook(frame.f, Termination.Failed(error), new Err(error))
  }

  // unwinding a cancelled run: only the hooks run
  private def onStop(): Signal = frames.poll() match {
    case null =>
      promise.tryFailure(new RunCancelled)
      null
    case frame: GuaranteeFrame => runHook(frame.f, Termination.Cancelled, Stop)
    case _: MapFrame | _: FlatMapFrame | _: RecoverFrame | _: TransformFrame => Stop
  }

  // a hook's call runs as a child run nobody cancels (and outside the late scopes); `after`
  // goes on once it ended
  private def runHook(
    f: Termination => AsyncCall[Unit],
    termination: Termination,
    after: Signal
  ): Signal =
    Try(f(termination)) match {
      case Failure(e)              => afterHook(after, Failure(e))
      case Success(SucceedCall(_)) => after // a synchronous hook (onTerminate) - no child run
      case Success(hook) =>
        val suspension = suspend(Uncancellable, NothingToCancel)
        val child = new CallFiber(hook, ec, bridges, Nil)
        child.result.onComplete(outcome => suspension.complete(afterHook(after, outcome)))(
          Trampoline
        )
        child.start()
        null
    }

  private def afterHook(
    after: Signal,
    outcome: Try[Any]
  ): Signal = (after, outcome) match {
    case (_, Success(_))           => after
    case (_: Ok, Failure(failure)) => new Err(failure)
    case (err: Err, Failure(failure)) =>
      if (failure ne err.error) err.error.addSuppressed(failure)
      err
    // a cancelled run ends with its CancellationException
    case (_, Failure(_)) => after
  }

  // the upstream as a child run raced against a timer (see TimeoutCall). The timer's task
  // only claims the race; what follows runs on `ec`
  private def startTimeout(
    up: AsyncCall[Any],
    limit: FiniteDuration,
    error: () => Throwable,
    onLate: Option[Try[Any] => Unit],
    onUnscheduled: Throwable => Unit,
    scheduler: AsyncScheduler
  ): Signal = {
    val scope = onLate.map(_ => new LateScope)
    val child = new CallFiber(up, ec, bridges, scope.toList ::: lateScopes)
    val race = new AtomicInteger(Racing)
    val timer = new AtomicReference[AsyncCancellable]()

    val suspension = suspend(
      Cancellable,
      () => {
        val scheduled = timer.get()
        if (scheduled != null) scheduled.cancel()
        child.cancel()
        child :: Nil
      }
    )
    // cleared once the timeout's outcome went on: a loser left to finish keeps only `onLate`
    val waiting = new AtomicReference[Suspension](suspension)

    // the timeout's failure, built in the loop (the `error` thunk is a user function)
    def timedOut(): Unit = {
      val parent = waiting.getAndSet(null)
      if (parent != null)
        parent.complete(
          new Eval(DeferCall(() => FailedCall(CallCompiler.timeoutFailure(limit, error))))
        )
    }

    child.result.onComplete { outcome =>
      val scheduled = timer.get()
      if (scheduled != null) scheduled.cancel()

      if (race.compareAndSet(Racing, ChildEnded)) {
        val parent = waiting.getAndSet(null)
        if (parent != null) parent.complete(signalOf(outcome))
      } else {
        // the timer won. A loser that was stopped ended as cancelled; one that could not be
        // stopped - left to finish, or already past its last step - ended with an outcome of
        // its own: reported late, never lost
        outcome match {
          case Failure(_: RunCancelled) => ()
          case finished                 => onLate.foreach(f => ignoreFailure(f(finished)))
        }
        timedOut()
      }
    }(Trampoline)

    val onTimer: Runnable = new Runnable {
      override def run(): Unit =
        // a `Future` in flight below (it cannot be cancelled): the loser is left to finish;
        // otherwise nothing of it may start a request any more, and it is cancelled
        if (scope.exists(!_.lockIfIdle())) timedOut()
        else child.cancel()
    }

    try
      timer.set(scheduler.scheduleOnce(limit) { () =>
        if (race.compareAndSet(Racing, TimedOut))
          try ec.execute(onTimer)
          catch { case NonFatal(_) => onTimer.run() }
      })
    catch {
      // a timer the scheduler refuses: the call runs unlimited
      case NonFatal(e) => ignoreFailure(onUnscheduled(e))
    }

    child.start()
    checkCancelled(suspension)
    null
  }

  private def suspend(
    kind: Int,
    handle: () => List[CallFiber]
  ): Suspension = {
    val suspension = new Suspension(kind, handle)
    current.set(suspension)
    suspension
  }

  // after a suspension is set up: a cancel that came before it claims it now
  private def checkCancelled(suspension: Suspension): Unit =
    if (cancelRequested) suspension.cancel()

  /**
   * One asynchronous step's wait: resumed exactly once - by `complete` or, unless
   * uncancellable, by `cancel`. A cancel runs the handle - what cancels the step - and resumes
   * the loop once the child runs the handle returns have ended.
   */
  private final class Suspension(
    kind: Int,
    handle: () => List[CallFiber]
  ) {

    private val state = new AtomicInteger(Open)

    def complete(signal: Signal): Unit =
      if (state.compareAndSet(Open, Completed)) resume(signal)

    def cancel(): Unit =
      if (kind != Uncancellable && state.compareAndSet(Open, Cancelled)) {
        val children =
          try handle()
          catch { case NonFatal(_) => Nil }
        whenAllEnded(children)(() => resume(Stop))
      }
  }

  /**
   * `traverse`: child runs, at most `parallelism` at once, results by index. The first failure
   * decides: no further child starts, the ones still running are cancelled, and the failure
   * goes on once they ended.
   */
  private final class Traversal(
    inputs: Vector[Any],
    parallelism: Int,
    f: Any => AsyncCall[Any]
  ) {

    // guarded by `this`
    private val results = new Array[Any](inputs.size)
    private var nextIndex = 0
    private var remaining = inputs.size
    private var finished = false
    private val running = new java.util.HashSet[CallFiber]()
    private var suspension: Suspension = _

    def start(owner: Suspension): Unit = {
      val first = synchronized {
        suspension = owner
        nextIndex = math.min(parallelism, inputs.size)
        nextIndex
      }
      var i = 0
      while (i < first) {
        launch(i)
        i += 1
      }
    }

    /** Cancels the running children; they are returned to be waited for. */
    def cancelAll(): List[CallFiber] = {
      val children = synchronized(drainLocked())
      children.foreach(_.cancel())
      children
    }

    // runs on the run's ec: the parent's loop, or a child's completion (in its loop)
    private def launch(index: Int): Unit = {
      val call =
        try f(inputs(index))
        catch { case NonFatal(e) => FailedCall(e) }
      val child = new CallFiber(call, ec, bridges, lateScopes)
      val added = synchronized(!finished && running.add(child))
      if (added) {
        child.result.onComplete(outcome => ended(index, child, outcome))(Trampoline)
        child.start()
      }
    }

    private def ended(
      index: Int,
      child: CallFiber,
      outcome: Try[Any]
    ): Unit = {
      var next = -1
      var signal: Signal = null
      var toCancel: List[CallFiber] = Nil
      var owner: Suspension = null

      synchronized {
        running.remove(child)
        owner = suspension
        if (!finished) outcome match {
          case Success(value) =>
            results(index) = value
            remaining -= 1
            if (remaining == 0) {
              finished = true
              signal = new Ok(results.toVector)
            } else if (nextIndex < inputs.size) {
              next = nextIndex
              nextIndex += 1
            }
          case Failure(error) =>
            signal = new Err(error)
            toCancel = drainLocked()
        }
      }

      if (signal != null) {
        toCancel.foreach(_.cancel())
        val done = signal
        whenAllEnded(toCancel)(() => owner.complete(done))
      }
      if (next >= 0) launch(next)
    }

    private def drainLocked(): List[CallFiber] = {
      finished = true
      val children = List.newBuilder[CallFiber]
      val it = running.iterator()
      while (it.hasNext) children += it.next()
      running.clear()
      children.result()
    }
  }
}

private[wsclient] object CallFiber {

  // the loop's signals
  sealed abstract class Signal
  final class Eval(val call: AsyncCall[Any]) extends Signal
  final class Ok(val value: Any) extends Signal
  final class Err(val error: Throwable) extends Signal
  case object Stop extends Signal

  // the continuation stack's frames
  sealed abstract class Frame
  final class MapFrame(val f: Any => Any) extends Frame
  final class FlatMapFrame(val f: Any => AsyncCall[Any]) extends Frame
  final class RecoverFrame(val onFailure: Throwable => AsyncCall[Any]) extends Frame
  final class TransformFrame(val f: Try[Any] => AsyncCall[Any]) extends Frame
  final class GuaranteeFrame(val f: Termination => AsyncCall[Unit]) extends Frame

  // suspension kinds
  val Cancellable = 1
  val Uncancellable = 2

  // suspension states
  val Open = 0
  val Completed = 1
  val Cancelled = 2

  // timeout race states
  val Racing = 0
  val ChildEnded = 1
  val TimedOut = 2

  val NothingToCancel: () => List[CallFiber] = () => Nil

  /** How a cancelled run of this interpreter ends (told apart from a call's own failure). */
  final class RunCancelled extends CancellationException("The AsyncCall run was cancelled")

  def signalOf(outcome: Try[Any]): Signal = outcome match {
    case Success(value) => new Ok(value)
    case Failure(error) => new Err(error)
  }

  def attempt(signal: => Signal): Signal =
    try signal
    catch { case NonFatal(e) => new Err(e) }

  def ignoreFailure(body: => Unit): Unit =
    try body
    catch { case NonFatal(_) => () }

  /** Runs `continue` once every one of `fibers` ended - at once if there is none. */
  def whenAllEnded(fibers: List[CallFiber])(continue: () => Unit): Unit =
    if (fibers.isEmpty) continue()
    else {
      val left = new AtomicInteger(fibers.size)
      val once = new AtomicBoolean(false)
      fibers.foreach { fiber =>
        fiber.result.onComplete { _ =>
          if (left.decrementAndGet() == 0 && once.compareAndSet(false, true)) continue()
        }(Trampoline)
      }
    }
}
