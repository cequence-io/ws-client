package io.cequence.wsclient.stream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorAttributes, Materializer, StreamSubscriptionTimeoutTerminationMode}
import akka.stream.scaladsl.{Flow, JavaFlowSupport, Keep, Sink, Source}
import akka.util.ByteString
import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.SourceInterop._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit, Flow => JFlow}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

/** The Akka (and, generated, Pekko) interop: native round trips, the compiler, bridges. */
class SourceInteropSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("source-interop-spec")
  implicit private val materializer: Materializer = Materializer(system)
  implicit private val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def await[T](f: Future[T]): T = Await.result(f, 15.seconds)

  private def runAkka[A](source: Source[A, NotUsed]): Seq[A] = await(source.runWith(Sink.seq))

  // the bridges of the streams `compileAndRun` compiles / starts, counted after it completed
  private def bridges[T](compileAndRun: => T): (T, Long) = {
    val (result, count) = StreamDiagnostics.counted(compileAndRun)
    (result, count.get)
  }

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

  // a native leaf whose fallback must never run
  private def nativeOnly[A](source: Source[A, NotUsed]): AsyncStream[A] =
    AsyncStream.fromNative(
      new SourceLeaf(source, () => throw new AssertionError("fallback used"))
    )

  "the round trip" should {

    "give back the same Source, ByteString source and Flow" in {
      val source = Source(1 to 3)
      source.toAsyncStream.toSource should be theSameInstanceAs source

      val bytes = Source(List(ByteString("ab"), ByteString("c")))
      bytes.toAsyncByteStream.toByteStringSource should be theSameInstanceAs bytes

      val flow = Flow[Int].map(_ + 1)
      flow.toAsyncPipe.toFlow should be theSameInstanceAs flow
    }

    "run operators on a native leaf as native stages - no bridge, no fallback" in {
      val stream = nativeOnly(Source(1 to 10))
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
        .mapError { case e => e }
        .recover { case _ => -1 }
        .via(AsyncPipe.of[Int].map(identity))
        .via(Flow[Int].map(_ + 0).toAsyncPipe)

      val (result, count) = bridges(runAkka(stream.toSource))
      result shouldBe Seq(2, 4, 8, 10, 14, 16, 20, -74, 0)
      count shouldBe 0
    }

    "bridge a foreign leaf exactly once" in {
      val (result, count) = bridges(
        runAkka(
          AsyncStream
            .fromPublisher(() => AsyncStream(1, 2).toPublisher[Int])
            .map(_ + 1)
            .toSource
        )
      )
      result shouldBe Seq(2, 3)
      count shouldBe 1
    }

    "run an Akka leaf on the neutral interpreter through its fallback (one bridge)" in {
      val (result, count) = bridges(await(Source(1 to 3).toAsyncStream.map(_ * 10).runToSeq))
      result shouldBe Seq(10, 20, 30)
      count shouldBe 1
    }

    "compile a non-native pipe into a Flow without a bridge" in {
      val pipe = AsyncPipe
        .of[Int]
        .map(_ + 1)
        .stateful(() => 0)(
          (
            n,
            a
          ) => Step(n + 1, List(a * n))
        )
      val (result, count) = bridges(runAkka(Source(1 to 4).via(pipe.toFlow)))
      result shouldBe Seq(0, 3, 8, 15)
      count shouldBe 0
      // the flow re-materializes with fresh state
      runAkka(Source(1 to 4).via(pipe.toFlow)) shouldBe Seq(0, 3, 8, 15)
    }
  }

  "the Akka compiler" should {

    "be cold: thunks run per materialization, never at compile time" in {
      val deferred, inits, futures, tasks, opens = new AtomicInteger()
      val stream = AsyncStream.defer {
        deferred.incrementAndGet(); AsyncStream(1, 2)
      }.stateful { () => inits.incrementAndGet(); 0 }(
        (
          s,
          a
        ) => Step(s, List(a))
      ).concat(AsyncStream.future { futures.incrementAndGet(); Future.successful(3) })
        .concat(AsyncStream.fromBlockingIterator { () =>
          opens.incrementAndGet(); Iterator(4)
        })
        .concurrently { () => tasks.incrementAndGet(); Future.unit }

      val source = stream.toSource
      Seq(deferred, inits, futures, tasks, opens).map(_.get()) shouldBe Seq(0, 0, 0, 0, 0)
      runAkka(source) shouldBe Seq(1, 2, 3, 4)
      runAkka(source) shouldBe Seq(1, 2, 3, 4)
      Seq(deferred, inits, futures, tasks, opens).map(_.get()) shouldBe Seq(2, 2, 2, 2, 2)
    }

    "report termination causes once, gating completion" in {
      val causes = new ConcurrentLinkedQueue[Termination]()
      def tracked(stream: AsyncStream[Int]) = stream.onTerminate(t => causes.add(t)).toSource

      runAkka(tracked(AsyncStream(1, 2))) shouldBe Seq(1, 2)
      val boom = new RuntimeException("boom")
      Try(
        runAkka(tracked(AsyncStream(1) ++ AsyncStream.failed(boom)))
      ).failed.get shouldBe theSameInstanceAs(boom)
      runAkka(tracked(AsyncStream(1, 2, 3)).take(1)) shouldBe Seq(1)
      eventually(causes.size shouldBe 3)
      causes.asScala.toList shouldBe List(
        Termination.Completed,
        Termination.Failed(boom),
        Termination.Cancelled
      )

      val hookDone = Promise[Unit]()
      val result =
        AsyncStream(1).guaranteeCase(_ => hookDone.future).toSource.runWith(Sink.seq)
      Thread.sleep(50)
      result.isCompleted shouldBe false
      hookDone.success(())
      await(result) shouldBe Seq(1)
    }

    "run the stateful end hook on normal completion only, and stop early" in {
      val hooks = new AtomicInteger()
      def withHook(s: AsyncStream[Int]) =
        s.stateful(() => ())(
          (
            u,
            a
          ) => Step(u, List(a)),
          _ => { hooks.incrementAndGet(); List(99) }
        )

      runAkka(withHook(AsyncStream(1, 2)).toSource) shouldBe Seq(1, 2, 99)
      Try(
        runAkka(withHook(AsyncStream(1) ++ AsyncStream.failed(new RuntimeException)).toSource)
      )
      runAkka(withHook(AsyncStream(1, 2, 3)).toSource.take(1)) shouldBe Seq(1)
      hooks.get() shouldBe 1

      runAkka(AsyncStream(1, 2, 3, 4).take(2).toSource) shouldBe Seq(1, 2)
      runAkka(
        AsyncStream(1, 2, 3, 4).takeWhile(_ < 3, inclusive = true).toSource
      ) shouldBe Seq(1, 2, 3)
    }

    "fail with a user function's exception, unwrapped" in {
      val boom = new IllegalArgumentException("boom")
      Try(
        runAkka(AsyncStream(1, 2).map(i => if (i == 2) throw boom else i).toSource)
      ).failed.get shouldBe
        theSameInstanceAs(boom)
      Try(
        runAkka(AsyncStream(1).evalMap(_ => Future.failed[Int](boom)).toSource)
      ).failed.get shouldBe
        theSameInstanceAs(boom)
    }

    "fail the stream when a concurrent task fails, await it when asked" in {
      val taskError = new RuntimeException("kickoff")
      Try(
        runAkka(
          Source.never[Int].toAsyncStream.concurrently(() => Future.failed(taskError)).toSource
        )
      ).failed.get shouldBe
        theSameInstanceAs(taskError)

      val task = Promise[Unit]()
      val result = AsyncStream(1)
        .concurrently(() => task.future, awaitCompletion = true)
        .toSource
        .runWith(Sink.seq)
      Thread.sleep(50)
      result.isCompleted shouldBe false
      task.success(())
      await(result) shouldBe Seq(1)
    }

    "run a hub source with exactly one bridge" in {
      val hub = AsyncBroadcastHub(AsyncStream(1, 2, 3), bufferSize = 8)
      val (result, count) = bridges(runAkka(hub.source().toSource))
      count shouldBe 1
      result.size should be <= 3
      hub.shutdown()
    }

    "convert Bytes to ByteString and back without copying" in {
      val array = Array[Byte](1, 2, 3)
      val bytes = Bytes.unsafeWrap(array)
      val viaAkka = runAkka(AsyncStream(bytes).toByteStringSource)
      viaAkka shouldBe Seq(ByteString(1, 2, 3))
      viaAkka.head.toArrayUnsafe() should be theSameInstanceAs array
    }
  }

  "the Akka compiler (regressions)" should {

    "keep the completion behind a pending hook or awaited task while the downstream pulls" in {
      val hook = Promise[Unit]()
      val withHook = AsyncStream(1, 2)
        .guaranteeCase(_ => hook.future)
        .evalMap(i => Future { Thread.sleep(10); i })
        .toSource
        .runWith(Sink.seq)
      Thread.sleep(200) // the evalMap pulls again while the hook is pending
      hook.success(())
      await(withHook) shouldBe Seq(1, 2)

      val task = Promise[Unit]()
      val withTask = AsyncStream(1, 2)
        .concurrently(() => task.future, awaitCompletion = true)
        .evalMap(i => Future { Thread.sleep(10); i })
        .toSource
        .runWith(Sink.seq)
      Thread.sleep(200)
      task.success(())
      await(withTask) shouldBe Seq(1, 2)
    }

    "run a recursive concat in constant memory, opening each next stream on demand" in {
      def ones: AsyncStream[Int] = AsyncStream.single(1) ++ ones
      await(ones.take(100000).toSource.runWith(Sink.fold(0)(_ + _))) shouldBe 100000

      def deferred: AsyncStream[Int] = AsyncStream.defer(AsyncStream.single(1) ++ deferred)
      await(deferred.take(100000).toSource.runWith(Sink.fold(0)(_ + _))) shouldBe 100000

      // no read-ahead: a next stream (a request, in a polling loop) never starts early
      val opened = new AtomicInteger()
      def polls: AsyncStream[Int] =
        AsyncStream.defer(AsyncStream.single(opened.incrementAndGet())) ++ polls
      runAkka(polls.take(3).toSource) shouldBe Seq(1, 2, 3)
      opened.get shouldBe 3
      runAkka(
        (AsyncStream(1) ++ (AsyncStream(2) ++ AsyncStream(3)) ++ AsyncStream(4)).toSource
      ) shouldBe
        Seq(1, 2, 3, 4)
    }

    "give a toFlow pipe its input without a subscription timeout, cancelling an unopened one" in {
      // the pipe opens its input only after a slow first stream (300 ms > the 100 ms timeout)
      val late = AsyncPipe.fromFunction[Int, Int](in =>
        AsyncStream.future(
          akka.pattern.after(300.millis, system.scheduler)(Future.successful(0))
        ) ++ in
      )
      val delayed = Source(1 to 3)
        .via(late.toFlow)
        .withAttributes(
          ActorAttributes.streamSubscriptionTimeout(
            100.millis,
            StreamSubscriptionTimeoutTerminationMode.cancel
          )
        )
      runAkka(delayed) shouldBe Seq(0, 1, 2, 3)

      // never opened: the upstream is cancelled when the pipe's output ends
      val cancelled = Promise[Unit]()
      val upstream = Source.maybe[Int].watchTermination() {
        (
          _,
          done
        ) =>
          done.onComplete(_ => cancelled.trySuccess(()))
          NotUsed
      }
      runAkka(
        upstream.via(AsyncPipe.fromFunction[Int, Int](_ => AsyncStream(7, 8)).toFlow)
      ) shouldBe
        Seq(7, 8)
      Await.result(cancelled.future, 3.seconds) shouldBe (())
    }

    "request bufferSize from a publisher (rounded up to a power of two)" in {
      def firstRequest(bufferSize: Int): Long = {
        val requests = new ConcurrentLinkedQueue[java.lang.Long]()
        val publisher = new JFlow.Publisher[Int] {
          def subscribe(subscriber: JFlow.Subscriber[_ >: Int]): Unit =
            subscriber.onSubscribe(new JFlow.Subscription {
              private val sent = new java.util.concurrent.atomic.AtomicBoolean(false)
              def request(n: Long): Unit = {
                requests.add(n)
                if (sent.compareAndSet(false, true)) subscriber.onNext(1)
              }
              def cancel(): Unit = ()
            })
        }
        runAkka(
          AsyncStream.fromPublisher(() => publisher, bufferSize).toSource.take(1)
        ) shouldBe
          Seq(1)
        requests.peek().longValue()
      }
      firstRequest(1) shouldBe 1L
      firstRequest(5) shouldBe 8L
      firstRequest(16) shouldBe 16L
      firstRequest(Int.MaxValue) shouldBe SourceCompiler.MaxInputBuffer.toLong // preallocated
    }

    "complete a concat as soon as its last stream ended - without further demand" in {
      // a subscriber that requests exactly the two elements, then only waits
      val completed = Promise[Seq[Int]]()
      val received = new ConcurrentLinkedQueue[Int]()
      (AsyncStream(1) ++ AsyncStream(2)).toSource
        .runWith(JavaFlowSupport.Sink.asPublisher[Int](fanout = false))
        .subscribe(new JFlow.Subscriber[Int] {
          def onSubscribe(s: JFlow.Subscription): Unit = s.request(2)
          def onNext(item: Int): Unit = { received.add(item); () }
          def onError(e: Throwable): Unit = completed.tryFailure(e)
          def onComplete(): Unit = completed.trySuccess(received.asScala.toList)
        })
      Await.result(completed.future, 3.seconds) shouldBe Seq(1, 2)
    }

    "cancel a toFlow input when the pipe's function throws on a run" in {
      val runs = new AtomicInteger()
      val throwing = AsyncPipe.fromFunction[Int, Int] { in =>
        // the first call is toFlow's own (compile-time) look at the pipe
        if (runs.incrementAndGet() > 1) throw new IllegalStateException("per-run failure")
        in
      }
      val cancelled = Promise[Unit]()
      val upstream = Source.maybe[Int].watchTermination() {
        (
          _,
          done
        ) =>
          done.onComplete(_ => cancelled.trySuccess(()))
          NotUsed
      }
      Try(runAkka(upstream.via(throwing.toFlow))).failed.get.getMessage shouldBe
        "per-run failure"
      Await.result(cancelled.future, 3.seconds) shouldBe (())
    }
  }

  "the opt-in conversions" should {

    "let 1.x Source code compile against an AsyncStream" in {
      import io.cequence.wsclient.stream.SourceConversions._
      val stream: AsyncStream[Int] = AsyncStream(1, 2, 3)
      await(stream.runWith(Sink.seq)) shouldBe Seq(1, 2, 3) // via the conversion
      await(stream.runToSeq) shouldBe Seq(1, 2, 3) // the AsyncStream member wins
      val source: Source[Int, NotUsed] = stream
      val back: AsyncStream[Int] = source
      back.toSource should be theSameInstanceAs source
      val pipe: AsyncPipe[Int, Int] = Flow[Int].map(_ + 1)
      val flow: Flow[Int, Int, NotUsed] = pipe
      runAkka(Source(1 to 2).via(flow)) shouldBe Seq(2, 3)
      Keep.none // keep the import used
    }
  }
}
