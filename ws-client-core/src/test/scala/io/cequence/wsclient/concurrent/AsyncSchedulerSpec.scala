package io.cequence.wsclient.concurrent

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit, TimeoutException}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

class AsyncSchedulerSpec extends AnyWordSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  "the default AsyncScheduler" should {

    "be found in implicit scope and run a task once, on a daemon thread" in {
      val scheduler = implicitly[AsyncScheduler]
      val latch = new CountDownLatch(1)
      @volatile var daemon = false
      @volatile var name = ""
      scheduler.scheduleOnce(10.millis) { () =>
        daemon = Thread.currentThread().isDaemon
        name = Thread.currentThread().getName
        latch.countDown()
      }
      latch.await(10, TimeUnit.SECONDS) shouldBe true
      daemon shouldBe true
      name should startWith("ws-client-scheduler")
    }

    "cancel a task that has not run" in {
      val runs = new AtomicInteger()
      val cancellable =
        AsyncScheduler.default.scheduleOnce(200.millis)(() => runs.incrementAndGet())
      cancellable.cancel() shouldBe true
      cancellable.isCancelled shouldBe true
      cancellable.cancel() shouldBe false
      Thread.sleep(300)
      runs.get() shouldBe 0
    }

    "wrap a caller-owned executor" in {
      val executor = Executors.newSingleThreadScheduledExecutor()
      try {
        val promise = Promise[Unit]()
        AsyncScheduler.fromExecutor(executor).scheduleOnce(1.milli)(() => promise.success(()))
        await(promise.future) shouldBe (())
      } finally executor.shutdown()
    }
  }

  "Futures" should {

    "delay and run after a delay, off the scheduler thread" in {
      val start = System.nanoTime()
      await(Futures.delay(50.millis))
      (System.nanoTime() - start) should be >= 50.millis.toNanos

      val thread =
        await(Futures.after(10.millis)(Future.successful(Thread.currentThread().getName)))
      thread should not startWith "ws-client-scheduler"
      await(Futures.after(1.milli)(Future(41 + 1))) shouldBe 42
      Try(
        await(Futures.after(1.milli)(throw new IllegalStateException("x")))
      ).failed.get shouldBe an[
        IllegalStateException
      ]
    }

    "time out a slow future with a TimeoutException or the given error" in {
      Try(await(Futures.timeout(Promise[Int]().future, 20.millis))).failed.get shouldBe a[
        TimeoutException
      ]
      val custom = new IllegalStateException("too slow")
      Try(await(Futures.timeout(Promise[Int]().future, 20.millis, custom))).failed.get shouldBe
        theSameInstanceAs(custom)
      await(Futures.timeout(Future.successful(1), 20.millis)) shouldBe 1
      await(Futures.timeout(Future(2), 5.seconds)) shouldBe 2
    }

    "fall back when the scheduler refuses the delay" in {
      implicit val refusing: AsyncScheduler = new AsyncScheduler {
        def scheduleOnce(delay: FiniteDuration)(task: () => Unit): AsyncCancellable =
          throw new IllegalArgumentException(s"cannot schedule $delay")
      }
      Try(await(Futures.after(1.milli)(Future.successful(1)))).failed.get shouldBe an[
        IllegalArgumentException
      ]
      await(Futures.timeout(Future.successful(1), 1.day)) shouldBe 1
      val slow = Promise[Int]()
      val bounded = Futures.timeout(slow.future, 1.day)
      slow.success(3)
      await(bounded) shouldBe 3
    }
  }
}
