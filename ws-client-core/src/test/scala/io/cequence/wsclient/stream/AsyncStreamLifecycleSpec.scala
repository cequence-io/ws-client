package io.cequence.wsclient.stream

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.Try

/** Termination hooks, concurrent tasks and blocking sources. */
class AsyncStreamLifecycleSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  private def eventually(assertion: => Unit): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var done = false
    while (!done) {
      try {
        assertion
        done = true
      } catch {
        case _: Throwable if System.nanoTime() < deadline => Thread.sleep(10)
      }
    }
  }

  "guaranteeCase" should {

    "report each termination cause exactly once" in {
      val causes = new ConcurrentLinkedQueue[Termination]()
      def tracked(stream: AsyncStream[Int]) = stream.onTerminate(t => causes.add(t))

      run(tracked(AsyncStream(1, 2))) shouldBe Seq(1, 2)
      val boom = new RuntimeException("boom")
      runFailure(
        tracked(AsyncStream(1) ++ AsyncStream.failed(boom))
      ) shouldBe theSameInstanceAs(boom)
      run(tracked(AsyncStream(1, 2, 3)).take(1)) shouldBe Seq(1)

      eventually(causes.size shouldBe 3)
      causes.asScala.toList shouldBe List(
        Termination.Completed,
        Termination.Failed(boom),
        Termination.Cancelled
      )
    }

    "deliver completion only after the hook's future completed" in {
      val hookDone = Promise[Unit]()
      val hookStarted = new CountDownLatch(1)
      val result = AsyncStream(1, 2).guaranteeCase { _ =>
        hookStarted.countDown()
        hookDone.future
      }.runToSeq

      hookStarted.await(10, TimeUnit.SECONDS) shouldBe true
      Thread.sleep(50)
      result.isCompleted shouldBe false
      hookDone.success(())
      await(result) shouldBe Seq(1, 2)
    }

    "complete the stateful end hook before the termination hook sees Completed" in {
      val order = new ConcurrentLinkedQueue[String]()
      val stream = AsyncStream(1, 2)
        .stateful(() => ())(
          (
            s,
            a
          ) => AsyncStream.Step(s, List(a)),
          _ => {
            order.add("end-hook")
            Nil
          }
        )
        .onTerminate(t => order.add(s"terminated: $t"))
      run(stream)
      order.asScala.toList shouldBe List("end-hook", "terminated: Completed")
    }

    "fail a completed run when the hook fails; suppress it on a failed run" in {
      val hookError = new IllegalStateException("hook")
      runFailure(AsyncStream(1).guaranteeCase(_ => Future.failed(hookError))) shouldBe
        theSameInstanceAs(hookError)
      runFailure(AsyncStream(1).onTerminate(_ => throw hookError)) shouldBe
        theSameInstanceAs(hookError)

      val boom = new RuntimeException("boom")
      val failure =
        runFailure(AsyncStream.failed[Int](boom).guaranteeCase(_ => Future.failed(hookError)))
      failure shouldBe theSameInstanceAs(boom)
      failure.getSuppressed.toList shouldBe List(hookError)
    }

    "call the hook after the upstream was cancelled" in {
      val publisher = new TrackedPublisher(1 to 1000)
      val cancelledBeforeHook = new AtomicBoolean(false)
      val hooked = new CountDownLatch(1)
      run(
        publisher.stream.onTerminate { t =>
          if (t == Termination.Cancelled)
            cancelledBeforeHook.set(publisher.cancellations.get() == 1)
          hooked.countDown()
        }.take(2)
      ) shouldBe Seq(1, 2)
      hooked.await(10, TimeUnit.SECONDS) shouldBe true
      cancelledBeforeHook.get() shouldBe true
    }

    "fire once per run, also when cancelled before the first element" in {
      val count = new AtomicInteger()
      val never =
        AsyncStream.future(Promise[Int]().future).onTerminate(_ => count.incrementAndGet())
      val streamRun = never.run(_ => ())
      Thread.sleep(50)
      streamRun.cancel()
      await(streamRun.done)
      eventually(count.get() shouldBe 1)
      Thread.sleep(50)
      count.get() shouldBe 1
    }
  }

  "concurrently" should {

    "start the task once per run, with the run" in {
      val starts = new AtomicInteger()
      val stream = AsyncStream(1, 2).concurrently { () =>
        starts.incrementAndGet()
        Future.unit
      }
      starts.get() shouldBe 0
      run(stream) shouldBe Seq(1, 2)
      run(stream) shouldBe Seq(1, 2)
      starts.get() shouldBe 2
    }

    "fail the stream and cancel the upstream when the task fails first" in {
      val publisher = new TrackedPublisher(1 to 10, hangAtEnd = true)
      val taskError = new RuntimeException("kickoff failed")
      val task = Promise[Unit]()
      val result = publisher.stream.concurrently(() => task.future).runToSeq
      eventually(publisher.subscriptions.get() shouldBe 1)
      task.failure(taskError)
      Try(await(result)).failed.get shouldBe theSameInstanceAs(taskError)
      eventually(publisher.cancellations.get() shouldBe 1)
    }

    "with awaitCompletion: complete only after the task, fail if it fails" in {
      val task = Promise[Unit]()
      val result =
        AsyncStream(1, 2).concurrently(() => task.future, awaitCompletion = true).runToSeq
      Thread.sleep(50)
      result.isCompleted shouldBe false
      task.success(())
      await(result) shouldBe Seq(1, 2)

      val taskError = new RuntimeException("post failed")
      val failing = Promise[Unit]()
      val failed =
        AsyncStream(1).concurrently(() => failing.future, awaitCompletion = true).runToSeq
      Thread.sleep(50)
      failing.failure(taskError)
      Try(await(failed)).failed.get shouldBe theSameInstanceAs(taskError)
    }

    "ignore the task after the stream ended (without awaitCompletion)" in {
      val task = Promise[Unit]()
      run(AsyncStream(1, 2).concurrently(() => task.future)) shouldBe Seq(1, 2)
      task.failure(new RuntimeException("late"))
    }

    "ignore the task's outcome on a downstream cancel" in {
      val task = Promise[Unit]()
      run(
        AsyncStream(1, 2, 3).concurrently(() => task.future, awaitCompletion = true).take(1)
      ) shouldBe
        Seq(1)
    }
  }

  "a blocking source" should {

    final class TrackedIterator(
      n: Int,
      blockAt: Option[Int] = None,
      failAt: Option[Int] = None
    ) {
      val threads = new ConcurrentLinkedQueue[String]()
      val closes = new AtomicInteger()
      val reading = new AtomicBoolean(false)
      val concurrentClose = new AtomicBoolean(false)
      val releaseBlock = new CountDownLatch(1)
      val blocked = new CountDownLatch(1)

      def stream: AsyncStream[Int] =
        AsyncStream.fromBlockingIterator(
          () => {
            threads.add(Thread.currentThread().getName)
            var i = 0
            new Iterator[Int] {
              def hasNext: Boolean = {
                reading.set(true)
                try {
                  if (blockAt.contains(i)) {
                    blocked.countDown()
                    releaseBlock.await(10, TimeUnit.SECONDS)
                  }
                  i < n
                } finally reading.set(false)
              }
              def next(): Int = {
                threads.add(Thread.currentThread().getName)
                if (failAt.contains(i)) throw new IllegalStateException(s"read $i failed")
                i += 1
                i
              }
            }
          },
          () => {
            if (reading.get()) concurrentClose.set(true)
            closes.incrementAndGet()
          }
        )
    }

    "read on the blocking pool and close once at the end" in {
      val source = new TrackedIterator(5)
      run(source.stream.map(i => (i, onEcThread))) shouldBe (1 to 5).map(i => (i, true))
      source.threads.asScala.forall(_.startsWith("ws-client-blocking-")) shouldBe true
      source.closes.get() shouldBe 1
      run(source.stream) shouldBe (1 to 5)
      source.closes.get() shouldBe 2
    }

    "close once on failure" in {
      val source = new TrackedIterator(5, failAt = Some(2))
      runFailure(source.stream) shouldBe an[IllegalStateException]
      eventually(source.closes.get() shouldBe 1)
    }

    "close once on cancel, after the read in progress returned" in {
      val source = new TrackedIterator(1000, blockAt = Some(3))
      val streamRun = source.stream.run(_ => ())
      source.blocked.await(10, TimeUnit.SECONDS) shouldBe true
      streamRun.cancel()
      await(streamRun.done) // completes promptly although the read still blocks
      source.closes.get() shouldBe 0
      source.releaseBlock.countDown()
      eventually(source.closes.get() shouldBe 1)
      Thread.sleep(50)
      source.closes.get() shouldBe 1
      source.concurrentClose.get() shouldBe false
    }

    "close the InputStream of fromInputStream" in {
      val closed = new AtomicInteger()
      val bytes = (0 until 20000).map(_.toByte).toArray
      val stream = AsyncStream.fromInputStream(
        () =>
          new java.io.ByteArrayInputStream(bytes) {
            override def close(): Unit = closed.incrementAndGet()
          },
        chunkSize = 4096
      )
      val chunks = run(stream)
      chunks.map(_.length) shouldBe Seq(4096, 4096, 4096, 4096, 3616)
      Bytes.concat(chunks).toArray.toSeq shouldBe bytes.toSeq
      closed.get() shouldBe 1

      run(stream.take(1)).size shouldBe 1
      eventually(closed.get() shouldBe 2)
    }
  }
}
