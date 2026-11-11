package io.cequence.wsclient.stream

import io.cequence.wsclient.concurrent.Futures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  Executors,
  Flow,
  LinkedBlockingQueue,
  RejectedExecutionException,
  ThreadFactory,
  TimeUnit
}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

/** Threads, cancel windows and failure paths of the stream layer's publishers and helpers. */
class StreamEdgeCasesSpec extends AnyWordSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def await[T](f: Future[T]): T = Await.result(f, 15.seconds)

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

  "a stream published as a Flow.Publisher" should {

    "run its user functions on its ec, never on the thread that requests" in {
      val runEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor(new ThreadFactory {
          def newThread(r: Runnable): Thread = {
            val thread = new Thread(r, "stream-run-ec")
            thread.setDaemon(true)
            thread
          }
        })
      )
      try {
        val threads = new ConcurrentLinkedQueue[String]()
        val publisher = AsyncStream
          .fromIterable(1 to 20)
          .map { i =>
            threads.add(Thread.currentThread().getName)
            i
          }
          .toPublisher[Int](runEc)

        val received = new LinkedBlockingQueue[Integer]()
        val done = Promise[Unit]()
        val subscription = new AtomicReference[Flow.Subscription]()
        publisher.subscribe(new Flow.Subscriber[Int] {
          def onSubscribe(s: Flow.Subscription): Unit = subscription.set(s)
          def onNext(item: Int): Unit = received.add(item)
          def onError(e: Throwable): Unit = done.tryFailure(e)
          def onComplete(): Unit = done.trySuccess(())
        })
        eventually(subscription.get() should not be null)

        // every request from this (foreign) thread
        (1 to 20).foreach { i =>
          subscription.get().request(1)
          received.poll(5, TimeUnit.SECONDS) shouldBe i
        }
        await(done.future)
        threads.asScala.toSet shouldBe Set("stream-run-ec")
      } finally runEc.shutdownNow()
    }
  }

  "a stream published as a Flow.Publisher (rules and rejection)" should {

    "signal a request(0) made inside onSubscribe only after onSubscribe returned" in {
      val inSubscribe = new java.util.concurrent.atomic.AtomicBoolean(false)
      val overlapped = new java.util.concurrent.atomic.AtomicBoolean(false)
      val failed = Promise[Throwable]()
      val opened = new AtomicInteger()
      AsyncStream.defer {
        opened.incrementAndGet() // the run's leaf: never opened for a run that is over
        AsyncStream(1, 2, 3)
      }.toPublisher[Int]
        .subscribe(new Flow.Subscriber[Int] {
          def onSubscribe(s: Flow.Subscription): Unit = {
            inSubscribe.set(true)
            s.request(0)
            Thread.sleep(200) // the error must not arrive meanwhile (rule 1.3)
            inSubscribe.set(false)
          }
          def onNext(item: Int): Unit = ()
          def onError(e: Throwable): Unit = {
            if (inSubscribe.get()) overlapped.set(true)
            failed.trySuccess(e)
          }
          def onComplete(): Unit = ()
        })
      await(failed.future) shouldBe an[IllegalArgumentException]
      overlapped.get shouldBe false
      opened.get shouldBe 0
    }

    "keep draining a long synchronous stream iteratively when the ec starts rejecting" in {
      // the first task (the subscription's start) runs; every later one is rejected
      val accepted = new AtomicInteger()
      val pool = Executors.newSingleThreadExecutor()
      val rejectingLater = new ExecutionContext {
        def execute(runnable: Runnable): Unit =
          if (accepted.incrementAndGet() == 1) pool.execute(runnable)
          else throw new RejectedExecutionException("shut down")
        def reportFailure(cause: Throwable): Unit = ()
      }
      try {
        val count = new AtomicInteger()
        val done = Promise[Unit]()
        AsyncStream
          .fromIterable(1 to 200000)
          .toPublisher[Int](rejectingLater)
          .subscribe(new Flow.Subscriber[Int] {
            def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
            def onNext(item: Int): Unit = { count.incrementAndGet(); () }
            def onError(e: Throwable): Unit = done.tryFailure(e)
            def onComplete(): Unit = done.trySuccess(())
          })
        await(done.future) // a recursion per yield overflowed the stack here
        count.get shouldBe 200000
      } finally pool.shutdownNow()
    }
  }

  "DeferredPublisher" should {

    "signal a factory's null result as onError after onSubscribe" in {
      val signals = new ConcurrentLinkedQueue[String]()
      new DeferredPublisher[Int](() => null).subscribe(new Flow.Subscriber[Int] {
        def onSubscribe(s: Flow.Subscription): Unit = signals.add("subscribe")
        def onNext(item: Int): Unit = signals.add("next")
        def onError(e: Throwable): Unit = signals.add(e.getClass.getSimpleName)
        def onComplete(): Unit = signals.add("complete")
      })
      signals.asScala.toList shouldBe List("subscribe", "NullPointerException")
    }
  }

  "the timer helpers" should {

    "fail `after` when its ec rejects the delayed task" in {
      val rejecting = new ExecutionContext {
        def execute(runnable: Runnable): Unit =
          throw new RejectedExecutionException("shut down")
        def reportFailure(cause: Throwable): Unit = ()
      }
      Try(
        await(
          Futures.after(10.millis)(Future.successful(1))(
            implicitly[io.cequence.wsclient.concurrent.AsyncScheduler],
            rejecting
          )
        )
      ).failed.get shouldBe
        a[RejectedExecutionException]
    }

    "fail `timeout` with the exception a throwing `error` throws" in {
      Try(
        await(
          Futures.timeout(
            Promise[Int]().future,
            10.millis,
            error = throw new IllegalStateException("x")
          )
        )
      ).failed.get shouldBe an[IllegalStateException]
    }
  }

  "Futures.timeout" should {

    "fail with a TimeoutException, or with the given error - two overloads, no null default" in {
      Try(await(Futures.timeout(Promise[Int]().future, 10.millis))).failed.get shouldBe
        a[java.util.concurrent.TimeoutException]
      val custom = new IllegalStateException("custom")
      Try(await(Futures.timeout(Promise[Int]().future, 10.millis, custom))).failed.get shouldBe
        theSameInstanceAs(custom)
      await(Futures.timeout(Future.successful(1), 10.millis)) shouldBe 1
    }
  }

  "AsyncBroadcastHub" should {

    "never start its upstream when shut down before it ran" in {
      val queued = new java.util.ArrayDeque[Runnable]()
      val manual = new ExecutionContext {
        def execute(runnable: Runnable): Unit = queued.add(runnable)
        def reportFailure(cause: Throwable): Unit = ()
      }
      val started = new AtomicInteger()
      val hub = AsyncBroadcastHub(AsyncStream.defer {
        started.incrementAndGet()
        AsyncStream(1, 2)
      })(manual)
      hub.shutdown()
      while (!queued.isEmpty) queued.poll().run()
      started.get shouldBe 0
    }
  }
}
