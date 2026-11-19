package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.AsyncCallSpec.Boom
import io.cequence.wsclient.concurrent.{AsyncCancellable, AsyncScheduler}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{
  CancellationException,
  ConcurrentLinkedQueue,
  CountDownLatch,
  TimeUnit,
  TimeoutException
}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/** The `Future` (neutral) interpreter of AsyncCall. */
class AsyncCallSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  private def failure(call: AsyncCall[_]): Throwable =
    Try(await(call.toFuture)).failed
      .getOrElse(throw new AssertionError("the call did not fail"))

  /** A native leaf whose runs complete when the test says so; records starts and cancels. */
  private final class ControlledNative[A] extends NativeCall[A] {
    val started = new AtomicInteger()
    val cancelled = new AtomicInteger()
    val runs = new ConcurrentLinkedQueue[Promise[A]]()

    override def start(): CallRun[A] = {
      started.incrementAndGet()
      val promise = Promise[A]()
      runs.add(promise)
      new CallRun[A] {
        override def result: Future[A] = promise.future
        override def cancel(): Unit = {
          cancelled.incrementAndGet()
          promise.tryFailure(new CancellationException("native run cancelled"))
          ()
        }
      }
    }

    override def backend: String = "test"

    def completeAll(value: A): Unit = runs.asScala.foreach(_.trySuccess(value))
  }

  // a future completed later, on another thread
  private def later[A](
    value: A,
    delay: FiniteDuration
  ): Future[A] = {
    val promise = Promise[A]()
    AsyncScheduler.default.scheduleOnce(delay)(() => promise.success(value))
    promise.future
  }

  "an AsyncCall" should {

    "be cold and re-runnable: every thunk runs per run" in {
      val evaluated = new AtomicInteger()
      val call = AsyncCall.defer {
        evaluated.incrementAndGet()
        AsyncCall.successful(1)
      }.map { n =>
        evaluated.incrementAndGet()
        n + 1
      }.flatMap(n => AsyncCall.future(Future.successful(n * 10)))

      evaluated.get shouldBe 0
      await(call.toFuture) shouldBe 20
      await(call.toFuture) shouldBe 20
      evaluated.get shouldBe 4
    }

    "evaluate a future thunk once per run" in {
      val started = new AtomicInteger()
      val call = AsyncCall.future {
        started.incrementAndGet()
        Future.successful("x")
      }
      started.get shouldBe 0
      await(call.toFuture) shouldBe "x"
      await(call.toFuture) shouldBe "x"
      started.get shouldBe 2
    }

    "give fromFuture the run's ExecutionContext" in {
      val seen = new AtomicReference[Any]()
      await(AsyncCall.fromFuture { runEc =>
        seen.set(runEc)
        Future.successful(1)
      }.toFuture) shouldBe 1
      seen.get shouldBe ec
    }

    "compose values" in {
      await(AsyncCall.successful(2).zip(AsyncCall.successful("b")).toFuture) shouldBe ((
        2,
        "b"
      ))
      await(AsyncCall.fromTry(Success(3)).toFuture) shouldBe 3
      failure(AsyncCall.fromTry(Failure(new Boom("t")))).getMessage shouldBe "t"
      await(AsyncCall.unit.toFuture) shouldBe (())
    }

    "fail with a user function's exception, unwrapped" in {
      failure(
        AsyncCall.successful(1).map(_ => throw new Boom("map"))
      ).getMessage shouldBe "map"
      failure(
        AsyncCall.successful(1).flatMap[Int](_ => throw new Boom("flatMap"))
      ).getMessage shouldBe "flatMap"
      failure(AsyncCall.defer[Int](throw new Boom("defer"))).getMessage shouldBe "defer"
      failure(AsyncCall.future[Int](throw new Boom("thunk"))).getMessage shouldBe "thunk"
      failure(AsyncCall.future(Future.failed[Int](new Boom("future")))).getMessage shouldBe
        "future"
      failure(AsyncCall.successful(1).flatMap[Int](_ => null)) shouldBe a[NullPointerException]
    }

    "skip the success stages on a failure and recover" in {
      val mapped = new AtomicBoolean(false)
      val call = AsyncCall
        .failed[Int](new Boom("x"))
        .map { n =>
          mapped.set(true)
          n
        }
        .recover { case e: Boom => e.getMessage.length }
      await(call.toFuture) shouldBe 1
      mapped.get shouldBe false

      await(
        AsyncCall
          .failed[Int](new Boom("y"))
          .recoverWith { case _: Boom => AsyncCall.successful(7) }
          .toFuture
      ) shouldBe 7

      // not defined: the failure goes on
      failure(AsyncCall.failed[Int](new Boom("z")).recover { case _: IllegalStateException =>
        0
      }).getMessage shouldBe "z"
    }

    "map errors" in {
      val mapped = AsyncCall.failed[Int](new Boom("a")).mapError { case e: Boom =>
        new IllegalStateException(e.getMessage)
      }
      failure(mapped) shouldBe an[IllegalStateException]

      // a throw in pf is the new failure
      failure(
        AsyncCall.failed[Int](new Boom("b")).mapError { case _ => throw new Boom("pf") }
      ).getMessage shouldBe "pf"
    }

    "transform and attempt the outcome" in {
      await(AsyncCall.successful(1).attempt.toFuture) shouldBe Right(1)
      await(AsyncCall.failed[Int](new Boom("e")).attempt.toFuture).left
        .map(_.getMessage) shouldBe
        Left("e")
      await(
        AsyncCall
          .failed[Int](new Boom("e"))
          .transform {
            case Failure(e) => Success(e.getMessage)
            case Success(n) => Success(n.toString)
          }
          .toFuture
      ) shouldBe "e"
      await(
        AsyncCall
          .successful(2)
          .transformWith {
            case Success(n) => AsyncCall.successful(n * 2)
            case Failure(e) => AsyncCall.failed(e)
          }
          .toFuture
      ) shouldBe 4
    }

    "run user functions on the run's ec, also after a future completed elsewhere" in {
      val threads = new ConcurrentLinkedQueue[Boolean]()
      val call = AsyncCall
        .future(later(1, 20.millis))
        .map { n =>
          threads.add(onEcThread)
          n
        }
        .flatMap { n =>
          threads.add(onEcThread)
          AsyncCall.successful(n)
        }
        .recover { case _ => 0 }
      await(call.toFuture) shouldBe 1
      threads.asScala.toList shouldBe List(true, true)
    }

    "be stack-safe" in {
      // a deep spine
      val chained = (1 to 100000).foldLeft(AsyncCall.successful(0))(
        (
          call,
          _
        ) => call.map(_ + 1)
      )
      await(chained.toFuture) shouldBe 100000

      val flatMapped =
        (1 to 100000).foldLeft(AsyncCall.successful(0))(
          (
            call,
            _
          ) => call.flatMap(n => AsyncCall.successful(n + 1))
        )
      await(flatMapped.toFuture) shouldBe 100000

      // recursion through flatMap - a retry loop
      def countDown(n: Int): AsyncCall[Int] =
        AsyncCall
          .successful(n)
          .flatMap(k => if (k == 0) AsyncCall.successful(0) else countDown(k - 1))
      await(countDown(200000).toFuture) shouldBe 0

      // recursion through recoverWith, with asynchronous steps
      def retry(left: Int): AsyncCall[Int] =
        AsyncCall
          .future(if (left == 0) Future.successful(left) else Future.failed(new Boom("again")))
          .recoverWith { case _: Boom => retry(left - 1) }
      await(retry(20000).toFuture, 60.seconds) shouldBe 0
    }

    "count a native leaf as a bridge, a Future leaf as none" in {
      val (future, bridges) =
        CallDiagnostics.counted(AsyncCall.future(Future.successful(1)).map(_ + 1).toFuture)
      await(future) shouldBe 2
      bridges.get shouldBe 0

      val native = new ControlledNative[Int]
      val (nativeFuture, nativeBridges) =
        CallDiagnostics.counted(AsyncCall.fromNative(native).map(_ + 1).toFuture)
      eventually(native.started.get shouldBe 1)
      native.completeAll(1)
      await(nativeFuture) shouldBe 2
      nativeBridges.get shouldBe 1
    }

    "describe its plan" in {
      implicit val scheduler: AsyncScheduler = AsyncScheduler.default
      AsyncCall
        .fromNative(new ControlledNative[Int])
        .map(identity)
        .recoverWith { case _ => AsyncCall.successful(0) }
        .timeout(30.seconds)
        .describe shouldBe "native(test) -> map -> recoverWith -> timeout(30 seconds)"
      AsyncCall.future(Future.successful(1)).flatMap(AsyncCall.successful).describe shouldBe
        "future -> flatMap"
    }
  }

  "sleep and delay" should {

    "complete after the duration" in {
      val start = System.nanoTime()
      await(AsyncCall.sleep(100.millis).toFuture)
      (System.nanoTime() - start).nanos should be >= 90.millis
    }

    "start a delayed call only when its timer fires" in {
      val scheduler = new ManualScheduler
      val started = new AtomicInteger()
      val delayed = AsyncCall.defer {
        started.incrementAndGet()
        AsyncCall.successful(1)
      }.delay(1.hour)(scheduler)
      val run = delayed.toFuture
      eventually(scheduler.pending shouldBe true)
      started.get shouldBe 0
      scheduler.fire()
      await(run) shouldBe 1
      started.get shouldBe 1
    }

    "fail when the scheduler refuses the timer" in {
      val refusing = new AsyncScheduler {
        override def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable =
          throw new IllegalArgumentException("too long")
      }
      failure(AsyncCall.sleep(1.second)(refusing)) shouldBe an[IllegalArgumentException]
    }
  }

  "run().cancel()" should {

    "stop the run at its next step and fail it with a CancellationException" in {
      val after = new AtomicBoolean(false)
      val recovered = new AtomicBoolean(false)
      val promise = Promise[Int]()
      val run = AsyncCall
        .future(promise.future)
        .map { n =>
          after.set(true)
          n
        }
        .recover { case _ =>
          recovered.set(true)
          0
        }
        .run()
      Thread.sleep(50)
      run.cancel()
      a[CancellationException] should be thrownBy await(run.result)
      promise.success(1) // the abandoned future completes later: nothing runs
      Thread.sleep(50)
      after.get shouldBe false
      recovered.get shouldBe false // an interruption is not a failure
    }

    "cancel a native leaf in flight" in {
      val native = new ControlledNative[Int]
      val run = AsyncCall.fromNative(native).run()
      eventually(native.started.get shouldBe 1)
      run.cancel()
      run.cancel() // idempotent
      a[CancellationException] should be thrownBy await(run.result)
      native.cancelled.get shouldBe 1
    }

    "cancel a sleep's timer" in {
      val run = AsyncCall.sleep(1.hour).run()
      Thread.sleep(20)
      run.cancel()
      a[CancellationException] should be thrownBy await(run.result)
    }
  }

  "hooks" should {

    "see Completed, Failed and Cancelled exactly once" in {
      val seen = new ConcurrentLinkedQueue[Termination]()
      await(AsyncCall.successful(1).onTerminate(seen.add(_)).toFuture)
      failure(AsyncCall.failed[Int](new Boom("f")).onTerminate(seen.add(_)))
      // the run must have reached the hook's call (here: its leaf) before the cancel
      val reached = new CountDownLatch(1)
      val run = AsyncCall.future {
        reached.countDown()
        Promise[Int]().future
      }.onTerminate(seen.add(_)).run()
      reached.await(10, TimeUnit.SECONDS) shouldBe true
      run.cancel()
      a[CancellationException] should be thrownBy await(run.result)
      eventually(seen.size shouldBe 3)
      seen.asScala.toList.map(_.getClass.getSimpleName.stripSuffix("$")) shouldBe
        List("Completed", "Failed", "Cancelled")
    }

    "run an asynchronous hook before the outcome goes on" in {
      val hookDone = new AtomicBoolean(false)
      val call = AsyncCall
        .successful(1)
        .guaranteeCase(_ =>
          AsyncCall.future(later((), 50.millis)).map(_ => hookDone.set(true))
        )
        .map(n => (n, hookDone.get))
      await(call.toFuture) shouldBe ((1, true))
    }

    "fail a completed call with a failing hook, and suppress it on a failed one" in {
      failure(
        AsyncCall.successful(1).onTerminate(_ => throw new Boom("hook"))
      ).getMessage shouldBe "hook"

      val failed = failure(
        AsyncCall
          .failed[Int](new Boom("call"))
          .guaranteeCase(_ => AsyncCall.failed(new Boom("hook")))
      )
      failed.getMessage shouldBe "call"
      failed.getSuppressed.map(_.getMessage).toList shouldBe List("hook")
    }
  }

  "timeout" should {

    "let a call that is fast enough through" in {
      await(AsyncCall.future(later(1, 10.millis)).timeout(5.seconds).toFuture) shouldBe 1
    }

    "fail a slow call with a TimeoutException, and run nothing after its future" in {
      val after = new AtomicBoolean(false)
      val slow = AsyncCall.future(later(1, 300.millis)).map { n =>
        after.set(true)
        n
      }
      failure(slow.timeout(50.millis)) shouldBe a[TimeoutException]
      Thread.sleep(400)
      after.get shouldBe false
    }

    "fail with the given error" in {
      failure(
        AsyncCall.future(Promise[Int]().future).timeout(20.millis, new Boom("slow"))
      ).getMessage shouldBe "slow"
    }

    "cancel a native leaf in flight (the loser is interrupted)" in {
      val native = new ControlledNative[Int]
      val scheduler = new ManualScheduler
      val run = AsyncCall.fromNative(native).timeout(1.hour)(scheduler).toFuture
      eventually(native.started.get shouldBe 1)
      scheduler.fire()
      Try(await(run)).failed.toOption.map(_.getClass) shouldBe Some(classOf[TimeoutException])
      native.cancelled.get shouldBe 1
    }

    "fail with the default TimeoutException for a null error, and with what the error thunk throws" in {
      failure(
        AsyncCall.future(Promise[Int]().future).timeout(20.millis, null)
      ).getMessage shouldBe
        "AsyncCall timed out after 20 milliseconds"
      failure(
        AsyncCall.future(Promise[Int]().future).timeout(20.millis, throw new Boom("thrown"))
      ).getMessage shouldBe "thrown"
    }

    "evaluate the error thunk on the run's ec, also when the loser is left to finish" in {
      val scheduler = new ManualScheduler
      val reached = new CountDownLatch(1)
      val onEc = new AtomicBoolean(false)
      val run = AsyncCall.future {
        reached.countDown()
        Promise[Int]().future
      }.timeout(
        1.hour, {
          onEc.set(onEcThread)
          new Boom("limit")
        },
        _ => (),
        _ => ()
      )(scheduler)
        .toFuture
      reached.await(10, TimeUnit.SECONDS) shouldBe true
      scheduler.fire() // on the test's thread - like a scheduler's
      Try(await(run)).failed.toOption.map(_.getMessage) shouldBe Some("limit")
      onEc.get shouldBe true
    }

    "with onLate: leave a loser waiting for a Future to finish, its remaining steps included" in {
      val scheduler = new ManualScheduler
      val upstream = Promise[Int]()
      val reached = new CountDownLatch(1)
      val late = Promise[Try[Int]]()
      val run = AsyncCall.future {
        reached.countDown()
        upstream.future
      }.map(_ + 1)
        .timeout(1.hour, new Boom("limit"), outcome => late.success(outcome), _ => ())(
          scheduler
        )
        .toFuture
      reached.await(10, TimeUnit.SECONDS) shouldBe true
      scheduler.fire()
      Try(await(run)).failed.toOption.map(_.getMessage) shouldBe Some("limit")
      late.isCompleted shouldBe false
      upstream.success(1)
      await(late.future) shouldBe Success(2)
    }

    "with onLate: leave a traverse with Futures in flight to finish, and report it" in {
      val scheduler = new ManualScheduler
      val upstreams = Vector.fill(2)(Promise[Int]())
      val reached = new CountDownLatch(2)
      val late = Promise[Try[Seq[Int]]]()
      val run = AsyncCall
        .traverse(List(0, 1), 2) { i =>
          AsyncCall.future {
            reached.countDown()
            upstreams(i).future
          }
        }
        .timeout(1.hour, new Boom("limit"), outcome => late.success(outcome), _ => ())(
          scheduler
        )
        .toFuture
      reached.await(10, TimeUnit.SECONDS) shouldBe true
      scheduler.fire()
      Try(await(run)).failed.toOption.map(_.getMessage) shouldBe Some("limit")
      upstreams(0).success(10)
      upstreams(1).success(20)
      await(late.future) shouldBe Success(Seq(10, 20))
    }

    "with onLate: interrupt a native loser and not call onLate" in {
      val native = new ControlledNative[Int]
      val scheduler = new ManualScheduler
      val lateCalled = new AtomicBoolean(false)
      val run = AsyncCall
        .fromNative(native)
        .timeout(1.hour, new Boom("limit"), _ => lateCalled.set(true), _ => ())(scheduler)
        .toFuture
      eventually(native.started.get shouldBe 1)
      scheduler.fire()
      Try(await(run)).failed.toOption.map(_.getMessage) shouldBe Some("limit")
      native.cancelled.get shouldBe 1
      Thread.sleep(50)
      lateCalled.get shouldBe false
    }

    "with onLate: start no request once the loser is being stopped" in {
      // the loser is between two requests when its limit passes: the second never starts
      val scheduler = new ManualScheduler
      val native = new ControlledNative[Int]
      val secondStarted = new AtomicBoolean(false)
      val lateCalled = new AtomicBoolean(false)
      val run = AsyncCall
        .fromNative(native)
        .flatMap(_ =>
          AsyncCall.future {
            secondStarted.set(true)
            Future.successful(2)
          }
        )
        .timeout(1.hour, new Boom("limit"), _ => lateCalled.set(true), _ => ())(scheduler)
        .toFuture
      eventually(native.started.get shouldBe 1)
      scheduler.fire()
      Try(await(run)).failed.toOption.map(_.getMessage) shouldBe Some("limit")
      secondStarted.get shouldBe false
      lateCalled.get shouldBe false
    }

    "leave the call unlimited when the scheduler refuses the timer" in {
      val refusing = new AsyncScheduler {
        override def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable =
          throw new IllegalStateException("scheduler shut down")
      }
      val unscheduled = new AtomicReference[Throwable]()
      val call = AsyncCall
        .future(later(5, 50.millis))
        .timeout(1.millis, new Boom("never"), _ => (), e => unscheduled.set(e))(refusing)
      await(call.toFuture) shouldBe 5
      unscheduled.get shouldBe an[IllegalStateException]

      // without onUnscheduled: logged, unlimited
      await(
        AsyncCall.future(later(6, 50.millis)).timeout(1.millis)(refusing).toFuture
      ) shouldBe 6
    }

    "cancel a sleep it wraps" in {
      failure(AsyncCall.sleep(1.hour).timeout(20.millis)) shouldBe a[TimeoutException]
    }
  }

  "traverse" should {

    "keep the input order and bound the calls in flight" in {
      val inFlight = new AtomicInteger()
      val maxInFlight = new AtomicInteger()
      val call = AsyncCall.traverse(1 to 20, parallelism = 3) { n =>
        AsyncCall.defer {
          val now = inFlight.incrementAndGet()
          maxInFlight.accumulateAndGet(
            now,
            (
              a,
              b
            ) => math.max(a, b)
          )
          // later items finish first
          AsyncCall.future(later(n * 10, ((21 - n) * 3).millis)).map { v =>
            inFlight.decrementAndGet()
            v
          }
        }
      }
      await(call.toFuture) shouldBe (1 to 20).map(_ * 10)
      maxInFlight.get shouldBe 3
    }

    "run one at a time with parallelism 1" in {
      val order = new ConcurrentLinkedQueue[String]()
      val call = AsyncCall.traverse(List("a", "b", "c"), 1) { s =>
        AsyncCall.defer {
          order.add(s"start $s")
          AsyncCall.future(later(s, 10.millis)).map { v =>
            order.add(s"end $s")
            v
          }
        }
      }
      await(call.toFuture) shouldBe List("a", "b", "c")
      order.asScala.toList shouldBe List(
        "start a",
        "end a",
        "start b",
        "end b",
        "start c",
        "end c"
      )
    }

    "handle no items" in {
      await(AsyncCall.traverse(Nil, 4)(AsyncCall.successful[Int]).toFuture) shouldBe Nil
    }

    "fail fast: no further item starts, the running ones are cancelled" in {
      val natives = Vector.fill(3)(new ControlledNative[Int])
      val started = new AtomicInteger()
      val call = AsyncCall.traverse(0 until 10, parallelism = 3) { i =>
        started.incrementAndGet()
        if (i == 2)
          AsyncCall
            .future(later(0, 30.millis))
            .flatMap(_ => AsyncCall.failed[Int](new Boom("item 2")))
        else if (i < 3) AsyncCall.fromNative(natives(i))
        else AsyncCall.successful(i)
      }
      failure(call).getMessage shouldBe "item 2"
      started.get shouldBe 3
      eventually(natives(0).cancelled.get shouldBe 1)
      natives(1).cancelled.get shouldBe 1
    }

    "run the cancelled items' hooks before its failure goes on" in {
      val native = new ControlledNative[Int]
      val failing = Promise[Int]()
      val hookDone = new AtomicBoolean(false)
      val call = AsyncCall
        .traverse(List(0, 1), parallelism = 2) { i =>
          if (i == 0) AsyncCall.future(failing.future)
          else
            AsyncCall
              .fromNative(native)
              .guaranteeCase(_ =>
                AsyncCall.future(later((), 100.millis)).map(_ => hookDone.set(true))
              )
        }
        .map(_ => false)
        .recover { case _: Boom => hookDone.get }
      val run = call.toFuture
      eventually(native.started.get shouldBe 1)
      failing.failure(new Boom("item 0"))
      await(run) shouldBe true
    }

    "run a cancelled run's hooks before its result fails - across a timeout and a traverse" in {
      val reached = new CountDownLatch(2)
      val hooks = new AtomicInteger()
      val run = AsyncCall
        .traverse(List(0, 1), 2) { _ =>
          AsyncCall.future {
            reached.countDown()
            Promise[Int]().future
          }.guaranteeCase(_ =>
            AsyncCall.future(later((), 50.millis)).map { _ =>
              hooks.incrementAndGet()
              ()
            }
          )
        }
        .timeout(1.hour)(new ManualScheduler)
        .run()
      reached.await(10, TimeUnit.SECONDS) shouldBe true
      run.cancel()
      a[CancellationException] should be thrownBy await(run.result)
      hooks.get shouldBe 2
    }

    "fail with what the item function throws" in {
      failure(
        AsyncCall.traverse(List(1, 2), 2)(n =>
          if (n == 2) throw new Boom("f") else AsyncCall.successful(n)
        )
      ).getMessage shouldBe "f"
    }

    "require a positive parallelism" in {
      an[IllegalArgumentException] should be thrownBy AsyncCall.traverse(List(1), 0)(
        AsyncCall.successful[Int]
      )
    }
  }

}

object AsyncCallSpec {

  final class Boom(message: String) extends RuntimeException(message)
}
