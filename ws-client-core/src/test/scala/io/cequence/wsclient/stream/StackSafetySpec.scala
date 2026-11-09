package io.cequence.wsclient.stream

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration._

/** Deep plans and long synchronous runs must neither overflow the stack nor leak memory. */
class StackSafetySpec extends AnyWordSpec with Matchers with StreamTestSupport {

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
      // warm-up, then measure
      await(pipeline.runDrain, 60.seconds)
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
      val elementsPerSecond = n / ((System.nanoTime() - start) / 1e9)
      info(f"$elementsPerSecond%.0f elements/s")
      elementsPerSecond should be > 1000000.0
    }
  }
}
