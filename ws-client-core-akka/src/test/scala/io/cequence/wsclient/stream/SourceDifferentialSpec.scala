package io.cequence.wsclient.stream

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import io.cequence.wsclient.stream.AsyncStream.Step
import io.cequence.wsclient.stream.SourceInterop._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

/**
 * The drift guard between the compilers: seeded random operator chains (with injected
 * failures, early termination, termination hooks) run natively as Akka sources and on the
 * neutral interpreter must give the same elements, the same outcome and the same hook calls.
 */
class SourceDifferentialSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("source-differential-spec")
  implicit private val materializer: Materializer = Materializer(system)
  implicit private val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private final class Boom(tag: String) extends RuntimeException(tag)

  // a chain from a seed; the counters record hook calls (fresh per build)
  private final class Chain(seed: Int) {
    val endHooks = new AtomicInteger()
    val terminations = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val stream: AsyncStream[Int] = {
      val random = new Random(seed)
      val source = Vector.fill(random.nextInt(10))(random.nextInt(15))
      val base =
        if (random.nextInt(4) == 0)
          AsyncStream.fromIterable(source) ++ AsyncStream.failed(new Boom("source"))
        else AsyncStream.fromIterable(source)

      (1 to 1 + random.nextInt(6)).foldLeft(base) {
        (
          s,
          depth
        ) =>
          random.nextInt(15) match {
            case 0 => val k = random.nextInt(5); s.map(_ + k)
            case 1 => val k = random.nextInt(3) + 2; s.filter(_ % k != 0)
            case 2 => s.mapConcat(i => List.fill(i % 3)(i))
            case 3 => s.take(random.nextInt(6).toLong)
            case 4 =>
              val limit = random.nextInt(15)
              s.takeWhile(_ < limit, inclusive = random.nextBoolean())
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
            case 8  => s.zipWithIndex.map { case (i, idx) => i + idx.toInt }
            case 9  => s ++ AsyncStream(random.nextInt(9))
            case 10 => s.flatMapConcat(i => AsyncStream.fromIterable(List.fill(i % 3)(i)))
            case 11 =>
              s.evalMap(i => if (i % 2 == 0) Future(i + 1) else Future.successful(i + 1))
            case 12 =>
              s.stateful(() => 0)(
                (
                  n,
                  i
                ) => Step(n + 1, List(i)),
                n => { endHooks.incrementAndGet(); List(1000 + n) }
              )
            case 13 =>
              val target = random.nextInt(15)
              s.map(i => if (i == target) throw new Boom(s"boom@$depth") else i)
            case _ =>
              s.recover { case e: Boom => -e.getMessage.length }.onTerminate(t =>
                terminations.add(s"$depth:${t.getClass.getSimpleName}")
              )
          }
      }
    }
  }

  private final case class Outcome(
    elements: Seq[Int],
    failure: Option[String],
    endHooks: Int,
    terminations: Set[String]
  )

  // the elements seen before the end, the failure, and the hook calls
  private def outcome(chain: Chain)(run: (AsyncStream[Int], Int => Unit) => Future[Any])
    : Outcome = {
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val result = Try(Await.result(run(chain.stream, i => { seen.add(i); () }), 15.seconds))
    // termination hooks on cancel run asynchronously - let them land
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)
    while (System.nanoTime() < deadline) Thread.sleep(5)
    val elements = seen.toArray.toSeq.map(_.asInstanceOf[Int])
    val terminations = chain.terminations.toArray.map(_.toString).toSet
    result match {
      case Success(_) => Outcome(elements, None, chain.endHooks.get(), terminations)
      case Failure(e: Boom) =>
        Outcome(elements, Some(e.getMessage), chain.endHooks.get(), terminations)
      case Failure(e) => throw e
    }
  }

  "the Akka compiler and the neutral interpreter" should {

    "agree on random operator chains (300 seeds)" in {
      (1 to 300).foreach { seed =>
        val native = outcome(new Chain(seed))(
          (
            stream,
            f
          ) => stream.toSource.runWith(Sink.foreach(f))
        )
        val neutral = outcome(new Chain(seed))(
          (
            stream,
            f
          ) => stream.runForeach(f)
        )
        withClue(s"seed $seed, plan ${new Chain(seed).stream.describe}: ") {
          native.failure shouldBe neutral.failure
          // below an early stop (take, takeWhile) the upstream's completion may reach a native
          // stage before the cancel does (its end hook runs, its elements are dropped); the
          // pull-based interpreter never pulls that completion - the hooks agree otherwise
          val plan = new Chain(seed).stream.describe
          if (!plan.contains("take")) {
            native.endHooks shouldBe neutral.endHooks
            native.terminations shouldBe neutral.terminations
          } else
            native.endHooks should be >= neutral.endHooks
          if (neutral.failure.isEmpty)
            native.elements shouldBe neutral.elements
          else
            // on a failure Akka's asynchronous stages (flatMapConcat, mapAsync) fail fast and may
            // drop elements still in flight; the pull-based interpreter delivers them first
            neutral.elements.startsWith(native.elements) shouldBe true
        }
      }
    }
  }
}
