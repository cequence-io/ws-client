package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.ZioCallSpec.Boom
import io.cequence.wsclient.stream.ZioInterop._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.{FiberRef, Task, Unsafe, ZIO}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CancellationException, ConcurrentLinkedQueue, TimeoutException}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/** AsyncCall compiled for ZIO (`toZIO`), and ZIO effects as calls (`toAsyncCall`). */
class ZioCallSpec extends AnyWordSpec with Matchers with ZioTestSupport {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def failure[A](call: AsyncCall[A]): Throwable =
    unsafeRun(call.toZIO.either)
      .fold(identity, value => fail(s"the call did not fail: $value"))

  /** A foreign native leaf (not ZIO): records starts and cancels. */
  private final class ForeignNative[A](value: Option[A]) extends NativeCall[A] {
    val started = new AtomicInteger()
    val cancelled = new AtomicInteger()

    override def start(): CallRun[A] = {
      started.incrementAndGet()
      val promise = Promise[A]()
      value.foreach(promise.success)
      new CallRun[A] {
        override def result: Future[A] = promise.future
        override def cancel(): Unit = {
          cancelled.incrementAndGet()
          promise.tryFailure(new CancellationException("cancelled"))
          ()
        }
      }
    }

    override def backend: String = "foreign"
  }

  "toZIO" should {

    "give back the same effect for a ZIO leaf" in {
      val effect: Task[Int] = ZIO.succeed(1)
      effect.toAsyncCall.toZIO should be theSameInstanceAs effect
    }

    "run operators on a ZIO leaf natively - no bridge" in {
      val call = ZIO
        .succeed(2)
        .toAsyncCall
        .map(_ * 10)
        .flatMap(n => AsyncCall.successful(n + 1))
        .recover { case _ => 0 }
        .attempt
      val (task, bridges) = CallDiagnostics.counted(call.toZIO)
      unsafeRun(task) shouldBe Right(21)
      bridges.get shouldBe 0
    }

    "count a Future leaf and a foreign native leaf as one bridge each" in {
      val (task, bridges) =
        CallDiagnostics.counted(AsyncCall.future(Future.successful(1)).map(_ + 1).toZIO)
      unsafeRun(task) shouldBe 2
      bridges.get shouldBe 1

      val foreign = new ForeignNative(Some(5))
      val (foreignTask, foreignBridges) =
        CallDiagnostics.counted(AsyncCall.fromNative(foreign).toZIO)
      unsafeRun(foreignTask) shouldBe 5
      foreignBridges.get shouldBe 1
    }

    "count a ZIO leaf run as a Future as one bridge" in {
      val (future, bridges) =
        CallDiagnostics.counted(ZIO.succeed(3).toAsyncCall.map(_ + 1).toFuture)
      Await.result(future, 10.seconds) shouldBe 4
      bridges.get shouldBe 1
    }

    "be cold and re-runnable" in {
      val runs = new AtomicInteger()
      val task = AsyncCall.defer {
        runs.incrementAndGet()
        ZIO.attempt(runs.incrementAndGet()).toAsyncCall
      }.map(_ => runs.incrementAndGet()).toZIO
      runs.get shouldBe 0
      unsafeRun(task)
      unsafeRun(task)
      runs.get shouldBe 6
    }

    "fail with a user function's exception - a failure, not a defect" in {
      val mapped = ZIO.succeed(1).toAsyncCall.map[Int](_ => throw new Boom("map"))
      unsafeRun(mapped.toZIO.either).left.map(_.getMessage) shouldBe Left("map")
      failure(
        AsyncCall.successful(1).flatMap[Int](_ => throw new Boom("flatMap"))
      ).getMessage shouldBe
        "flatMap"
      failure(AsyncCall.defer[Int](throw new Boom("defer"))).getMessage shouldBe "defer"
      failure(AsyncCall.future[Int](throw new Boom("thunk"))).getMessage shouldBe "thunk"
      failure(AsyncCall.successful(1).flatMap[Int](_ => null)) shouldBe a[NullPointerException]
    }

    "treat a ZIO leaf's defect as a failure for recover" in {
      val call =
        ZIO.die(new Boom("defect")).toAsyncCall.recover { case e: Boom => e.getMessage }
      unsafeRun(call.toZIO) shouldBe "defect"
    }

    "transform, attempt, map errors" in {
      unsafeRun(ZIO.fail(new Boom("e")).toAsyncCall.attempt.toZIO).left
        .map(_.getMessage) shouldBe
        Left("e")
      unsafeRun(
        ZIO
          .succeed(2)
          .toAsyncCall
          .transform {
            case Success(n) => Success(n * 2)
            case Failure(e) => Failure(e)
          }
          .toZIO
      ) shouldBe 4
      failure(
        ZIO.fail(new Boom("x")).toAsyncCall.mapError { case e: Boom =>
          new IllegalStateException(e)
        }
      ) shouldBe an[IllegalStateException]
    }

    "be stack-safe" in {
      val chained = (1 to 100000).foldLeft(AsyncCall.successful(0))(
        (
          call,
          _
        ) => call.map(_ + 1)
      )
      unsafeRun(chained.toZIO) shouldBe 100000

      def countDown(n: Int): AsyncCall[Int] =
        ZIO
          .succeed(n)
          .toAsyncCall
          .flatMap(k => if (k == 0) AsyncCall.successful(0) else countDown(k - 1))
      unsafeRun(countDown(200000).toZIO) shouldBe 0

      def retry(left: Int): AsyncCall[Int] =
        (if (left == 0) ZIO.succeed(0)
         else ZIO.fail(new Boom("again"))).toAsyncCall.recoverWith { case _: Boom =>
          retry(left - 1)
        }
      unsafeRun(retry(100000).toZIO) shouldBe 0
    }

    "run a ZIO leaf in the calling fiber: FiberRefs and log annotations reach it" in {
      val ref = Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make("outside"))
      val call = ref.get.toAsyncCall
        .map(_.toUpperCase)
        .flatMap(s => ref.get.toAsyncCall.map(s + "/" + _))
      unsafeRun(ref.locally("inside")(call.toZIO)) shouldBe "INSIDE/inside"

      val annotations = ZIO.logAnnotations.toAsyncCall.map(_.get("request-id"))
      unsafeRun(ZIO.logAnnotate("request-id", "r-1")(annotations.toZIO)) shouldBe Some("r-1")
    }

    "give fromFuture an ExecutionContext and run the future" in {
      val call = AsyncCall.fromFuture(runEc => Future(21)(runEc).map(_ * 2)(runEc))
      unsafeRun(call.toZIO) shouldBe 42
    }
  }

  "interruption" should {

    "interrupt a ZIO leaf, run no recovery, and show the hooks Cancelled" in {
      val interrupted = new AtomicBoolean(false)
      val recovered = new AtomicBoolean(false)
      val seen = new ConcurrentLinkedQueue[Termination]()
      // the fiber must be inside the leaf (and so past the hook's call) before the interrupt
      val started = new AtomicBoolean(false)
      val call = (ZIO.succeed(started.set(true)) *> ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.set(true)))
        .toAsyncCall
        .recover { case _ =>
          recovered.set(true)
        }
        .onTerminate(seen.add(_))
      val exit = unsafeRun(
        for {
          fiber <- call.toZIO.fork
          _ <- ZIO.attemptBlocking(eventually(started.get shouldBe true))
          exit <- fiber.interrupt
        } yield exit
      )
      exit.isInterrupted shouldBe true
      interrupted.get shouldBe true
      recovered.get shouldBe false
      seen.asScala.toList shouldBe List(Termination.Cancelled)
    }

    "cancel a foreign native leaf's run" in {
      val foreign = new ForeignNative[Int](None)
      unsafeRun(
        for {
          fiber <- AsyncCall.fromNative(foreign).toZIO.fork
          _ <- ZIO.succeed(eventually(foreign.started.get shouldBe 1))
          _ <- fiber.interrupt
        } yield ()
      )
      foreign.cancelled.get shouldBe 1
    }

    "interrupt a ZIO leaf run as a Future when the run is cancelled" in {
      val interrupted = new AtomicBoolean(false)
      val started = new AtomicBoolean(false)
      val run = (ZIO.succeed(started.set(true)) *> ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.set(true)))
        .toAsyncCall
        .run()
      eventually(started.get shouldBe true)
      run.cancel()
      a[CancellationException] should be thrownBy Await.result(run.result, 10.seconds)
      eventually(interrupted.get shouldBe true)
    }
  }

  "time" should {

    "sleep on ZIO's clock" in {
      val start = System.nanoTime()
      unsafeRun(AsyncCall.sleep(100.millis).toZIO)
      (System.nanoTime() - start).nanos should be >= 90.millis
    }

    "time out: interrupt the loser and fail with a TimeoutException" in {
      val interrupted = new AtomicBoolean(false)
      val lateCalled = new AtomicBoolean(false)
      val slow = ZIO.never.onInterrupt(ZIO.succeed(interrupted.set(true))).toAsyncCall
      failure(slow.timeout(50.millis)) shouldBe a[TimeoutException]
      interrupted.get shouldBe true

      failure(
        ZIO.never.toAsyncCall
          .timeout(20.millis, new Boom("limit"), _ => lateCalled.set(true), _ => ())
      ).getMessage shouldBe "limit"
      lateCalled.get shouldBe false

      unsafeRun(ZIO.succeed(1).toAsyncCall.timeout(5.seconds).toZIO) shouldBe 1
    }

    "wait for the upstream of an interrupted timeout: its hooks run first, innermost first" in {
      // ZIO's own `timeout` interrupts its loser in the background: the outer hook would run
      // before the inner one (found by the plan trees of verification's CallTreeSpec)
      (1 to 20).foreach { _ =>
        val order = new ConcurrentLinkedQueue[String]()
        val parked = new AtomicBoolean(false)
        val call = ZIO
          .succeed(parked.set(true))
          .zipRight(ZIO.never)
          .toAsyncCall
          .onTerminate(_ => { order.add("inner"); () })
          .timeout(1.minute)
          .onTerminate(_ => { order.add("outer"); () })
        unsafeRun(
          for {
            fiber <- call.toZIO.fork
            _ <- ZIO.succeed(()).repeatUntil(_ => parked.get)
            _ <- fiber.interrupt
          } yield ()
        )
        order.asScala.toList shouldBe List("inner", "outer")
      }
    }

    "fail with the default TimeoutException for a null error, and with what the error thunk throws" in {
      failure(ZIO.never.toAsyncCall.timeout(20.millis, null)).getMessage shouldBe
        "AsyncCall timed out after 20 milliseconds"
      failure(
        ZIO.never.toAsyncCall.timeout(20.millis, throw new Boom("thrown"))
      ).getMessage shouldBe "thrown"
    }

    "with onLate: leave a loser with a Future in flight to finish, and report it" in {
      // a Future cannot be cancelled: interrupting the fiber would only drop its outcome
      val upstream = Promise[Int]()
      val reached = new AtomicBoolean(false)
      val late = Promise[Try[Int]]()
      val call = AsyncCall.future {
        reached.set(true)
        upstream.future
      }.map(_ + 1)
        .timeout(200.millis, new Boom("limit"), outcome => late.success(outcome), _ => ())
      failure(call).getMessage shouldBe "limit"
      reached.get shouldBe true
      late.isCompleted shouldBe false
      upstream.success(1)
      Await.result(late.future, 10.seconds) shouldBe Success(2)
    }

    "with onLate: keep running in the calling fiber's context" in {
      val ref = Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make("outside"))
      val call = ref.get.toAsyncCall.timeout(5.seconds, new Boom("limit"), _ => (), _ => ())
      unsafeRun(ref.locally("inside")(call.toZIO)) shouldBe "inside"
    }

    "with onLate: interrupt the loser's fiber when the caller is interrupted" in {
      val interrupted = new AtomicBoolean(false)
      val started = new AtomicBoolean(false)
      val call = (ZIO.succeed(started.set(true)) *> ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.set(true)))
        .toAsyncCall
        .timeout(1.hour, new Boom("limit"), _ => (), _ => ())
      val exit = unsafeRun(
        for {
          fiber <- call.toZIO.fork
          _ <- ZIO.attemptBlocking(eventually(started.get shouldBe true))
          exit <- fiber.interrupt
        } yield exit
      )
      exit.isInterrupted shouldBe true
      interrupted.get shouldBe true
    }
  }

  "traverse" should {

    "keep the order and bound the parallelism" in {
      val inFlight = new AtomicInteger()
      val maxInFlight = new AtomicInteger()
      val call = AsyncCall.traverse(1 to 12, parallelism = 3) { n =>
        (ZIO.succeed {
          val now = inFlight.incrementAndGet()
          maxInFlight.accumulateAndGet(
            now,
            (
              a,
              b
            ) => math.max(a, b)
          )
        } *> ZIO.sleep(zio.Duration.fromMillis(((13 - n) * 3).toLong)) *>
          ZIO.succeed {
            inFlight.decrementAndGet()
            n * 10
          }).toAsyncCall
      }
      unsafeRun(call.toZIO) shouldBe (1 to 12).map(_ * 10)
      maxInFlight.get shouldBe 3
    }

    "fail fast and interrupt the others" in {
      val interrupted = new AtomicInteger()
      val call = AsyncCall.traverse(0 until 3, parallelism = 3) { i =>
        if (i == 2)
          (ZIO.sleep(zio.Duration.fromMillis(30)) *> ZIO.fail(new Boom("item 2"))).toAsyncCall
        else
          ZIO.never.onInterrupt(ZIO.succeed(interrupted.incrementAndGet())).toAsyncCall
      }
      failure(call).getMessage shouldBe "item 2"
      interrupted.get shouldBe 2
    }
  }

  "hooks" should {

    "see Completed and Failed, and run an asynchronous hook before the outcome" in {
      val seen = new ConcurrentLinkedQueue[Termination]()
      unsafeRun(ZIO.succeed(1).toAsyncCall.onTerminate(seen.add(_)).toZIO)
      failure(ZIO.fail(new Boom("f")).toAsyncCall.onTerminate(seen.add(_)))
      seen.asScala.toList.map(_.getClass.getSimpleName.stripSuffix("$")) shouldBe
        List("Completed", "Failed")

      val hookDone = new AtomicBoolean(false)
      val call = ZIO
        .succeed(1)
        .toAsyncCall
        .guaranteeCase(_ =>
          (ZIO.sleep(zio.Duration.fromMillis(30)) *> ZIO
            .succeed(hookDone.set(true))).toAsyncCall
        )
        .map(n => (n, hookDone.get))
      unsafeRun(call.toZIO) shouldBe ((1, true))
    }

    "fail a completed call with a failing hook, and suppress it on a failed one" in {
      failure(
        ZIO.succeed(1).toAsyncCall.onTerminate(_ => throw new Boom("hook"))
      ).getMessage shouldBe
        "hook"
      val failed = failure(
        ZIO
          .fail(new Boom("call"))
          .toAsyncCall
          .guaranteeCase(_ => AsyncCall.failed(new Boom("hook")))
      )
      failed.getMessage shouldBe "call"
      failed.getSuppressed.map(_.getMessage).toList shouldBe List("hook")
    }
  }
}

object ZioCallSpec {

  final class Boom(message: String) extends RuntimeException(message)
}
