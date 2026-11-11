package io.cequence.wsclient.stream

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.Fs2Interop._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success, Try}

class Fs2InteropSpec extends AnyWordSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def runFs2[A](
    stream: fs2.Stream[IO, A],
    timeout: FiniteDuration = 15.seconds
  ): Seq[A] =
    stream.compile.toVector
      .unsafeRunTimed(timeout)
      .getOrElse(fail(s"timed out after $timeout"))

  private def nativeOnly[A](stream: fs2.Stream[IO, A]): AsyncStream[A] =
    AsyncStream.fromNative(new Fs2Leaf(stream, IO.asyncForIO, Fs2Runner.none[IO]))

  private def eventually(assertion: => Unit): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var done = false
    while (!done) {
      try {
        assertion
        done = true
      } catch { case _: Throwable if System.nanoTime() < deadline => Thread.sleep(10) }
    }
  }

  "the fs2 interop" should {

    "give back the same fs2 stream and pipe" in {
      val stream = fs2.Stream.emits(List(1, 2, 3)).covary[IO]
      stream.toAsyncStream.toFs2[IO] should be theSameInstanceAs stream

      val pipe: fs2.Pipe[IO, Int, Int] = _.map(_ + 1)
      Fs2Interop.fromFs2Pipe(pipe).toFs2Pipe[IO] should be theSameInstanceAs pipe
    }

    "run operators on a native leaf natively - no bridge, no fallback" in {
      val stream = nativeOnly(fs2.Stream.range(1, 11).covary[IO])
        .map(_ * 2)
        .filter(_ % 3 != 0)
        .stateful(() => 0)(
          (
            sum,
            a
          ) => Step(sum + a, List(a)),
          sum => List(-sum)
        )
        .evalMap(a => Future.successful(a))
        .flatMapConcat(a => AsyncStream(a))
        .concat(AsyncStream.single(0))
        .onTerminate(_ => ())
        .concurrently(() => Future.unit)
        .recover { case _ => -1 }
        .via(Fs2Interop.fromFs2Pipe[IO, Int, Int](_.map(identity)))

      val (compiled, bridges) = StreamDiagnostics.counted(stream.toFs2[IO])
      runFs2(compiled) shouldBe Seq(2, 4, 8, 10, 14, 16, 20, -74, 0)
      bridges.get shouldBe 0
    }

    "bridge a foreign leaf once, and a native fs2 leaf consumed neutrally once" in {
      val (compiled, bridges) =
        StreamDiagnostics.counted(
          AsyncStream.fromPublisher(() => AsyncStream(1, 2).toPublisher[Int]).toFs2[IO]
        )
      runFs2(compiled) shouldBe Seq(1, 2)
      bridges.get shouldBe 1

      val (neutral, neutralBridges) =
        StreamDiagnostics.counted(
          fs2.Stream.emits(List(1, 2, 3)).covary[IO].toAsyncStream.runToSeq
        )
      Await.result(neutral, 15.seconds) shouldBe Seq(1, 2, 3)
      neutralBridges.get shouldBe 1
    }

    "apply a non-native pipe as an fs2 pipe without a bridge" in {
      val pipe = AsyncPipe
        .of[Int]
        .map(_ + 1)
        .stateful(() => 0)(
          (
            n,
            a
          ) => Step(n + 1, List(a * n))
        )
      val (fs2Pipe, bridges) = StreamDiagnostics.counted(pipe.toFs2Pipe[IO])
      runFs2(fs2.Stream.range(1, 5).covary[IO].through(fs2Pipe)) shouldBe Seq(0, 3, 8, 15)
      runFs2(fs2.Stream.range(1, 5).covary[IO].through(fs2Pipe)) shouldBe Seq(0, 3, 8, 15)
      bridges.get shouldBe 0
    }

    "be cold: thunks run per run, never at compile time" in {
      val deferred, inits, tasks, opens = new AtomicInteger()
      val compiled = AsyncStream.defer {
        deferred.incrementAndGet(); AsyncStream(1, 2)
      }.stateful { () => inits.incrementAndGet(); 0 }(
        (
          s,
          a
        ) => Step(s, List(a))
      ).concat(AsyncStream.fromBlockingIterator { () => opens.incrementAndGet(); Iterator(3) })
        .concurrently { () => tasks.incrementAndGet(); Future.unit }
        .toFs2[IO]
      Seq(deferred, inits, tasks, opens).map(_.get()) shouldBe Seq(0, 0, 0, 0)
      runFs2(compiled) shouldBe Seq(1, 2, 3)
      runFs2(compiled) shouldBe Seq(1, 2, 3)
      Seq(deferred, inits, tasks, opens).map(_.get()) shouldBe Seq(2, 2, 2, 2)
    }

    "report termination causes once - a downstream stop is Cancelled" in {
      val causes = new ConcurrentLinkedQueue[Termination]()
      def tracked(s: AsyncStream[Int]) = s.onTerminate(t => causes.add(t)).toFs2[IO]

      runFs2(tracked(AsyncStream(1, 2))) shouldBe Seq(1, 2)
      val boom = new RuntimeException("boom")
      Try(
        runFs2(tracked(AsyncStream(1) ++ AsyncStream.failed(boom)))
      ).failed.get shouldBe theSameInstanceAs(boom)
      runFs2(tracked(AsyncStream(1, 2, 3)).take(1)) shouldBe Seq(1)
      eventually(causes.size shouldBe 3)
      causes.asScala.toList shouldBe List(
        Termination.Completed,
        Termination.Failed(boom),
        Termination.Cancelled
      )

      val hookError = new IllegalStateException("hook")
      Try(
        runFs2(AsyncStream(1).guaranteeCase(_ => Future.failed(hookError)).toFs2[IO])
      ).failed.get shouldBe
        theSameInstanceAs(hookError)
    }

    "emit the outputs before a failing element, then fail" in {
      val boom = new IllegalArgumentException("boom")
      val seen = new ConcurrentLinkedQueue[Int]()
      val compiled = AsyncStream(1, 2, 3, 4).map(i => if (i == 3) throw boom else i).toFs2[IO]
      Try(
        compiled.evalMap(i => IO(seen.add(i))).compile.drain.unsafeRunTimed(15.seconds)
      ).failed.get shouldBe
        theSameInstanceAs(boom)
      seen.asScala.toList shouldBe List(1, 2)
    }

    "fail on a concurrent task's failure, await it when asked" in {
      val taskError = new RuntimeException("kickoff")
      Try(
        runFs2(
          AsyncStream
            .future(Promise[Int]().future)
            .concurrently(() => Future.failed(taskError))
            .toFs2[IO]
        )
      ).failed.get shouldBe
        theSameInstanceAs(taskError)
      runFs2(
        AsyncStream(1).concurrently(() => Future.unit, awaitCompletion = true).toFs2[IO]
      ) shouldBe Seq(1)
    }

    "close blocking sources once and stay stack-safe on recursive concats" in {
      val closes = new AtomicInteger()
      runFs2(
        AsyncStream
          .fromBlockingIterator(() => Iterator(1, 2, 3), () => closes.incrementAndGet())
          .toFs2[IO]
          .take(2)
      ) shouldBe
        Seq(1, 2)
      // after an early stop the close runs on the blocking pool: not awaited by the stream's end
      eventually(closes.get() shouldBe 1)

      def ones: AsyncStream[Int] = AsyncStream.single(1) ++ ones
      // 100,000 pulls, each through the run's ec: 1 s on an idle machine, but the ec is shared
      // with every suite running next to this one (it timed out at 15 s on a loaded CI runner)
      runFs2(ones.take(100000).toFs2[IO], 2.minutes).size shouldBe 100000
    }

    "read a blocking source through the neutral blocking cursor - without a bridge" in {
      // a throwing close() is logged: never a failure of the (finished) stream
      val closes = new AtomicInteger()
      val throwingClose = AsyncStream.fromBlockingIterator[Int](
        () => Iterator(1, 2),
        () => { closes.incrementAndGet(); throw new RuntimeException("close") }
      )
      val (compiled, bridges) = StreamDiagnostics.counted(throwingClose.toFs2[IO])
      runFs2(compiled) shouldBe Seq(1, 2)
      bridges.get shouldBe 0
      closes.get() shouldBe 1

      // a stalled read: the cancel does not wait for it; the source is closed once it returned
      val reading = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val stalledCloses = new AtomicInteger()
      val stalled = AsyncStream.fromBlockingIterator[Int](
        () =>
          new Iterator[Int] {
            def hasNext: Boolean = { reading.countDown(); release.await(); false }
            def next(): Int = throw new NoSuchElementException
          },
        () => stalledCloses.incrementAndGet()
      )
      try {
        runFs2(
          stalled.toFs2[IO].interruptWhen(IO.blocking(reading.await()).attempt)
        ) shouldBe Seq.empty
        stalledCloses.get() shouldBe 0 // the read is still in progress
      } finally release.countDown()
      eventually(stalledCloses.get() shouldBe 1)
    }

    "treat a throw while building a per-run stream as a failure of the stream" in {
      val boom = new RuntimeException("boom")
      def failing: AsyncStream[Int] = throw boom
      def recovered(stream: AsyncStream[Int]): Seq[Int] =
        runFs2(stream.recover { case e if e eq boom => -1 }.toFs2[IO])

      // behind a `++` and below a stage that steps its upstream, fs2 hands a throw out of a
      // suspended stream's thunk to no later error handler
      recovered(AsyncStream.single(1) ++ failing) shouldBe Seq(1, -1)
      recovered(AsyncStream.single(1) ++ AsyncStream.defer(failing)) shouldBe Seq(1, -1)
      recovered(AsyncStream.defer(failing).map(_ + 1)) shouldBe Seq(-1)
      recovered(AsyncStream(1, 2).flatMapConcat(_ => failing).map(_ + 1)) shouldBe Seq(-1)
      recovered(
        AsyncStream.single(1) ++ AsyncStream.futureStream(
          Future.successful(1).map(_ => failing)
        )
      ) shouldBe Seq(1, -1)
      recovered(
        AsyncStream.single(1) ++
          AsyncStream.failed[Int](new RuntimeException("first")).recoverWith { case _ =>
            failing
          }
      ) shouldBe Seq(1, -1)
      recovered(
        AsyncStream.single(1) ++
          AsyncStream(2).stateful[Int, Int](() => throw boom)(
            (
              s,
              a
            ) => Step(s, List(a)),
            _ => Nil
          )
      ) shouldBe Seq(1, -1)

      val mapped = new RuntimeException("mapped")
      Try(
        runFs2((AsyncStream.single(1) ++ failing).mapError { case _ => mapped }.toFs2[IO])
      ).failed.get shouldBe theSameInstanceAs(mapped)
    }

    "read an iterator's first element alone, and emit the elements before a failing read" in {
      val produced = new AtomicInteger()
      val counting =
        AsyncStream.fromIterator(() =>
          Iterator.from(0).map { i => produced.incrementAndGet(); i }
        )
      runFs2(counting.toFs2[IO].take(1)) shouldBe Seq(0)
      produced.get shouldBe 1 // a slow iterator's head is not held back for a full chunk

      val boom = new RuntimeException("boom")
      val failing = AsyncStream.fromIterator(() =>
        Iterator(1, 2) ++ new Iterator[Int] {
          def hasNext: Boolean = true
          def next(): Int = throw boom
        }
      )
      runFs2(failing.recover { case e if e eq boom => -1 }.toFs2[IO]) shouldBe Seq(1, 2, -1)
    }

    "read a lazy or infinite expansion lazily - at most one chunk ahead" in {
      val produced = new AtomicInteger()
      val infinite = new scala.collection.immutable.Iterable[Int] {
        def iterator: Iterator[Int] =
          Iterator.from(0).map { i => produced.incrementAndGet(); i }
      }
      runFs2(AsyncStream.single(0).mapConcat(_ => infinite).take(3).toFs2[IO]) shouldBe
        Seq(0, 1, 2)
      produced.get should be <= Fs2Compiler.MaxOutputChunk
      // a stateful end hook too, and a strict expansion keeps its order across chunks
      runFs2(
        AsyncStream(1, 2)
          .stateful(() => 0)(
            (
              s,
              a
            ) => Step(s + a, List.fill(100)(a)),
            _ => infinite
          )
          .take(205)
          .toFs2[IO]
      ) shouldBe (List.fill(100)(1) ++ List.fill(100)(2) ++ (0 until 5))
    }

    "deliver a publisher's elements as they arrive - a stalled stream holds nothing back" in {
      // one element, then silence: fs2's own flow subscriber waited for a chunk of 16
      val stalling = new java.util.concurrent.Flow.Publisher[Int] {
        def subscribe(subscriber: java.util.concurrent.Flow.Subscriber[_ >: Int]): Unit =
          subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription {
            private val sent = new java.util.concurrent.atomic.AtomicBoolean(false)
            def request(n: Long): Unit =
              if (sent.compareAndSet(false, true)) subscriber.onNext(1)
            def cancel(): Unit = ()
          })
      }
      runFs2(AsyncStream.fromPublisher(() => stalling).toFs2[IO].take(1)) shouldBe Seq(1)
    }

    "agree with the neutral interpreter on random operator chains (200 seeds)" in {
      (1 to 200).foreach { seed =>
        def chain(): AsyncStream[Int] = {
          val random = new Random(seed)
          val source = Vector.fill(random.nextInt(10))(random.nextInt(15))
          val base =
            if (random.nextInt(4) == 0)
              AsyncStream.fromIterable(source) ++ AsyncStream.failed(
                new RuntimeException("source")
              )
            else AsyncStream.fromIterable(source)
          (1 to 1 + random.nextInt(6)).foldLeft(base) {
            (
              s,
              depth
            ) =>
              random.nextInt(13) match {
                case 0 => val k = random.nextInt(5); s.map(_ + k)
                case 1 => s.filter(_ % 2 != 0)
                case 2 => s.mapConcat(i => List.fill(i % 3)(i))
                case 3 => s.take(random.nextInt(6).toLong)
                case 4 =>
                  val l = random.nextInt(15);
                  s.takeWhile(_ < l, inclusive = random.nextBoolean())
                case 5 => s.drop(random.nextInt(3).toLong)
                case 6 =>
                  s.scan(0)(
                    (
                      a,
                      i
                    ) => (a + i) % 1000
                  )
                case 7 =>
                  s.fold(0)(
                    (
                      a,
                      i
                    ) => (a + i) % 1000
                  )
                case 8  => s ++ AsyncStream(random.nextInt(9))
                case 9  => s.flatMapConcat(i => AsyncStream.fromIterable(List.fill(i % 3)(i)))
                case 10 => s.evalMap(i => Future.successful(i + 1))
                case 11 =>
                  val target = random.nextInt(15)
                  s.map(i =>
                    if (i == target) throw new RuntimeException(s"boom@$depth") else i
                  )
                case _ => s.recover { case e => -e.getMessage.length }
              }
          }
        }

        def outcome(run: (AsyncStream[Int], Int => Unit) => Future[Any])
          : (Seq[Int], Option[String]) = {
          val seen = new ConcurrentLinkedQueue[Int]()
          val result = Try(Await.result(run(chain(), i => { seen.add(i); () }), 15.seconds))
          (
            seen.asScala.toList,
            result match {
              case Success(_) => None
              case Failure(e) => Some(e.getMessage)
            }
          )
        }

        val native = outcome(
          (
            s,
            f
          ) => s.toFs2[IO].evalMap(i => IO(f(i))).compile.drain.unsafeToFuture()
        )
        val neutral = outcome(
          (
            s,
            f
          ) => s.runForeach(f)
        )
        withClue(s"seed $seed, plan ${chain().describe}: ") {
          native shouldBe neutral
        }
      }
    }
  }
}
