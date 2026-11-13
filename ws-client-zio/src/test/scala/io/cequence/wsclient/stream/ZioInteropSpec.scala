package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.ZioInterop._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.stream.{ZPipeline, ZStream}
import zio.{Runtime, Unsafe, ZIO}

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success, Try}

class ZioInteropSpec extends AnyWordSpec with Matchers {

  implicit private val runtime: Runtime[Any] = Runtime.default
  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def unsafeRun[T](effect: ZIO[Any, Throwable, T]): T =
    Unsafe.unsafe { implicit unsafe =>
      Await.result(runtime.unsafe.runToFuture(effect), 15.seconds)
    }

  private def runZio[A](stream: ZStream[Any, Throwable, A]): Seq[A] = unsafeRun(
    stream.runCollect
  )

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

  "the ZIO interop" should {

    "give back the same ZStream" in {
      val stream = ZStream(1, 2, 3)
      stream.toAsyncStream.toZStream should be theSameInstanceAs stream
    }

    "run operators on a native leaf natively - no bridge" in {
      val stream = ZStream
        .range(1, 11)
        .toAsyncStream
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
        .via(ZioInterop.fromZPipeline(ZPipeline.map[Int, Int](identity)))

      val (compiled, bridges) = StreamDiagnostics.counted(stream.toZStream)
      runZio(compiled) shouldBe Seq(2, 4, 8, 10, 14, 16, 20, -74, 0)
      bridges.get shouldBe 0
    }

    "convert byte streams both ways without a bridge - a converted ZIO byte stream comes back as its own chunks" in {
      val zBytes = ZStream.fromChunks(zio.Chunk[Byte](1, 2), zio.Chunk[Byte](3))
      val async = zBytes.toAsyncByteStream

      // neutrally: the chunks as received
      Await.result(async.runToSeq, 10.seconds).map(_.toArray.toSeq) shouldBe
        Seq(Seq[Byte](1, 2), Seq[Byte](3))

      val (back, bridges) = StreamDiagnostics.counted(async.toZByteStream)
      runZio(back) shouldBe Seq[Byte](1, 2, 3)
      bridges.get shouldBe 0

      // neutral Bytes, sliced ones included, share their arrays towards ZIO
      val array = Array[Byte](9, 4, 5, 6, 9)
      val neutral = AsyncStream(Bytes.unsafeWrap(array, 1, 3), Bytes.utf8("a"))
      val (fromNeutral, neutralBridges) = StreamDiagnostics.counted(neutral.toZByteStream)
      runZio(fromNeutral) shouldBe Seq[Byte](4, 5, 6, 'a'.toByte)
      neutralBridges.get shouldBe 0

      // an operator on the leaf: no shortcut, still native
      runZio(async.map(b => Bytes.unsafeWrap(b.toArray.reverse)).toZByteStream) shouldBe
        Seq[Byte](2, 1, 3)
    }

    "bridge a foreign leaf once, and a native ZIO leaf consumed neutrally once" in {
      val (compiled, bridges) = StreamDiagnostics.counted(
        AsyncStream.fromPublisher(() => AsyncStream(1, 2).toPublisher[Int]).toZStream
      )
      runZio(compiled) shouldBe Seq(1, 2)
      bridges.get shouldBe 1

      val (neutral, neutralBridges) =
        StreamDiagnostics.counted(ZStream(1, 2, 3).toAsyncStream.map(_ * 10).runToSeq)
      Await.result(neutral, 15.seconds) shouldBe Seq(10, 20, 30)
      neutralBridges.get shouldBe 1
    }

    "apply a non-native pipe to a ZStream without a bridge" in {
      val pipe = AsyncPipe
        .of[Int]
        .map(_ + 1)
        .stateful(() => 0)(
          (
            n,
            a
          ) => Step(n + 1, List(a * n))
        )
      val (function, bridges) = StreamDiagnostics.counted(pipe.toZStreamFunction)
      runZio(function(ZStream(1, 2, 3, 4))) shouldBe Seq(0, 3, 8, 15)
      runZio(function(ZStream(1, 2, 3, 4))) shouldBe Seq(0, 3, 8, 15)
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
        .toZStream
      Seq(deferred, inits, tasks, opens).map(_.get()) shouldBe Seq(0, 0, 0, 0)
      runZio(compiled) shouldBe Seq(1, 2, 3)
      runZio(compiled) shouldBe Seq(1, 2, 3)
      Seq(deferred, inits, tasks, opens).map(_.get()) shouldBe Seq(2, 2, 2, 2)
    }

    "report termination causes once - a downstream stop is Cancelled" in {
      val causes = new ConcurrentLinkedQueue[Termination]()
      def tracked(s: AsyncStream[Int]) = s.onTerminate(t => causes.add(t)).toZStream

      runZio(tracked(AsyncStream(1, 2))) shouldBe Seq(1, 2)
      val boom = new RuntimeException("boom")
      Try(
        runZio(tracked(AsyncStream(1) ++ AsyncStream.failed(boom)))
      ).failed.get shouldBe theSameInstanceAs(boom)
      runZio(tracked(AsyncStream(1, 2, 3)).take(1)) shouldBe Seq(1)
      eventually(causes.size shouldBe 3)
      causes.asScala.toList shouldBe List(
        Termination.Completed,
        Termination.Failed(boom),
        Termination.Cancelled
      )
    }

    "fail with a user function's exception (a failure, not a defect), after the outputs before it" in {
      val boom = new IllegalArgumentException("boom")
      val compiled = AsyncStream(1, 2, 3, 4).map(i => if (i == 3) throw boom else i).toZStream
      val seen = new ConcurrentLinkedQueue[Int]()
      val result = unsafeRun(compiled.mapZIO(i => ZIO.succeed(seen.add(i))).runDrain.either)
      result shouldBe Left(boom)
      seen.asScala.toList shouldBe List(1, 2)

      // recover sees it - it is a failure
      runZio(
        AsyncStream(1, 2)
          .map(i => if (i == 2) throw boom else i)
          .recover { case _ => -1 }
          .toZStream
      ) shouldBe
        Seq(1, -1)
    }

    "fail on a concurrent task's failure, await it when asked" in {
      val taskError = new RuntimeException("kickoff")
      Try(
        runZio(
          AsyncStream
            .future(Promise[Int]().future)
            .concurrently(() => Future.failed(taskError))
            .toZStream
        )
      ).failed.get shouldBe theSameInstanceAs(taskError)
      runZio(
        AsyncStream(1).concurrently(() => Future.unit, awaitCompletion = true).toZStream
      ) shouldBe Seq(1)
    }

    "close blocking sources once, run ZIO's scheduler, stay stack-safe on recursive concats" in {
      val closes = new AtomicInteger()
      runZio(
        AsyncStream
          .fromBlockingIterator(() => Iterator(1, 2, 3), () => closes.incrementAndGet())
          .toZStream
          .take(2)
      ) shouldBe Seq(1, 2)
      eventually(closes.get() shouldBe 1)

      val ran = Promise[Unit]()
      ZioInterop.scheduler.scheduleOnce(10.millis)(() => ran.success(()))
      Await.result(ran.future, 10.seconds) shouldBe (())

      def ones: AsyncStream[Int] = AsyncStream.single(1) ++ ones
      runZio(ones.take(100000).toZStream).size shouldBe 100000
    }

    "read a blocking source through the neutral blocking cursor - without a bridge" in {
      // a throwing close() is logged: never a failure of the (finished) stream
      val closes = new AtomicInteger()
      val throwingClose = AsyncStream.fromBlockingIterator[Int](
        () => Iterator(1, 2),
        () => { closes.incrementAndGet(); throw new RuntimeException("close") }
      )
      val (compiled, bridges) = StreamDiagnostics.counted(throwingClose.toZStream)
      runZio(compiled) shouldBe Seq(1, 2)
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
        runZio(
          stalled.toZStream.interruptWhen(ZIO.attemptBlocking(reading.await()))
        ) shouldBe Seq.empty
        stalledCloses.get() shouldBe 0 // the read is still in progress
      } finally release.countDown()
      eventually(stalledCloses.get() shouldBe 1)
    }

    "read an iterator's first element alone, and emit the elements before a failing read" in {
      val produced = new AtomicInteger()
      val counting =
        AsyncStream.fromIterator(() =>
          Iterator.from(0).map { i => produced.incrementAndGet(); i }
        )
      runZio(counting.toZStream.take(1)) shouldBe Seq(0)
      produced.get shouldBe 1 // a slow iterator's head is not held back for a full chunk

      val boom = new RuntimeException("boom")
      val failing = AsyncStream.fromIterator(() =>
        Iterator(1, 2) ++ new Iterator[Int] {
          def hasNext: Boolean = true
          def next(): Int = throw boom
        }
      )
      runZio(failing.recover { case e if e eq boom => -1 }.toZStream) shouldBe Seq(1, 2, -1)
    }

    "read a lazy or infinite expansion lazily - at most one chunk ahead" in {
      val produced = new AtomicInteger()
      val infinite = new scala.collection.immutable.Iterable[Int] {
        def iterator: Iterator[Int] =
          Iterator.from(0).map { i => produced.incrementAndGet(); i }
      }
      runZio(AsyncStream.single(0).mapConcat(_ => infinite).take(3).toZStream) shouldBe
        Seq(0, 1, 2)
      produced.get should be <= ZioCompiler.MaxOutputChunk
      runZio(
        AsyncStream(1, 2)
          .stateful(() => 0)(
            (
              s,
              a
            ) => Step(s + a, List.fill(100)(a)),
            _ => infinite
          )
          .take(205)
          .toZStream
      ) shouldBe (List.fill(100)(1) ++ List.fill(100)(2) ++ (0 until 5))
    }

    "treat a native ZIO leaf's defect as a failure for recover, mapError and onTerminate" in {
      val boom = new IllegalStateException("boom")
      // 1, then a defect (ZIO's map over a chunk loses the whole chunk: 1 in a chunk of its own)
      val dying = ZStream(1) ++ ZStream(2).map(i => if (i == 2) throw boom else i)

      runZio(dying.toAsyncStream.recover { case _ => 0 }.toZStream) shouldBe Seq(1, 0)
      Try(
        runZio(
          dying.toAsyncStream.mapError { case e =>
            new IllegalArgumentException(e.getMessage)
          }.toZStream
        )
      ).failed.get shouldBe an[IllegalArgumentException]

      val causes = new ConcurrentLinkedQueue[Termination]()
      Try(runZio(dying.toAsyncStream.onTerminate(t => causes.add(t)).toZStream))
      eventually(causes.size shouldBe 1)
      causes.peek() shouldBe Termination.Failed(boom)
    }

    "keep a throwing mapError guard a failure, never a defect" in {
      val result = unsafeRun(
        AsyncStream
          .failed[Int](new RuntimeException()) // getMessage is null
          .mapError { case e if e.getMessage.startsWith("429") => new IllegalStateException() }
          .toZStream
          .runCollect
          .either
      )
      result.left.toOption.get shouldBe a[NullPointerException]
    }

    "release a resource acquired while a cancel waited for the pull (exactly once)" in {
      val acquired, released = new AtomicInteger()
      val slowAcquire = ZStream
        .acquireReleaseWith(
          (ZIO.sleep(zio.Duration.fromMillis(300)) *> ZIO
            .succeed(acquired.incrementAndGet())).uninterruptible
        )(_ => ZIO.succeed(released.incrementAndGet()))
        .flatMap(_ => ZStream.never)
      val run = slowAcquire.toAsyncStream.run(_ => ())
      Thread.sleep(50) // the pull is inside the uninterruptible acquire
      run.cancel()
      Await.result(run.done, 15.seconds)
      eventually {
        acquired.get shouldBe 1
        released.get shouldBe 1
      }
    }

    "keep a fiber the stream forked alive for its whole run - behind the bridge and after a preflight" in {
      val stopped = new AtomicInteger()
      // the first pull forks the producer (a plain fork: a child of the fiber that pulls) and
      // returns; every later element needs the producer still running
      def produced: ZStream[Any, Throwable, Int] = ZStream.unwrap(
        for {
          queue <- zio.Queue.bounded[Int](1)
          _ <- ZIO
            .foreachDiscard(1 to 5)(i =>
              ZIO.sleep(zio.Duration.fromMillis(20)) *> queue.offer(i)
            )
            .zipRight(ZIO.never)
            .onInterrupt(ZIO.succeed(stopped.incrementAndGet()))
            .fork
        } yield ZStream.fromQueue(queue)
      )

      // behind the bridge: every pull of the neutral run
      Await.result(produced.toAsyncStream.take(5).runToSeq, 10.seconds) shouldBe (1 to 5)
      eventually(stopped.get shouldBe 1) // the run's end stops the producer

      // preflighted (the first pull), then run natively and neutrally
      val native = Await.result(produced.toAsyncStream.preflight, 10.seconds)
      runZio(native.take(5).toZStream) shouldBe (1 to 5)
      eventually(stopped.get shouldBe 2)

      val neutral = Await.result(produced.toAsyncStream.preflight, 10.seconds)
      Await.result(neutral.take(5).runToSeq, 10.seconds) shouldBe (1 to 5)
      eventually(stopped.get shouldBe 3)
    }

    "end a bridged run as its stream ends - a defect and the stream's own interruption are failures, after the elements before them" in {
      def bridged(stream: ZStream[Any, Throwable, Int]): (Seq[Int], Try[Unit]) = {
        val seen = new ConcurrentLinkedQueue[Int]()
        val run = stream.toAsyncStream.run(i => { seen.add(i); () })
        val result = Try(Await.result(run.done, 10.seconds))
        (seen.asScala.toSeq, result)
      }

      val boom = new RuntimeException("defect")
      val (beforeDefect, died) = bridged(ZStream(1, 2) ++ ZStream.die(boom))
      beforeDefect shouldBe Seq(1, 2)
      died.failed.get should be theSameInstanceAs boom

      val (beforeInterrupt, interrupted) =
        bridged(ZStream(1, 2) ++ ZStream.fromZIO(ZIO.interrupt))
      beforeInterrupt shouldBe Seq(1, 2)
      // a Scala future boxes an InterruptedException (it is not NonFatal)
      interrupted.failed.get.getCause shouldBe an[InterruptedException]

      // the finalizers have run when the end (or the failure) is signalled
      val released = new AtomicInteger()
      def releasing(stream: ZStream[Any, Throwable, Int]): ZStream[Any, Throwable, Int] =
        ZStream
          .acquireReleaseWith(ZIO.unit)(_ =>
            ZIO.sleep(zio.Duration.fromMillis(100)) *> ZIO.succeed(released.incrementAndGet())
          )
          .flatMap(_ => stream)
      bridged(releasing(ZStream(1)))._2 shouldBe Success(())
      released.get shouldBe 1
      bridged(releasing(ZStream.fail(boom)))._2.failed.get should be theSameInstanceAs boom
      released.get shouldBe 2
    }

    "complete a cancelled run at once - also before its first pull and while a pull cannot be interrupted" in {
      val pulled = new AtomicInteger()
      val idle = ZStream.fromZIO(ZIO.succeed(pulled.incrementAndGet())).toAsyncStream
      // a cursor nobody asked: the stream is never pulled
      val cursor =
        new ZioCursor(ZioCompiler.toZStream(idle), runtime, impl.StreamRuntime.context(ec))
      cursor.cancel()
      Await.result(cursor.next(), 10.seconds) shouldBe None
      cursor.cancel() // idempotent
      pulled.get shouldBe 0

      val entered, finished = new CountDownLatch(1)
      val stuck = ZStream.fromZIO(
        (ZIO.succeed(entered.countDown()) *> ZIO.sleep(zio.Duration.fromMillis(500)) *>
          ZIO.succeed(finished.countDown())).uninterruptible
      )
      val run = stuck.toAsyncStream.run(_ => ())
      entered.await(10, TimeUnit.SECONDS) shouldBe true
      run.cancel()
      Await.result(run.done, 10.seconds)
      finished.getCount shouldBe 1 // the run is over, the pull still on its way
      finished.await(10, TimeUnit.SECONDS) shouldBe true
    }

    "cancel a scheduled task exactly when cancel() wins - never both, never neither" in {
      val runs = new AtomicInteger()
      val pending = ZioInterop.scheduler.scheduleOnce(300.millis)(() => {
        runs.incrementAndGet(); ()
      })
      pending.cancel() shouldBe true
      pending.isCancelled shouldBe true
      pending.cancel() shouldBe false
      Thread.sleep(500) // past the delay: the cancelled task never runs
      runs.get shouldBe 0

      val ran = Promise[Unit]()
      val done = ZioInterop.scheduler.scheduleOnce(1.milli)(() => { ran.success(()); () })
      Await.result(ran.future, 10.seconds)
      done.cancel() shouldBe false
      done.isCancelled shouldBe false
    }

    "preflight a native stream natively: no bridge, one run, the first element replayed" in {
      val runs = new AtomicInteger()
      val released = new AtomicInteger()
      val source = ZStream
        .acquireReleaseWith(ZIO.succeed(runs.incrementAndGet()))(_ =>
          ZIO.succeed(released.incrementAndGet())
        )
        .flatMap(_ => ZStream.fromChunks(zio.Chunk.empty, zio.Chunk(1, 2), zio.Chunk(3, 4, 5)))
      val stream = source.toAsyncStream.map(_ * 2)

      val (preflighted, preflightBridges) =
        StreamDiagnostics.counted(Await.result(stream.preflight, 10.seconds))
      preflightBridges.get shouldBe 0
      runs.get shouldBe 1
      released.get shouldBe 0 // held until it is run
      preflighted.describe shouldBe "native(zio)"
      preflighted.toZStream should be theSameInstanceAs preflighted.toZStream

      val (compiled, runBridges) = StreamDiagnostics.counted(preflighted.map(_ + 1).toZStream)
      runZio(compiled) shouldBe Seq(3, 5, 7, 9, 11)
      runBridges.get shouldBe 0
      runs.get shouldBe 1
      released.get shouldBe 1 // the finalizers ran before the run completed

      // a single run only: the second one fails, without an element
      val second = Try(runZio(preflighted.toZStream)).failed.get
      second shouldBe an[IllegalStateException]
      second.getMessage shouldBe "A preflighted stream supports a single run only"
      runs.get shouldBe 1
      released.get shouldBe 1
    }

    "release a preflighted stream whose run outside ZIO is cancelled at once - before its fiber or its open" in {
      // up to 1.2.0 the single run was claimed when its fiber first evaluated the stream: a run
      // cancelled before that never released the preflight (a zio-http response stayed open)
      def preflighted(released: CountDownLatch): AsyncStream[Int] =
        Await.result(
          (ZStream(1) ++ ZStream.never)
            .ensuring(ZIO.succeed(released.countDown()))
            .toAsyncStream
            .preflight,
          10.seconds
        )

      (1 to 50).foreach { _ =>
        val released = new CountDownLatch(1)
        preflighted(released).run(_ => ()).cancel()
        released.await(5, TimeUnit.SECONDS) shouldBe true
      }

      // the leaf's publisher, cancelled from inside onSubscribe: its run is never opened
      (1 to 20).foreach { _ =>
        val released = new CountDownLatch(1)
        val publisher = ZioBridges.publisher[Int](
          ZStream.never,
          runtime,
          (_: zio.Exit[Any, Any]) => ZIO.succeed(released.countDown())
        )
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber[Int] {
          def onSubscribe(s: java.util.concurrent.Flow.Subscription): Unit = s.cancel()
          def onNext(item: Int): Unit = ()
          def onError(t: Throwable): Unit = ()
          def onComplete(): Unit = ()
        })
        released.await(5, TimeUnit.SECONDS) shouldBe true
      }
    }

    "run a preflighted stream outside ZIO once: all elements, released at its end and at an early stop" in {
      val released = new AtomicInteger()
      def preflighted(): AsyncStream[Int] =
        Await.result(
          ZStream(1, 2, 3)
            .ensuring(ZIO.succeed(released.incrementAndGet()))
            .toAsyncStream
            .preflight,
          10.seconds
        )

      val whole = preflighted()
      Await.result(whole.runToSeq, 10.seconds) shouldBe Seq(1, 2, 3)
      eventually(released.get shouldBe 1)
      val second = Try(Await.result(whole.runToSeq, 10.seconds)).failed.get
      second shouldBe an[IllegalStateException]
      second.getMessage shouldBe "A preflighted stream supports a single run only"

      Await.result(preflighted().take(1).runToSeq, 10.seconds) shouldBe Seq(1)
      eventually(released.get shouldBe 2)
    }

    "preflight an empty and a failing native stream, releasing each at once; deliver a later failure in the run" in {
      def releasing(
        released: AtomicInteger,
        stream: ZStream[Any, Throwable, Int]
      ): ZStream[Any, Throwable, Int] =
        ZStream
          .acquireReleaseWith(ZIO.unit)(_ => ZIO.succeed(released.incrementAndGet()))
          .flatMap(_ => stream)

      val emptyReleased = new AtomicInteger()
      val empty = Await.result(
        releasing(emptyReleased, ZStream.fromChunk(zio.Chunk.empty)).toAsyncStream.preflight,
        10.seconds
      )
      emptyReleased.get shouldBe 1 // nothing left to hold
      runZio(empty.toZStream) shouldBe Nil
      Try(runZio(empty.toZStream)).failed.get shouldBe an[IllegalStateException]
      emptyReleased.get shouldBe 1

      val boom = new RuntimeException("404")
      val failedReleased = new AtomicInteger()
      val failure = Try(
        Await.result(
          releasing(failedReleased, ZStream.fail(boom)).toAsyncStream.map(identity).preflight,
          10.seconds
        )
      ).failed.get
      failure should be theSameInstanceAs boom
      failure.getSuppressed.length shouldBe 0 // the stream's own exception, nothing attached
      failedReleased.get shouldBe 1

      val lateReleased = new AtomicInteger()
      val late = Await.result(
        releasing(lateReleased, ZStream(1, 2) ++ ZStream.fail(boom)).toAsyncStream.preflight,
        10.seconds
      )
      val seen = new ConcurrentLinkedQueue[Int]()
      Try(
        unsafeRun(late.toZStream.runForeach(i => ZIO.succeed(seen.add(i))))
      ).failed.get.getMessage shouldBe "404"
      seen.asScala.toSeq shouldBe Seq(1, 2)
      lateReleased.get shouldBe 1
    }

    "release a preflighted run that is stopped early, and run it neutrally through one bridge" in {
      val released = new AtomicInteger()
      def held: AsyncStream[Int] = Await.result(
        ZStream
          .acquireReleaseWith(ZIO.unit)(_ => ZIO.succeed(released.incrementAndGet()))
          .flatMap(_ => ZStream(1, 2, 3) ++ ZStream.never)
          .toAsyncStream
          .preflight,
        10.seconds
      )

      runZio(held.take(2).toZStream) shouldBe Seq(1, 2)
      released.get shouldBe 1

      // an interrupted run (the stream never ends)
      unsafeRun(held.toZStream.runDrain.timeout(zio.Duration.fromMillis(200))) shouldBe None
      released.get shouldBe 2

      val (result, bridges) =
        StreamDiagnostics.counted(Await.result(held.take(3).runToSeq, 10.seconds))
      result shouldBe Seq(1, 2, 3)
      bridges.get shouldBe 1
      eventually(released.get shouldBe 3)
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
          ) =>
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.runToFuture(s.toZStream.runForeach(i => ZIO.succeed(f(i))))
            }
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
