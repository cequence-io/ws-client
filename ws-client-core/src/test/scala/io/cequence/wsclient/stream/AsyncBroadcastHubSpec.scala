package io.cequence.wsclient.stream

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import io.cequence.wsclient.stream.impl.{Cursor, FromCursor}

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

class AsyncBroadcastHubSpec extends AnyWordSpec with Matchers with StreamTestSupport {

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

  // a source the test feeds element by element
  private final class Feed {
    private val promises = Vector.fill(1000)(Promise[Option[Int]]())
    private val index = new AtomicInteger()
    val pulled = new AtomicInteger()
    def emit(i: Int): Unit = promises(index.getAndIncrement()).success(Some(i))
    def complete(): Unit = promises(index.getAndIncrement()).success(None)
    def fail(e: Throwable): Unit = promises(index.getAndIncrement()).failure(e)
    def stream: AsyncStream[Int] =
      AsyncStream
        .fromIterator(() => Iterator.from(0))
        .evalMap { i =>
          pulled.incrementAndGet()
          promises(i).future
        }
        .takeWhile(_.isDefined)
        .map(_.get)
  }

  "AsyncBroadcastHub" should {

    "broadcast every element to every registered consumer" in {
      val feed = new Feed
      val hub = AsyncBroadcastHub(feed.stream, bufferSize = 16)
      val a = hub.source().runToSeq
      val b = hub.source().runToSeq
      eventually(hub.consumerCount shouldBe 2)
      (1 to 5).foreach(feed.emit)
      feed.complete()
      await(a) shouldBe (1 to 5)
      await(b) shouldBe (1 to 5)
      await(hub.completion) shouldBe (())
      eventually(hub.consumerCount shouldBe 0)
    }

    "emit the prefix first, buffering live elements meanwhile" in {
      val feed = new Feed
      val hub = AsyncBroadcastHub(feed.stream, bufferSize = 16)
      val ready = Promise[Int]()
      val result = hub.source(prefix = AsyncStream.future(ready.future)).runToSeq
      eventually(hub.consumerCount shouldBe 1)
      feed.emit(1)
      feed.emit(2)
      Thread.sleep(50)
      ready.success(0)
      feed.emit(3)
      feed.complete()
      await(result) shouldBe Seq(0, 1, 2, 3)
    }

    "be paced by the slowest consumer (bufferSize)" in {
      val feed = new Feed
      val hub = AsyncBroadcastHub(feed.stream, bufferSize = 8)
      val slow = Promise[Unit]()
      val slowRun = hub
        .source()
        .evalMap(i => if (i == 0) slow.future.map(_ => i) else Future.successful(i))
        .take(20)
        .runToSeq
      val fast = hub.source().take(5).runToSeq
      eventually(hub.consumerCount shouldBe 2) // both registered before any element exists
      (0 until 40).foreach(feed.emit)

      await(fast) shouldBe (0 until 5)
      // the slow consumer holds element 0: the hub buffers at most 8 more, then waits
      eventually(feed.pulled.get() should be >= 9)
      Thread.sleep(100)
      feed.pulled.get() should be <= 10
      slow.success(())
      await(slowRun) shouldBe (0 until 20)
      hub.shutdown()
    }

    "unregister a cancelled consumer (leak-free)" in {
      val hub =
        AsyncBroadcastHub(AsyncStream.fromIterator(() => Iterator.from(0)), bufferSize = 4)
      val run = hub.source().run(_ => ())
      eventually(hub.consumerCount shouldBe 1)
      run.cancel()
      await(run.done)
      eventually(hub.consumerCount shouldBe 0)

      // cancelled while still in the prefix
      val prefixRun =
        hub.source(prefix = AsyncStream.future(Promise[Int]().future)).run(_ => ())
      eventually(hub.consumerCount shouldBe 1)
      prefixRun.cancel()
      eventually(hub.consumerCount shouldBe 0)
      hub.shutdown()
    }

    "back-pressure without consumers, or drain them with drain = true" in {
      val feed = new Feed
      val hub = AsyncBroadcastHub(feed.stream, bufferSize = 4)
      (1 to 10).foreach(feed.emit)
      eventually(feed.pulled.get() shouldBe 4) // the buffer is full: no further pull
      Thread.sleep(50)
      feed.pulled.get() shouldBe 4
      val result = hub.source().take(10).runToSeq
      await(result) shouldBe (1 to 10) // the buffered ones first: nothing was lost
      hub.shutdown()

      val drained =
        AsyncBroadcastHub(AsyncStream.fromIterable(1 to 100), bufferSize = 4, drain = true)
      await(drained.completion) shouldBe (())
      run(drained.source()) shouldBe Nil // registered after completion: completes at once
    }

    "fail every consumer with the upstream failure, and late ones at once" in {
      val feed = new Feed
      val hub = AsyncBroadcastHub(feed.stream, bufferSize = 4)
      val a = hub.source().runToSeq
      eventually(hub.consumerCount shouldBe 1)
      val boom = new RuntimeException("boom")
      feed.emit(1)
      feed.fail(boom)
      Try(await(a)).failed.get shouldBe theSameInstanceAs(boom)
      Try(await(hub.completion)).failed.get shouldBe theSameInstanceAs(boom)
      runFailure(hub.source()) shouldBe theSameInstanceAs(boom)
    }

    "complete every consumer on shutdown" in {
      val hub = AsyncBroadcastHub(AsyncStream.future(Promise[Int]().future), bufferSize = 4)
      val a = hub.source().runToSeq
      eventually(hub.consumerCount shouldBe 1)
      hub.shutdown()
      await(a) shouldBe Nil
      await(hub.completion) shouldBe (())
    }

    "keep the shutdown when the cancelled pull ends in a failure or an element" in {
      // an upstream whose pending pull answers the cancel with `late` - a result already in
      // flight when the shutdown came
      def hubOver(late: Try[Option[Int]]): (AsyncBroadcastHub[Int], CountDownLatch) = {
        val pulled = new CountDownLatch(1)
        val pending = Promise[Option[Int]]()
        val cursor = new Cursor[Int] {
          def next(): Future[Option[Int]] = { pulled.countDown(); pending.future }
          def cancel(): Unit = pending.tryComplete(late)
        }
        (AsyncBroadcastHub(FromCursor[Int](_ => cursor, "test"), bufferSize = 4), pulled)
      }

      Seq[Try[Option[Int]]](Failure(new RuntimeException("late")), Success(Some(1))).foreach {
        late =>
          val (hub, pulled) = hubOver(late)
          val a = hub.source().runToSeq
          eventually(hub.consumerCount shouldBe 1)
          pulled.await(10, TimeUnit.SECONDS) shouldBe true
          hub.shutdown()
          await(a) shouldBe Nil
          await(hub.completion) shouldBe (())
          run(hub.source()) shouldBe Nil // a later consumer: completed, as the shutdown said
      }
    }
  }
}
