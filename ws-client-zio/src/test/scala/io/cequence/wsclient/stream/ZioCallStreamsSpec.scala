package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.ZioCallStreamsSpec.Boom
import io.cequence.wsclient.stream.ZioInterop._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.stream.ZStream
import zio.{Exit, FiberRef, ZIO}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.{ExecutionContext, Future}

/**
 * The races of calls, and calls and streams together (`fromCall`, `unwrapCall`, `evalMapCall`,
 * `runFoldCall`, `preflightCall`), compiled for ZIO: natively, interrupted with their fiber.
 */
class ZioCallStreamsSpec extends AnyWordSpec with Matchers with ZioTestSupport {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def failure[A](call: AsyncCall[A]): Throwable =
    unsafeRun(call.toZIO.either)
      .fold(identity, value => fail(s"the call did not fail: $value"))

  /** A ZIO call that signals it started and then waits - recording that it was interrupted. */
  private final class Waiting {
    val started = new AtomicBoolean(false)
    val interrupted = new AtomicBoolean(false)
    val call: AsyncCall[Int] =
      ZIO
        .succeed(started.set(true))
        .zipRight(ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.set(true)))
        .toAsyncCall
  }

  "AsyncCall.firstCompletedOf on ZIO" should {

    "take the first outcome and interrupt the losers" in {
      val slow = new Waiting
      val (task, bridges) = CallDiagnostics.counted(
        AsyncCall
          .firstCompletedOf(
            Seq(slow.call, ZIO.sleep(zio.Duration.fromMillis(50)).as(9).toAsyncCall)
          )
          .toZIO
      )
      unsafeRun(task) shouldBe 9
      eventually(slow.interrupted.get shouldBe true)
      bridges.get shouldBe 0
    }

    "take a failure when it is first" in {
      val slow = new Waiting
      failure(
        AsyncCall.firstCompletedOf(Seq(slow.call, AsyncCall.failed[Int](new Boom("first"))))
      ).getMessage shouldBe "first"
      eventually(slow.interrupted.get shouldBe true)
    }

    "interrupt every call when the race is interrupted" in {
      val first = new Waiting
      val second = new Waiting
      unsafeRun(
        for {
          fiber <- AsyncCall.firstCompletedOf(Seq(first.call, second.call)).toZIO.fork
          _ <- ZIO.succeed(()).repeatUntil(_ => first.started.get && second.started.get)
          _ <- fiber.interrupt
        } yield ()
      )
      first.interrupted.get shouldBe true
      second.interrupted.get shouldBe true
    }

    "fail without calls" in {
      failure(AsyncCall.firstCompletedOf(Seq.empty[AsyncCall[Int]])) shouldBe a[
        NoSuchElementException
      ]
    }
  }

  "AsyncStream.fromCall on ZIO" should {

    "run a ZIO call natively - no bridge" in {
      val (stream, bridges) =
        StreamDiagnostics.counted(
          AsyncStream.fromCall(ZIO.succeed(4).toAsyncCall.map(_ + 1)).toZStream
        )
      unsafeRun(stream.runCollect).toList shouldBe List(5)
      bridges.get shouldBe 0
    }

    "interrupt the call when the stream is interrupted" in {
      val waiting = new Waiting
      unsafeRun(
        for {
          fiber <- AsyncStream.fromCall(waiting.call).toZStream.runDrain.fork
          _ <- ZIO.succeed(()).repeatUntil(_ => waiting.started.get)
          _ <- fiber.interrupt
        } yield ()
      )
      waiting.interrupted.get shouldBe true
    }

    "fail the stream with the call's failure" in {
      val result =
        unsafeRun(
          AsyncStream.fromCall(AsyncCall.failed[Int](new Boom("c"))).toZStream.runDrain.either
        )
      result.left.map(_.getMessage) shouldBe Left("c")
    }

    "count a Future call in it as a bridge" in {
      val (stream, bridges) = StreamDiagnostics.counted(
        AsyncStream.fromCall(AsyncCall.future(Future.successful(1))).toZStream
      )
      unsafeRun(stream.runCollect).toList shouldBe List(1)
      bridges.get shouldBe 1
    }
  }

  "AsyncStream.unwrapCall on ZIO" should {

    "run the call, then its (native) stream" in {
      val (stream, bridges) = StreamDiagnostics.counted(
        AsyncStream
          .unwrapCall(ZIO.succeed(ZStream(1, 2, 3).toAsyncStream).toAsyncCall)
          .toZStream
      )
      unsafeRun(stream.runCollect).toList shouldBe List(1, 2, 3)
      bridges.get shouldBe 0
    }
  }

  "AsyncStream.evalMapCall on ZIO" should {

    "run the callback in the stream's own fiber - its FiberRefs reach it - one at a time" in {
      val running = new AtomicInteger()
      val maxRunning = new AtomicInteger()
      val seen: ZIO[zio.Scope, Throwable, List[String]] =
        for {
          ref <- FiberRef.make("outside")
          stream = ZStream(1, 2, 3).toAsyncStream.evalMapCall { n =>
            (for {
              now <- ZIO.succeed(running.incrementAndGet())
              _ <- ZIO.succeed(maxRunning.updateAndGet(m => math.max(m, now)))
              value <- ref.get
              _ <- ZIO.sleep(zio.Duration.fromMillis(5))
              _ <- ZIO.succeed(running.decrementAndGet())
            } yield s"$value-$n").toAsyncCall
          }
          result <- ref.locally("inside")(stream.toZStream.runCollect)
        } yield result.toList
      unsafeRun(ZIO.scoped(seen)) shouldBe List("inside-1", "inside-2", "inside-3")
      maxRunning.get shouldBe 1
    }

    "count no bridge for ZIO calls" in {
      val (stream, bridges) = StreamDiagnostics.counted(
        ZStream(1, 2).toAsyncStream.evalMapCall(n => ZIO.succeed(n * 2).toAsyncCall).toZStream
      )
      unsafeRun(stream.runCollect).toList shouldBe List(2, 4)
      bridges.get shouldBe 0
    }

    "interrupt the call in flight with the stream" in {
      val waiting = new Waiting
      unsafeRun(
        for {
          fiber <- ZStream(1, 2).toAsyncStream
            .evalMapCall(_ => waiting.call)
            .toZStream
            .runDrain
            .fork
          _ <- ZIO.succeed(()).repeatUntil(_ => waiting.started.get)
          _ <- fiber.interrupt
        } yield ()
      )
      waiting.interrupted.get shouldBe true
    }
  }

  "AsyncStream.runFoldCall on ZIO" should {

    "run a ZIO stream natively - no bridge" in {
      val (call, bridges) = CallDiagnostics.counted(
        ZStream.range(1, 5).toAsyncStream.map(_ * 2).runFoldCall(0)(_ + _).toZIO
      )
      unsafeRun(call) shouldBe 20
      bridges.get shouldBe 0
    }

    "evaluate zero once per run, and fail with a throw in it or in the function" in {
      val call = ZStream(1, 2).toAsyncStream.runToSeqCall.toZIO
      unsafeRun(call) shouldBe Seq(1, 2)
      unsafeRun(call) shouldBe Seq(1, 2)

      failure(
        ZStream(1).toAsyncStream.runFoldCall[Int](throw new Boom("zero"))(_ + _)
      ).getMessage shouldBe "zero"
      failure(
        ZStream(1, 2).toAsyncStream.runFoldCall(0)(
          (
            _,
            a
          ) => if (a == 2) throw new Boom("f") else a
        )
      ).getMessage shouldBe "f"
    }

    "interrupt the stream when the call is interrupted" in {
      val interrupted = new AtomicBoolean(false)
      val started = new AtomicBoolean(false)
      val stream = ZStream
        .fromZIO(ZIO.succeed(started.set(true)))
        .zipRight(ZStream.never)
        .ensuring(ZIO.succeed(interrupted.set(true)))
      unsafeRun(
        for {
          fiber <- stream.toAsyncStream.runDrainCall.toZIO.fork
          _ <- ZIO.succeed(()).repeatUntil(_ => started.get)
          _ <- fiber.interrupt
        } yield ()
      )
      interrupted.get shouldBe true
    }

    "give the same results for a bridged (non-ZIO) stream" in {
      val (call, bridges) =
        CallDiagnostics.counted(AsyncStream(1, 2, 3).runFoldCall(0)(_ + _).toZIO)
      unsafeRun(call) shouldBe 6
      bridges.get shouldBe 0 // an `Emits` leaf is compiled natively by ZIO as well
    }
  }

  "AsyncStream.preflightCall on ZIO" should {

    "preflight a native stream natively: no bridge, one run, the first element replayed" in {
      val released = new AtomicBoolean(false)
      val stream = ZStream(1, 2, 3).ensuring(ZIO.succeed(released.set(true))).toAsyncStream
      val (call, bridges) = CallDiagnostics.counted(stream.preflightCall.toZIO)
      val preflighted = unsafeRun(call)
      bridges.get shouldBe 0
      preflighted.describe shouldBe "native(zio)"
      unsafeRun(preflighted.toZStream.runCollect).toList shouldBe List(1, 2, 3)
      released.get shouldBe true
      // single-shot
      unsafeRun(preflighted.toZStream.runCollect.either).isLeft shouldBe true
    }

    "fail with the stream's own exception before its first element" in {
      val stream = ZStream.fail(new Boom("before")).toAsyncStream
      failure(stream.preflightCall).getMessage shouldBe "before"
    }

    "fail - not wait for ever - when a finalizer dies while the preflight's scope closes" in {
      // the daemon fiber ends with the defect before it delivered anything
      val stream =
        ZStream.fail(new Boom("before")).ensuring(ZIO.die(new Boom("finalizer"))).toAsyncStream
      val outcome = unsafeRun(
        stream.preflightCall.toZIO.either.timeout(zio.Duration.fromSeconds(10))
      )
      outcome.fold(fail("the call waited for ever"))(
        _.fold(
          e => {
            e shouldBe a[Boom]
            e.getMessage shouldBe "finalizer"
          },
          value => fail(s"the call did not fail: $value")
        )
      )
    }

    "abort the request - release the stream - when interrupted before the first element" in {
      val started = new AtomicBoolean(false)
      val released = new AtomicBoolean(false)
      val stream = ZStream
        .fromZIO(ZIO.succeed(started.set(true)))
        .zipRight(ZStream.never)
        .ensuring(ZIO.succeed(released.set(true)))
        .toAsyncStream
      unsafeRun(
        for {
          fiber <- stream.preflightCall.toZIO.fork
          _ <- ZIO.succeed(()).repeatUntil(_ => started.get)
          _ <- fiber.interrupt
        } yield ()
      )
      eventually(released.get shouldBe true)
    }

    "release a handed-over run that nobody got: interrupt right after the first element" in {
      // many tries: whichever side wins the race, nothing may stay held
      (1 to 50).foreach { _ =>
        val opened = new AtomicBoolean(false)
        val released = new AtomicBoolean(false)
        // released iff opened: an interrupt before the stream was opened leaves nothing to release
        val stream = ZStream
          .acquireReleaseWith(ZIO.succeed(opened.set(true)))(_ =>
            ZIO.succeed(released.set(true))
          )
          .flatMap(_ => ZStream(1, 2))
          .toAsyncStream
        unsafeRun(
          for {
            fiber <- stream.preflightCall.toZIO.fork
            _ <- ZIO.yieldNow
            exit <- fiber.interrupt
            _ <- exit match {
              case Exit.Success(handedOver) =>
                // it completed first: the stream is ours - cancel it by running nothing
                handedOver.toZStream.take(1).runDrain
              case Exit.Failure(_) => ZIO.unit
            }
          } yield ()
        )
        eventually(released.get shouldBe opened.get)
      }
    }
  }

  "the bridge counter of a run" should {

    // a stream with a foreign (Future) leaf behind a native ZIO leaf, compiled by the native
    // preflight INSIDE a deferred call - at run time, on whatever thread runs the thunk
    def withFutureLeaf: AsyncStream[Int] =
      ZStream(1, 2).toAsyncStream ++ AsyncStream.futureStream(
        Future.successful(AsyncStream.single(3))
      )

    "reach a stream compiled in a deferred call, on ZIO" in {
      val (call, bridges) =
        CallDiagnostics.counted(AsyncCall.defer(withFutureLeaf.preflightCall).toZIO)
      unsafeRun(call.flatMap(_.toZStream.runCollect)).toList shouldBe List(1, 2, 3)
      bridges.get should be >= 1L // the `Future` leaf, counted when the stream reached it
    }

    "reach a stream compiled in a deferred call, on the Future interpreter" in {
      val (run, bridges) =
        CallDiagnostics.counted(AsyncCall.defer(withFutureLeaf.preflightCall).run())
      val preflighted = scala.concurrent.Await.result(
        run.result,
        scala.concurrent.duration.DurationInt(10).seconds
      )
      unsafeRun(preflighted.toZStream.runCollect).toList shouldBe List(1, 2, 3)
      // the ZIO leaf of the call is foreign to this interpreter (1), the `Future` leaf in its
      // stream is foreign to ZIO (1)
      bridges.get should be >= 2L
    }
  }

  "AsyncStream.preflightCall of a non-ZIO stream on ZIO" should {

    "cancel the request when interrupted before the first element" in {
      val subscribed = new AtomicBoolean(false)
      val cancelled = new AtomicBoolean(false)
      val publisher = new java.util.concurrent.Flow.Publisher[Int] {
        override def subscribe(s: java.util.concurrent.Flow.Subscriber[_ >: Int]): Unit = {
          subscribed.set(true)
          s.onSubscribe(new java.util.concurrent.Flow.Subscription {
            override def request(n: Long): Unit = ()
            override def cancel(): Unit = cancelled.set(true)
          })
        }
      }
      val (call, bridges) =
        CallDiagnostics.counted(AsyncStream.fromPublisher(() => publisher).preflightCall.toZIO)
      unsafeRun(
        for {
          fiber <- call.fork
          _ <- ZIO.succeed(()).repeatUntil(_ => subscribed.get)
          _ <- fiber.interrupt
        } yield ()
      )
      eventually(cancelled.get shouldBe true)
      bridges.get should be >= 1L
    }
  }

  "the neutral interpreter on ZIO leaves" should {

    "run runFoldCall of a ZIO stream as a Future - one bridge - and cancel it" in {
      val interrupted = new AtomicBoolean(false)
      val started = new AtomicBoolean(false)
      val stream = ZStream
        .fromZIO(ZIO.succeed(started.set(true)))
        .zipRight(ZStream.never)
        .ensuring(ZIO.succeed(interrupted.set(true)))
      val (run, bridges) = CallDiagnostics.counted(stream.toAsyncStream.runDrainCall.run())
      eventually(started.get shouldBe true)
      run.cancel()
      eventually(interrupted.get shouldBe true)
      bridges.get should be >= 1L
    }
  }
}

object ZioCallStreamsSpec {

  final class Boom(message: String) extends RuntimeException(message)
}
