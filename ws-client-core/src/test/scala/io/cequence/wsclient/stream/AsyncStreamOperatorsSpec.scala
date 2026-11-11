package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.AsyncStream.Step
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

class AsyncStreamOperatorsSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  private val ints = AsyncStream(1, 2, 3, 4, 5)

  "the element operators" should {

    "apply in order" in {
      run(ints.map(_ * 10)) shouldBe Seq(10, 20, 30, 40, 50)
      run(ints.mapConcat(i => List(i, -i))) shouldBe Seq(1, -1, 2, -2, 3, -3, 4, -4, 5, -5)
      run(ints.filter(_ % 2 == 1)) shouldBe Seq(1, 3, 5)
      run(ints.filterNot(_ % 2 == 1)) shouldBe Seq(2, 4)
      run(ints.collect { case i if i > 3 => s"#$i" }) shouldBe Seq("#4", "#5")
      run(ints.take(2)) shouldBe Seq(1, 2)
      run(ints.take(10)) shouldBe Seq(1, 2, 3, 4, 5)
      run(ints.takeWhile(_ < 3)) shouldBe Seq(1, 2)
      run(ints.takeWhile(_ < 3, inclusive = true)) shouldBe Seq(1, 2, 3)
      run(ints.drop(3)) shouldBe Seq(4, 5)
      run(ints.drop(0)) shouldBe Seq(1, 2, 3, 4, 5)
      run(ints.scan(0)(_ + _)) shouldBe Seq(0, 1, 3, 6, 10, 15)
      run(AsyncStream.empty[Int].scan(7)(_ + _)) shouldBe Seq(7)
      run(ints.fold(0)(_ + _)) shouldBe Seq(15)
      run(AsyncStream.empty[Int].fold(42)(_ + _)) shouldBe Seq(42)
      run(ints.zipWithIndex.take(2)) shouldBe Seq((1, 0L), (2, 1L))
      run(ints.evalMap(i => Future(i + 1))) shouldBe Seq(2, 3, 4, 5, 6)
      run(ints.evalMap(i => Future.successful(i))) shouldBe Seq(1, 2, 3, 4, 5)
      run(ints.statefulMapConcat { () =>
        var sum = 0
        i => {
          sum += i
          List(sum)
        }
      }) shouldBe Seq(1, 3, 6, 10, 15)
    }

    "compose" in {
      run(ints ++ AsyncStream(6, 7)) shouldBe (1 to 7)
      run(
        AsyncStream.empty[Int] ++ AsyncStream.empty[Int] ++ AsyncStream.single(1)
      ) shouldBe Seq(1)
      run(ints.flatMapConcat(i => AsyncStream.fromIterable(List.fill(i)(i)))) shouldBe
        Seq(1, 2, 2, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 5)
      run(ints.flatMapConcat(_ => AsyncStream.empty[Int])) shouldBe Nil
      run(ints.via(AsyncPipe.of[Int].map(_ + 1).filter(_ > 3))) shouldBe Seq(4, 5, 6)
      run(ints.via(AsyncPipe.identity)) shouldBe Seq(1, 2, 3, 4, 5)
    }

    "run each mapConcat function eagerly when its element arrives" in {
      val seen = new ConcurrentLinkedQueue[String]()
      val stream = AsyncStream(1, 2).mapConcat { i =>
        seen.add(s"f($i)")
        List(i, i)
      }.map { i =>
        seen.add(s"out($i)")
        i
      }
      run(stream)
      seen.asScala.toList shouldBe List("f(1)", "out(1)", "out(1)", "f(2)", "out(2)", "out(2)")
    }

    "zip a 1:1 pipe's outputs with their elements" in {
      val pipe = AsyncPipe.of[Int].map(_ * 100)
      run(
        ints.zipVia(i => i + 1, pipe)(
          (
            i,
            y
          ) => (i, y)
        )
      ) shouldBe
        Seq((1, 200), (2, 300), (3, 400), (4, 500), (5, 600))

      val notOneToOne = AsyncPipe.of[Int].mapConcat(i => List(i, i))
      runFailure(
        ints.zipVia(identity, notOneToOne)(
          (
            i,
            y
          ) => i + y
        )
      ) shouldBe an[
        IllegalStateException
      ]
    }
  }

  "the stream" should {

    "be cold: no thunk runs before a run, each runs once per run" in {
      val deferred, inits, creates, iterators, futures, futureStreams, tasks, opens =
        new Counter

      val stream =
        AsyncStream.defer {
          deferred.inc()
          AsyncStream(1, 2)
        }.stateful { () =>
          inits.inc()
          0
        }(
          (
            s,
            a
          ) => Step(s + a, List(a))
        ).statefulMapConcat { () =>
          creates.inc()
          (a: Int) => List(a)
        }.concat(AsyncStream.fromIterator { () =>
          iterators.inc()
          Iterator(3)
        }).concat(AsyncStream.future {
          futures.inc()
          Future.successful(4)
        }).concat(AsyncStream.futureStream {
          futureStreams.inc()
          Future.successful(AsyncStream.single(5))
        }).concat(AsyncStream.fromBlockingIterator { () =>
          opens.inc()
          Iterator(6)
        }).concurrently { () =>
          tasks.inc()
          Future.unit
        }

      val all = Seq(deferred, inits, creates, iterators, futures, futureStreams, tasks, opens)
      all.map(_()) shouldBe Seq.fill(8)(0)
      stream.describe // describing runs nothing either
      all.map(_()) shouldBe Seq.fill(8)(0)

      run(stream) shouldBe (1 to 6)
      all.map(_()) shouldBe Seq.fill(8)(1)
      run(stream) shouldBe (1 to 6)
      all.map(_()) shouldBe Seq.fill(8)(2)
    }

    "keep per-run state per run" in {
      val stream = ints.zipWithIndex.stateful(() => 0)(
        (
          sum,
          pair
        ) => Step(sum + pair._1, List(sum + pair._1)),
        sum => List(-sum)
      )
      run(stream) shouldBe Seq(1, 3, 6, 10, 15, -15)
      run(stream) shouldBe run(stream)
    }

    "run a publisher factory once per run" in {
      val publisher = new TrackedPublisher(1 to 3)
      val stream = publisher.stream.map(_ * 2)
      publisher.subscriptions.get() shouldBe 0
      run(stream) shouldBe Seq(2, 4, 6)
      run(stream) shouldBe Seq(2, 4, 6)
      publisher.subscriptions.get() shouldBe 2
    }

    "run user functions on the run's ec, never on the publisher's thread" in {
      val publisher = new TrackedPublisher(1 to 50)
      val onEc = new ConcurrentLinkedQueue[Boolean]()
      val stream = publisher.stream.map { i =>
        onEc.add(onEcThread)
        i
      }.evalMap(i => Future.successful(i)).filter { _ =>
        onEc.add(onEcThread)
        true
      }
      run(stream).size shouldBe 50
      publisher.emittedOnThreads.asScala.forall(_.startsWith("test-io-")) shouldBe true
      onEc.asScala.toSet shouldBe Set(true)
    }

    "fail with a user function's exception, unwrapped, and cancel the upstream" in {
      val boom = new IllegalArgumentException("boom")
      val publisher = new TrackedPublisher(1 to 100)
      val failure = runFailure(publisher.stream.map { i =>
        if (i == 3) throw boom else i
      })
      failure shouldBe theSameInstanceAs(boom)
      eventually(publisher.cancellations.get() shouldBe 1)

      runFailure(
        ints.evalMap(i => if (i == 2) Future.failed(boom) else Future.successful(i))
      ) shouldBe
        theSameInstanceAs(boom)
      runFailure(ints.evalMap(_ => throw boom)) shouldBe theSameInstanceAs(boom)
      runFailure(ints.flatMapConcat(_ => throw boom)) shouldBe theSameInstanceAs(boom)
      runFailure(ints.filter(_ => throw boom)) shouldBe theSameInstanceAs(boom)
      runFailure(AsyncStream.defer[Int](throw boom)) shouldBe theSameInstanceAs(boom)
      runFailure(AsyncStream.future[Int](throw boom)) shouldBe theSameInstanceAs(boom)
      runFailure(AsyncStream.fromIterator[Int](() => throw boom)) shouldBe theSameInstanceAs(
        boom
      )
      runFailure(
        ints.stateful[Int, Int](() => throw boom)(
          (
            s,
            a
          ) => Step(s, List(a))
        )
      ) shouldBe
        theSameInstanceAs(boom)
    }

    "deliver an upstream failure after the elements before it" in {
      val boom = new RuntimeException("upstream")
      val publisher = new TrackedPublisher(1 to 3, failWith = Some(boom))
      val seen = new ConcurrentLinkedQueue[Int]()
      val failure = Try(await(publisher.stream.runForeach(i => seen.add(i)))).failed.get
      failure shouldBe theSameInstanceAs(boom)
      seen.asScala.toList shouldBe List(1, 2, 3)
    }

    "run the stateful end hook on normal completion only" in {
      val hooks = new AtomicInteger()
      def withHook(stream: AsyncStream[Int]) =
        stream.stateful(() => ())(
          (
            s,
            a
          ) => Step(s, List(a)),
          _ => { hooks.incrementAndGet(); List(99) }
        )

      run(withHook(ints)) shouldBe Seq(1, 2, 3, 4, 5, 99)
      hooks.get() shouldBe 1

      runFailure(withHook(ints ++ AsyncStream.failed(new RuntimeException)))
      hooks.get() shouldBe 1

      run(withHook(ints).take(2)) shouldBe Seq(1, 2)
      hooks.get() shouldBe 1

      // a throwing end hook fails the stream after the elements
      val boom = new RuntimeException("hook")
      val seen = new ConcurrentLinkedQueue[Int]()
      val failure = Try(
        await(
          ints
            .stateful(() => ())(
              (
                s,
                a
              ) => Step(s, List(a)),
              _ => throw boom
            )
            .runForeach(i => seen.add(i))
        )
      ).failed.get
      failure shouldBe theSameInstanceAs(boom)
      seen.asScala.toList shouldBe List(1, 2, 3, 4, 5)
    }

    "stop: emit, cancel the upstream, complete the stages above (not below)" in {
      val publisher = new TrackedPublisher(1 to 100)
      val belowHook, aboveHook = new AtomicInteger()
      val stream = publisher.stream
        .stateful(() => ())(
          (
            s,
            a
          ) => Step(s, List(a)),
          _ => { belowHook.incrementAndGet(); Nil }
        )
        .stateful(() => 0)(
          (
            n,
            a
          ) => Step(n + 1, List(a, a), stop = a == 3)
        )
        .stateful(() => 0)(
          (
            count,
            a
          ) => Step(count + 1, List(a)),
          count => List(-count)
        )
        .stateful(() => ())(
          (
            s,
            a
          ) => Step(s, List(a)),
          _ => { aboveHook.incrementAndGet(); Nil }
        )

      run(stream) shouldBe Seq(1, 1, 2, 2, 3, 3, -6)
      belowHook.get() shouldBe 0
      aboveHook.get() shouldBe 1
      eventually(publisher.cancellations.get() shouldBe 1)
    }

    "take(0): never run the upstream" in {
      val publisher = new TrackedPublisher(1 to 3)
      run(publisher.stream.take(0)) shouldBe Nil
      run(publisher.stream.take(-1)) shouldBe Nil
      publisher.subscriptions.get() shouldBe 0
    }

    "cancel the upstream after take(n) and runHead" in {
      val publisher = new TrackedPublisher(1 to 1000)
      run(publisher.stream.take(3)) shouldBe Seq(1, 2, 3)
      eventually(publisher.cancellations.get() shouldBe 1)
      await(publisher.stream.runHead) shouldBe 1
      eventually(publisher.cancellations.get() shouldBe 2)
      await(publisher.stream.runHeadOption) shouldBe Some(1)
      await(AsyncStream.empty[Int].runHeadOption) shouldBe None
      a[NoSuchElementException] should be thrownBy await(AsyncStream.empty[Int].runHead)
      await(ints.runLast) shouldBe 5
      await(AsyncStream.empty[Int].runLastOption) shouldBe None
      await(ints.runFold(0)(_ + _)) shouldBe 15
      await(ints.runDrain) shouldBe (())
    }

    "recover and map errors" in {
      val boom = new IllegalStateException("boom")
      val failing = AsyncStream(1, 2) ++ AsyncStream.failed[Int](boom)

      run(failing.recover { case _: IllegalStateException => -1 }) shouldBe Seq(1, 2, -1)
      run(failing.recoverWith { case _: IllegalStateException => AsyncStream(7, 8) }) shouldBe
        Seq(1, 2, 7, 8)
      runFailure(failing.recover { case _: IllegalArgumentException => -1 }) shouldBe
        theSameInstanceAs(boom)

      // a recovery stream's failure is final (one switch)
      val second = new IllegalStateException("second")
      runFailure(failing.recoverWith { case _ => AsyncStream.failed(second) }) shouldBe
        theSameInstanceAs(second)

      val mapped = new IllegalArgumentException("mapped")
      runFailure(failing.mapError { case _: IllegalStateException => mapped }) shouldBe
        theSameInstanceAs(mapped)
      runFailure(failing.mapError { case _: IllegalArgumentException => mapped }) shouldBe
        theSameInstanceAs(boom)
      run(ints.mapError { case e => e }) shouldBe Seq(1, 2, 3, 4, 5)
    }

    "run a cancellable run" in {
      val publisher = new TrackedPublisher(1 to 1000000)
      val started = new CountDownLatch(1)
      val streamRun = publisher.stream.run(_ => started.countDown())
      started.await(10, TimeUnit.SECONDS) shouldBe true
      streamRun.cancel()
      await(streamRun.done) shouldBe (())
      eventually(publisher.cancellations.get() shouldBe 1)
    }

    "describe its plan, never its elements" in {
      val stream = AsyncStream
        .fromPublisher(() => new TrackedPublisher(Seq("secret")).publisher)
        .map(identity)
        .filter(_ => true)
        .take(1)
        .mapError { case e => e }
      stream.describe shouldBe "fromPublisher -> map -> filter -> take(1) -> mapError"
      stream.toString should not include "secret"
      AsyncStream("secret").toString should not include "secret"
    }

    "replay the first element of a preflighted run, once" in {
      val publisher = new TrackedPublisher(1 to 3)
      val preflighted = await(publisher.stream.preflight)
      publisher.subscriptions.get() shouldBe 1
      run(preflighted) shouldBe Seq(1, 2, 3)
      publisher.subscriptions.get() shouldBe 1
      runFailure(preflighted) shouldBe an[IllegalStateException]

      run(await(AsyncStream.empty[Int].preflight)) shouldBe Nil

      val boom = new RuntimeException("404")
      Try(
        await(AsyncStream.failed[Int](boom).preflight)
      ).failed.get shouldBe theSameInstanceAs(boom)
    }

    "hand a preflight to a native leaf that runs it itself, whole" in {
      val handed = new java.util.concurrent.atomic.AtomicReference[AsyncStream[_]]()
      val own = new NativeStream[Int] with impl.NativePreflight {
        def openPublisher() = AsyncStream(1).toPublisher[Int]
        def backend = "test"
        def preflight[A](stream: AsyncStream[A]): Future[AsyncStream[A]] = {
          handed.set(stream)
          Future.successful(stream)
        }
      }
      val stream = AsyncStream.fromNative(own).map(_ + 1).mapError { case e => e }
      await(stream.preflight) should be theSameInstanceAs stream
      handed.get should be theSameInstanceAs stream

      // a hook that throws: a failed future, never a throw
      val boom = new RuntimeException("no runtime")
      val throwing = new NativeStream[Int] with impl.NativePreflight {
        def openPublisher() = AsyncStream(1).toPublisher[Int]
        def backend = "test"
        def preflight[A](stream: AsyncStream[A]): Future[AsyncStream[A]] = throw boom
      }
      Try(
        await(AsyncStream.fromNative(throwing).preflight)
      ).failed.get should be theSameInstanceAs boom

      // a native leaf without the hook: a run of the neutral interpreter
      val plain = new NativeStream[Int] {
        def openPublisher() = AsyncStream(1, 2).toPublisher[Int]
        def backend = "test"
      }
      val preflighted = await(AsyncStream.fromNative(plain).map(_ + 1).preflight)
      preflighted.describe shouldBe "preflight"
      run(preflighted) shouldBe Seq(2, 3)
    }

    "convert to a publisher: one independent run per subscription" in {
      val counter = new Counter
      val publisher = AsyncStream.defer {
        counter.inc()
        ints
      }.toPublisher[Int]
      run(AsyncStream.fromPublisher(() => publisher)) shouldBe Seq(1, 2, 3, 4, 5)
      run(AsyncStream.fromPublisher(() => publisher)) shouldBe Seq(1, 2, 3, 4, 5)
      counter() shouldBe 2
    }

    "count bridges per compilation scope, lazy children included, other threads excluded" in {
      // a foreign native leaf: one bridge per neutral run
      def foreign(n: Int): AsyncStream[Int] =
        AsyncStream.fromNative(new NativeStream[Int] {
          def openPublisher() = AsyncStream(n).toPublisher[Int]
          def backend = "test"
        })

      val noise = new java.util.concurrent.atomic.AtomicBoolean(true)
      val noiseThread = new Thread(() => {
        while (noise.get()) await(foreign(0).runToSeq)
      })
      noiseThread.start()
      try {
        val stream =
          foreign(1) ++ AsyncStream(2).flatMapConcat(_ => foreign(3)) // lazy children
        val (result, bridges) = StreamDiagnostics.counted(stream.runToSeq)
        await(result) shouldBe Seq(1, 3)
        bridges.get shouldBe 2

        val (plain, none) = StreamDiagnostics.counted(AsyncStream(1, 2).map(_ + 1).runToSeq)
        await(plain) shouldBe Seq(2, 3)
        none.get shouldBe 0
      } finally {
        noise.set(false)
        noiseThread.join()
      }
    }

    "fail a second run of fromPublisherOnce" in {
      val once = AsyncStream.fromPublisherOnce(new TrackedPublisher(1 to 2).publisher)
      run(once) shouldBe Seq(1, 2)
      runFailure(once) shouldBe an[IllegalStateException]
    }
  }

  private def eventually(assertion: => Unit): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var done = false
    while (!done) {
      try {
        assertion
        done = true
      } catch {
        case _: Throwable if System.nanoTime() < deadline =>
          Thread.sleep(10)
      }
    }
  }
}
