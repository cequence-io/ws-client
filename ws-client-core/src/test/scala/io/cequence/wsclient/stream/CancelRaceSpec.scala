package io.cequence.wsclient.stream

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

/** Cancelling a run at random moments - racing an outstanding pull - must be clean. */
class CancelRaceSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  // cancels land anywhere from before the run started to well into it
  private def spinRandomly(): Unit = {
    val until = System.nanoTime() + ThreadLocalRandom.current().nextInt(300) * 1000L
    while (System.nanoTime() < until) Thread.onSpinWait()
  }

  "a cancel racing the run" should {

    "complete the run, fire the termination hook once and cancel the leaf (10k runs)" in {
      val iterations = 10000
      val hookCounts = new ConcurrentLinkedQueue[AtomicInteger]()
      val leafCancels = new ConcurrentLinkedQueue[TrackedPublisher[Int]]()

      (1 to iterations).foreach { i =>
        val publisher = new TrackedPublisher(1 to 50)
        val hooks = new AtomicInteger()
        hookCounts.add(hooks)
        leafCancels.add(publisher)

        val stream = publisher.stream
          .evalMap(x => if (i % 2 == 0) Future(x) else Future.successful(x))
          .map(_ + 1)
          .flatMapConcat(x => AsyncStream(x, x))
          .onTerminate(_ => hooks.incrementAndGet())

        val run = stream.run(_ => ())
        spinRandomly()
        run.cancel()
        // done completes (cancelled or finished) - never hangs
        await(run.done, 10.seconds)
      }

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
      while (hookCounts.asScala.exists(_.get() != 1) && System.nanoTime() < deadline)
        Thread.sleep(10)
      hookCounts.asScala.map(_.get()).toSet shouldBe Set(1)
      // every run either completed or had its subscription cancelled - at most once
      leafCancels.asScala.map(_.cancellations.get()).toSet.subsetOf(Set(0, 1)) shouldBe true
    }

    "close every opened blocking source exactly once (2k runs)" in {
      val opens, closes = new AtomicInteger()
      (1 to 2000).foreach { _ =>
        val run = AsyncStream
          .fromBlockingIterator(
            () => {
              opens.incrementAndGet()
              Iterator.from(0)
            },
            () => closes.incrementAndGet()
          )
          .run(_ => ())
        spinRandomly()
        run.cancel()
        await(run.done, 10.seconds)
      }
      // a cancel before the first read never opens the source - nothing to close then
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
      while (closes.get() < opens.get() && System.nanoTime() < deadline) Thread.sleep(10)
      Thread.sleep(50)
      closes.get() shouldBe opens.get()
      info(s"${opens.get()} of 2000 runs opened their source before the cancel")
    }
  }
}
