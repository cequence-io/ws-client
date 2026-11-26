package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.CequenceWSException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CancellationException, ConcurrentLinkedQueue}
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
 * The races of calls (`AsyncCall.firstCompletedOf`) and the places where streams and calls
 * meet (`fromCall`, `unwrapCall`, `evalMapCall`, `runFoldCall`, `preflightCall`, ...), on the
 * `Future` interpreter.
 */
class CallStreamsSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  import CallStreamsSpec.Boom

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

  private def failure(call: AsyncCall[_]): Throwable =
    Try(await(call.toFuture)).failed
      .getOrElse(throw new AssertionError("the call did not fail"))

  "AsyncCall.firstCompletedOf" should {

    "complete with the first outcome - a value or a failure" in {
      val slow = Promise[Int]()
      val fast = AsyncCall.successful(1)
      await(
        AsyncCall.firstCompletedOf(Seq(AsyncCall.future(slow.future), fast)).toFuture
      ) shouldBe 1

      val failing = AsyncCall.failed[Int](new Boom("first"))
      failure(
        AsyncCall.firstCompletedOf(Seq(AsyncCall.future(slow.future), failing))
      ).getMessage shouldBe "first"
    }

    "fail without calls" in {
      failure(AsyncCall.firstCompletedOf(Seq.empty[AsyncCall[Int]])) shouldBe a[
        NoSuchElementException
      ]
    }

    "cancel the losers - a native leaf is cancelled, a Future runs on" in {
      val native = new ControlledNative[Int]
      val loserFuture = Promise[Int]()
      val winner = Promise[Int]()
      val race = AsyncCall.firstCompletedOf(
        Seq(
          AsyncCall.fromNative(native),
          AsyncCall.future(loserFuture.future),
          AsyncCall.future(winner.future)
        )
      )
      val result = race.toFuture
      eventually(native.started.get shouldBe 1)
      winner.success(7)
      await(result) shouldBe 7
      native.cancelled.get shouldBe 1
      // a Future cannot be cancelled: nothing stops it, nothing waits for it
      loserFuture.isCompleted shouldBe false
    }

    "run the hooks of the cancelled losers before it completes" in {
      val hookRan = new AtomicInteger()
      val native = new ControlledNative[Int]
      val loser = AsyncCall
        .fromNative(native)
        .guaranteeCase(termination =>
          AsyncCall.successful(()).map { _ =>
            if (termination == Termination.Cancelled) hookRan.incrementAndGet()
            ()
          }
        )
      val winner = Promise[Int]()
      val result =
        AsyncCall.firstCompletedOf(Seq(loser, AsyncCall.future(winner.future))).toFuture
      eventually(native.started.get shouldBe 1)
      winner.success(1)
      await(result) shouldBe 1
      hookRan.get shouldBe 1
    }

    "be cold and re-runnable" in {
      val started = new AtomicInteger()
      val race = AsyncCall.firstCompletedOf(
        Seq(
          AsyncCall.defer {
            started.incrementAndGet()
            AsyncCall.successful(1)
          },
          AsyncCall.future(Promise[Int]().future)
        )
      )
      started.get shouldBe 0
      await(race.toFuture) shouldBe 1
      await(race.toFuture) shouldBe 1
      started.get shouldBe 2
    }

    "cancel every call when the race itself is cancelled" in {
      val first = new ControlledNative[Int]
      val second = new ControlledNative[Int]
      val run = AsyncCall
        .firstCompletedOf(Seq(AsyncCall.fromNative(first), AsyncCall.fromNative(second)))
        .run()
      eventually {
        first.started.get shouldBe 1
        second.started.get shouldBe 1
      }
      run.cancel()
      a[CancellationException] should be thrownBy await(run.result)
      first.cancelled.get shouldBe 1
      second.cancelled.get shouldBe 1
    }
  }

  "AsyncStream.fromCall" should {

    "emit the call's value and end" in {
      run(AsyncStream.fromCall(AsyncCall.successful(5))) shouldBe Seq(5)
    }

    "fail with the call's failure" in {
      runFailure(
        AsyncStream.fromCall(AsyncCall.failed[Int](new Boom("call")))
      ).getMessage shouldBe
        "call"
    }

    "run the call once per run of the stream" in {
      val runs = new AtomicInteger()
      val stream = AsyncStream.fromCall(AsyncCall.defer {
        runs.incrementAndGet()
        AsyncCall.successful(1)
      })
      runs.get shouldBe 0
      run(stream)
      run(stream)
      runs.get shouldBe 2
    }

    "cancel the call when the run is cancelled" in {
      val native = new ControlledNative[Int]
      val streamRun = AsyncStream.fromCall(AsyncCall.fromNative(native)).run(_ => ())
      eventually(native.started.get shouldBe 1)
      streamRun.cancel()
      await(streamRun.done)
      native.cancelled.get shouldBe 1
    }

    "be an element like any other downstream" in {
      run(
        AsyncStream.fromCall(AsyncCall.successful(2)).concat(AsyncStream.single(3)).map(_ * 10)
      ) shouldBe Seq(20, 30)
    }
  }

  "AsyncStream.unwrapCall" should {

    "run the call first, then its stream" in {
      val order = new ConcurrentLinkedQueue[String]()
      val stream = AsyncStream.unwrapCall(AsyncCall.defer {
        order.add("call")
        AsyncCall.successful(AsyncStream.defer {
          order.add("stream")
          AsyncStream(1, 2, 3)
        })
      })
      order.size shouldBe 0
      run(stream) shouldBe Seq(1, 2, 3)
      order.asScala.toList shouldBe List("call", "stream")
    }

    "fail with the call's failure, and open no stream" in {
      val opened = new AtomicInteger()
      val stream = AsyncStream.unwrapCall(
        AsyncCall.failed[AsyncStream[Int]](new Boom("start")).recoverWith { case e =>
          opened.incrementAndGet()
          AsyncCall.failed(e)
        }
      )
      runFailure(stream).getMessage shouldBe "start"
      opened.get shouldBe 1
    }
  }

  "AsyncStream.evalMapCall" should {

    "evaluate one call at a time, in order" in {
      val running = new AtomicInteger()
      val maxRunning = new AtomicInteger()
      val stream = AsyncStream(1, 2, 3, 4).evalMapCall { n =>
        AsyncCall.defer {
          val now = running.incrementAndGet()
          maxRunning.updateAndGet(m => math.max(m, now))
          AsyncCall.future(Future { Thread.sleep(10); n * 10 }).map { r =>
            running.decrementAndGet()
            r
          }
        }
      }
      run(stream) shouldBe Seq(10, 20, 30, 40)
      maxRunning.get shouldBe 1
    }

    "fail with a failed call, or a throw in the function" in {
      runFailure(
        AsyncStream(1, 2, 3).evalMapCall(n =>
          if (n == 2) AsyncCall.failed[Int](new Boom("call")) else AsyncCall.successful(n)
        )
      ).getMessage shouldBe "call"

      runFailure(
        AsyncStream(1, 2, 3).evalMapCall[Int](n =>
          if (n == 2) throw new Boom("function") else AsyncCall.successful(n)
        )
      ).getMessage shouldBe "function"
    }

    "cancel the call in flight when the run is cancelled" in {
      val native = new ControlledNative[Int]
      val streamRun =
        AsyncStream(1, 2).evalMapCall(_ => AsyncCall.fromNative(native)).run(_ => ())
      eventually(native.started.get shouldBe 1)
      streamRun.cancel()
      await(streamRun.done)
      native.cancelled.get shouldBe 1
      native.started.get shouldBe 1
    }
  }

  "AsyncStream.runFoldCall" should {

    "fold the stream per run" in {
      val call = AsyncStream(1, 2, 3).runFoldCall(10)(_ + _)
      await(call.toFuture) shouldBe 16
      await(call.toFuture) shouldBe 16
    }

    "evaluate zero once per run - a mutable accumulator is not shared" in {
      val call =
        AsyncStream(1, 2)
          .runFoldCall(Vector.newBuilder[Int])(
            (
              b,
              a
            ) => b += a
          )
          .map(_.result())
      await(call.toFuture) shouldBe Vector(1, 2)
      await(call.toFuture) shouldBe Vector(1, 2)
    }

    "fail with a throw in zero or in the function" in {
      failure(
        AsyncStream(1).runFoldCall[Int](throw new Boom("zero"))(_ + _)
      ).getMessage shouldBe "zero"
      failure(
        AsyncStream(1, 2).runFoldCall(0)(
          (
            _,
            a
          ) => if (a == 2) throw new Boom("f") else a
        )
      ).getMessage shouldBe "f"
    }

    "fail with the stream's failure" in {
      failure(
        AsyncStream.failed[Int](new Boom("stream")).runFoldCall(0)(_ + _)
      ).getMessage shouldBe "stream"
    }

    "cancel the stream's run when the call is cancelled" in {
      val publisher = new TrackedPublisher[Int](Seq(1), hangAtEnd = true)
      val run = publisher.stream.runFoldCall(0)(_ + _).run()
      eventually(publisher.subscriptions.get shouldBe 1)
      run.cancel()
      a[CancellationException] should be thrownBy await(run.result)
      eventually(publisher.cancellations.get shouldBe 1)
    }

    "give runToSeqCall and runDrainCall" in {
      await(AsyncStream(3, 1, 2).runToSeqCall.toFuture) shouldBe Seq(3, 1, 2)
      val seen = new AtomicInteger()
      await(AsyncStream(1, 2, 3).map { n => seen.addAndGet(n); n }.runDrainCall.toFuture)
      seen.get shouldBe 6
    }

    "be cold: nothing runs until the call is" in {
      val publisher = new TrackedPublisher[Int](Seq(1, 2))
      val call = publisher.stream.runToSeqCall
      publisher.subscriptions.get shouldBe 0
      await(call.toFuture) shouldBe Seq(1, 2)
      publisher.subscriptions.get shouldBe 1
    }
  }

  "Bytes.runToBytesCall" should {

    "collect the bytes, up to the limit" in {
      val stream = AsyncStream(Bytes(Array[Byte](1, 2)), Bytes(Array[Byte](3)))
      await(stream.runToBytesCall().toFuture).toArray.toList shouldBe List[Byte](1, 2, 3)
      failure(stream.runToBytesCall(2)) shouldBe a[CequenceWSException]
      failure(stream.runToBytesCall(-1)) shouldBe an[IllegalArgumentException]
    }
  }

  "AsyncStream.preflightCall" should {

    "complete once the first element arrived, with a single-run stream" in {
      val publisher = new TrackedPublisher[Int](Seq(1, 2, 3))
      val call = publisher.stream.preflightCall
      publisher.subscriptions.get shouldBe 0

      val preflighted = await(call.toFuture)
      publisher.subscriptions.get shouldBe 1
      run(preflighted) shouldBe Seq(1, 2, 3)
      failure(preflighted.runToSeqCall) shouldBe an[IllegalStateException]
    }

    "fail with the stream's failure before its first element" in {
      val reason = new AtomicReference[Throwable]()
      val call = AsyncStream.failed[Int](new Boom("before")).preflightCall
      reason.set(failure(call))
      reason.get.getMessage shouldBe "before"
    }

    "cancel the request when the call is cancelled before the first element" in {
      val publisher = new TrackedPublisher[Int](Seq.empty, hangAtEnd = true)
      val running = publisher.stream.preflightCall.run()
      val deadline = System.nanoTime() + 10000000000L
      while (publisher.subscriptions.get == 0 && System.nanoTime() < deadline) Thread.sleep(5)
      publisher.subscriptions.get shouldBe 1
      running.cancel()
      while (publisher.cancellations.get == 0 && System.nanoTime() < deadline) Thread.sleep(5)
      publisher.cancellations.get shouldBe 1
    }

    "send a new request per run of the call" in {
      val publisher = new TrackedPublisher[Int](Seq(1))
      val call = publisher.stream.preflightCall
      run(await(call.toFuture))
      run(await(call.toFuture))
      publisher.subscriptions.get shouldBe 2
    }

    "send no request at all when the call is cancelled while its start is still queued" in {
      // an execution context the test drives task by task: the preflight's start sits in the
      // queue (a busy pool) when the call is cancelled
      val queue = new java.util.concurrent.ConcurrentLinkedQueue[Runnable]()
      val manual = new scala.concurrent.ExecutionContext {
        override def execute(task: Runnable): Unit = { queue.add(task); () }
        override def reportFailure(cause: Throwable): Unit = ()
      }
      def drain(): Unit = {
        var task = queue.poll()
        while (task != null) { task.run(); task = queue.poll() }
      }

      val publisher = new TrackedPublisher[Int](Seq(1), hangAtEnd = true)
      val running = publisher.stream.preflightCall.run()(manual)
      queue.poll().run() // the call's own loop: it queues the preflight's start and suspends
      queue.size shouldBe 1
      running.cancel()
      drain()

      Try(await(running.result)).failed.get shouldBe a[CancellationException]
      publisher.subscriptions.get shouldBe 0
    }
  }
}

object CallStreamsSpec {

  final class Boom(message: String) extends RuntimeException(message)
}
