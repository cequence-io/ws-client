package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.CequenceWSException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Flow, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Try

/** Deep plans and long synchronous runs must neither overflow the stack nor leak memory. */
class StackSafetySpec extends AnyWordSpec with Matchers with StreamTestSupport {

  /** Requests everything; `terminated` completes (fails) with the stream. */
  private final class UnboundedSubscriber[T] extends Flow.Subscriber[T] {
    val subscription = new AtomicReference[Flow.Subscription]()
    val terminated: Promise[Unit] = Promise[Unit]()

    override def onSubscribe(s: Flow.Subscription): Unit = {
      subscription.set(s)
      s.request(Long.MaxValue)
    }
    override def onNext(item: T): Unit = ()
    override def onError(t: Throwable): Unit = terminated.tryFailure(t)
    override def onComplete(): Unit = terminated.trySuccess(())
  }

  "the neutral interpreter" should {

    "compile and run 100k chained maps" in {
      val stream = (1 to 100000).foldLeft(AsyncStream(0, 1)) {
        (
          s,
          _
        ) => s.map(_ + 1)
      }
      run(stream) shouldBe Seq(100000, 100001)
      stream.describe.length should be > 0 // the spine walk is iterative too
    }

    "run 10k left-nested concats" in {
      val stream = (1 to 10000).foldLeft(AsyncStream.single(0)) {
        (
          s,
          i
        ) => s ++ AsyncStream.single(i)
      }
      run(stream) shouldBe (0 to 10000)
    }

    "run 10k right-nested concats" in {
      def nested(i: Int): AsyncStream[Int] =
        if (i == 10000) AsyncStream.single(i) else AsyncStream.single(i) ++ nested(i + 1)
      run(nested(0)) shouldBe (0 to 10000)
    }

    "run a recursive concat in constant stack (1e6 iterations)" in {
      def countdown(n: Int): AsyncStream[Int] =
        AsyncStream.defer {
          if (n == 0) AsyncStream.empty else AsyncStream.single(n) ++ countdown(n - 1)
        }
      await(countdown(1000000).runFold(0L)(_ + _), 60.seconds) shouldBe 500000500000L
    }

    "run an infinite recursive concat in constant memory" in {
      def ones: AsyncStream[Int] = AsyncStream.single(1) ++ ones
      await(ones.take(1000000).runFold(0L)(_ + _), 60.seconds) shouldBe 1000000L
    }

    "resolve a 100k-deep chain of defers" in {
      val stream = (1 to 100000).foldLeft(AsyncStream(42)) {
        (
          s,
          _
        ) => AsyncStream.defer(s)
      }
      run(stream) shouldBe Seq(42)
    }

    "drop 1e6 elements synchronously with a filter" in {
      val stream =
        AsyncStream.fromIterator(() => Iterator.range(0, 1000000)).filter(_ == 999999)
      await(stream.runToSeq, 60.seconds) shouldBe Seq(999999)
    }

    "flatMap 1e5 empty inner streams" in {
      val stream =
        AsyncStream
          .fromIterator(() => Iterator.range(0, 100000))
          .flatMapConcat(_ => AsyncStream.empty[Int]) ++
          AsyncStream.single(1)
      run(stream) shouldBe Seq(1)
    }

    "run 1e5 synchronous evalMap futures" in {
      val stream = AsyncStream
        .fromIterator(() => Iterator.range(0, 100000))
        .evalMap(i => Future.successful(i))
      await(stream.runFold(0L)(_ + _), 60.seconds) shouldBe (0L until 100000L).sum
    }

    "push 1e6 elements through 5 fused stages at more than 1M elements/s" in {
      val n = 1000000
      def pipeline =
        AsyncStream
          .fromIterator(() => Iterator.range(0, n))
          .map(_ + 1)
          .filter(_ > 0)
          .map(_ * 2)
          .mapConcat(i => i :: Nil)
          .zipWithIndex
      // warm-up, then the best of three measurements: a wall-clock floor on a shared machine (a
      // loaded CI runner, parallel test JVMs) needs one undisturbed run, not every run undisturbed
      await(pipeline.runDrain, 60.seconds)
      def measure(): Double = {
        val start = System.nanoTime()
        await(
          pipeline.runFold(0L)(
            (
              acc,
              p
            ) => acc + p._2
          ),
          60.seconds
        ) shouldBe (0L until n.toLong).sum
        n / ((System.nanoTime() - start) / 1e9)
      }
      val elementsPerSecond = Seq.fill(3)(measure()).max
      info(f"$elementsPerSecond%.0f elements/s (best of 3)")
      elementsPerSecond should be > 1000000.0
    }
  }

  // AsyncStream has no unfold: recursion is how a stream is paged, polled or reconnected
  "a recursive stream (the recursive call in tail position)" should {

    "page through flatMapConcat at a constant depth (1e5 pages)" in {
      val pages = 100000
      def page(i: Int): AsyncStream[Int] =
        AsyncStream.future(Future.successful(i)).flatMapConcat { p =>
          if (p == pages) AsyncStream(p, p) else AsyncStream(p, p) ++ page(p + 1)
        }
      await(page(0).runFold(0L)(_ + _), 60.seconds) shouldBe 2 * (0L to pages.toLong).sum
    }

    "page through flatMapConcat over asynchronous loads" in {
      val pages = 100000
      def page(i: Int): AsyncStream[Int] =
        AsyncStream.future(Future(i)).flatMapConcat { p =>
          if (p == pages) AsyncStream.single(p) else AsyncStream.single(p) ++ page(p + 1)
        }
      await(page(0).runFold(0L)(_ + _), 60.seconds) shouldBe (0L to pages.toLong).sum
    }

    "page through flatMapConcat with deferred and mapped loads" in {
      val pages = 100000
      def page(i: Int): AsyncStream[Int] =
        AsyncStream.single(i).map(_ + 1).flatMapConcat { p =>
          AsyncStream.defer {
            if (p == pages) AsyncStream.empty[Int] else AsyncStream.single(p) ++ page(p)
          }
        }
      await(page(0).runFold(0L)(_ + _), 60.seconds) shouldBe (1L until pages.toLong).sum
    }

    "poll through flatMapConcat without emitting (1e6 synchronous polls)" in {
      val polls = 1000000
      def poll(i: Int): AsyncStream[Int] =
        AsyncStream.future(Future.successful(i)).flatMapConcat { attempt =>
          if (attempt == polls) AsyncStream.single(attempt) else poll(attempt + 1)
        }
      await(poll(0).runToSeq, 60.seconds) shouldBe Seq(polls)
    }

    "poll through flatMapConcat without emitting (1e5 asynchronous polls)" in {
      val polls = 100000
      def poll(i: Int): AsyncStream[Int] =
        AsyncStream.future(Future(i)).flatMapConcat { attempt =>
          if (attempt == polls) AsyncStream.single(attempt) else poll(attempt + 1)
        }
      await(poll(0).runToSeq, 60.seconds) shouldBe Seq(polls)
    }

    "reconnect through recoverWith at a constant depth (1e5 reconnects)" in {
      val reconnects = 100000
      def connect(attempt: Int): AsyncStream[Int] =
        if (attempt == reconnects)
          AsyncStream.single(attempt)
        else
          (AsyncStream.single(attempt) ++ AsyncStream.failed[Int](
            new IOException("dropped")
          )).recoverWith { case _: IOException => connect(attempt + 1) }
      await(connect(0).runFold(0L)(_ + _), 60.seconds) shouldBe (0L to reconnects.toLong).sum
    }

    "continue through futureStream at a constant depth (1e5 steps)" in {
      val steps = 100000
      def step(i: Int): AsyncStream[Int] =
        AsyncStream.futureStream(
          Future.successful(
            if (i == steps) AsyncStream.empty[Int] else AsyncStream.single(i) ++ step(i + 1)
          )
        )
      await(step(0).runFold(0L)(_ + _), 60.seconds) shouldBe (0L until steps.toLong).sum
    }

    "stay cancellable: an early stop reaches the stream the recursion has arrived at" in {
      val depth = 10000
      val terminations = new ConcurrentLinkedQueue[Termination]()
      def page(i: Int): AsyncStream[Int] =
        AsyncStream.future(Future.successful(i)).flatMapConcat { p =>
          if (p == depth)
            AsyncStream
              .fromIterator(() => Iterator.from(p))
              .onTerminate(t => terminations.add(t))
          else
            AsyncStream.single(p) ++ page(p + 1)
        }
      await(page(0).take(depth + 5L).runToSeq, 60.seconds) shouldBe (0 until depth + 5)
      terminations.asScala.toList shouldBe List(Termination.Cancelled)
    }

    "stay cancellable from another thread while a load is pending" in {
      val depth = 10000
      val pendingLoad = Promise[Int]()
      val reached = new CountDownLatch(1)
      val terminations = new ConcurrentLinkedQueue[Termination]()
      def load(i: Int): AsyncStream[Int] =
        if (i < depth) AsyncStream.future(Future.successful(i))
        else
          AsyncStream.future {
            reached.countDown()
            pendingLoad.future
          }.onTerminate(t => terminations.add(t))
      def page(i: Int): AsyncStream[Int] =
        load(i).flatMapConcat(p => AsyncStream.single(p) ++ page(p + 1))

      val subscriber = new UnboundedSubscriber[Int]
      page(0).toPublisher.subscribe(subscriber)
      reached.await(60, TimeUnit.SECONDS) shouldBe true
      subscriber.subscription.get().cancel()

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while (terminations.isEmpty && System.nanoTime() < deadline) Thread.sleep(10)
      terminations.asScala.toList shouldBe List(Termination.Cancelled)
    }
  }

  "a recursive stream (unobservable shortcuts)" should {

    "never ask a lazy upstream for its end ahead of the pull" in {
      val events = new ConcurrentLinkedQueue[String]()
      def recorded(prefix: String): Iterator[Int] = new Iterator[Int] {
        private val underlying = Iterator(1, 2)
        def hasNext: Boolean = {
          events.add(s"$prefix.hasNext")
          underlying.hasNext
        }
        def next(): Int = {
          events.add(s"$prefix.next")
          underlying.next()
        }
      }
      val lazyIterable = new scala.collection.immutable.Iterable[Int] {
        def iterator: Iterator[Int] = recorded("iterable")
      }
      def inner(a: Int) = {
        events.add(s"f($a)")
        AsyncStream(a * 10).map { b =>
          events.add(s"emit $b")
          b
        }
      }
      def expected(prefix: String) = List(
        s"$prefix.hasNext",
        s"$prefix.next",
        "f(1)",
        "emit 10",
        s"$prefix.hasNext",
        s"$prefix.next",
        "f(2)",
        "emit 20",
        s"$prefix.hasNext"
      )

      run(AsyncStream.fromIterator(() => recorded("iterator")).flatMapConcat(inner)) shouldBe
        Seq(10, 20)
      events.asScala.toList shouldBe expected("iterator")

      events.clear()
      run(AsyncStream.fromIterable(lazyIterable).flatMapConcat(inner)) shouldBe Seq(10, 20)
      events.asScala.toList shouldBe expected("iterable")
    }

    "run the termination hooks of a recursive stream innermost first, each once" in {
      val events = new ConcurrentLinkedQueue[String]()
      def page(i: Int): AsyncStream[Int] =
        AsyncStream
          .future(Future.successful(i))
          .onTerminate(t => events.add(s"load $i: $t"))
          .flatMapConcat { p =>
            if (p == 2) AsyncStream.single(p) else AsyncStream.single(p) ++ page(p + 1)
          }
          .onTerminate(t => events.add(s"page $i: $t"))
      run(page(0)) shouldBe Seq(0, 1, 2)
      events.asScala.toList shouldBe List(
        "load 2: Completed",
        "page 2: Completed",
        "load 1: Completed",
        "page 1: Completed",
        "load 0: Completed",
        "page 0: Completed"
      )
    }

    "recover a failure only where recoverWith covers it" in {
      // the recovery stream's own failure is not recovered again - and a skipped cursor must
      // not change that
      val boom = new IOException("second failure")
      def connect(attempt: Int): AsyncStream[Int] =
        (AsyncStream.single(attempt) ++ AsyncStream.failed[Int](
          new IOException("dropped")
        )).recoverWith {
          case _: IOException if attempt < 3 => connect(attempt + 1)
          case _: IOException => AsyncStream.single(-1) ++ AsyncStream.failed(boom)
        }
      val seen = new ConcurrentLinkedQueue[Int]()
      val failure = Try(await(connect(0).runForeach(a => seen.add(a)))).failed.get
      failure shouldBe theSameInstanceAs(boom)
      seen.asScala.toList shouldBe List(0, 1, 2, 3, -1)
    }
  }

  "a recursion that is not in tail position" should {

    "fail the run when nested deeper than the stack - never leave it incomplete" in {
      // the map applied to the recursive call's result keeps one cursor per level
      def nested(i: Int): AsyncStream[Int] =
        AsyncStream.single(i).flatMapConcat { a =>
          if (a == 1000000) AsyncStream.single(a) else nested(a + 1).map(_ + 1)
        }
      val failure = Try(await(nested(0).runToSeq, 60.seconds)).failed.get
      failure shouldBe a[CequenceWSException]
      failure.getCause shouldBe a[StackOverflowError]
      failure.getMessage should include("nested too deeply")

      // the pool thread that overflowed keeps working
      run(AsyncStream(1, 2, 3)) shouldBe Seq(1, 2, 3)
    }

    "fail a published run the same way" in {
      def nested(i: Int): AsyncStream[Int] =
        AsyncStream.single(i).flatMapConcat { a =>
          if (a == 1000000) AsyncStream.single(a) else nested(a + 1).map(_ + 1)
        }
      val subscriber = new UnboundedSubscriber[Int]
      nested(0).toPublisher.subscribe(subscriber)
      val failure = Try(await(subscriber.terminated.future, 60.seconds)).failed.get
      failure shouldBe a[CequenceWSException]
      failure.getCause shouldBe a[StackOverflowError]
    }
  }
}
