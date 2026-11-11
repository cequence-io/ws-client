package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.AsyncStream.Step
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.util.{Failure, Random, Success, Try}

/**
 * Seeded random operator chains (with injected failures and early termination) run on the
 * neutral interpreter - directly and through a publisher round trip - against a List model.
 */
class AsyncStreamGenerativeSpec extends AnyWordSpec with Matchers with StreamTestSupport {

  private final class Boom(val tag: String) extends RuntimeException(tag)

  // the expected outcome of a run: the elements, then completion (None) or a failure tag
  private final case class Model(
    elements: Vector[Int],
    failure: Option[String]
  ) {

    // f applied in order; a throw at index i ends the stream there
    def mapWith(f: Int => Int): Model = {
      val out = Vector.newBuilder[Int]
      val it = elements.iterator
      var failed: Option[String] = None
      while (failed.isEmpty && it.hasNext)
        Try(f(it.next())) match {
          case Success(v) => out += v
          case Failure(e) => failed = Some(e.getMessage)
        }
      if (failed.isDefined) Model(out.result(), failed) else Model(out.result(), failure)
    }

    def take(n: Int): Model =
      if (n <= 0) Model(Vector.empty, None)
      else if (elements.size >= n) Model(elements.take(n), None)
      else this
  }

  private sealed trait Gen {
    def stream(s: AsyncStream[Int]): AsyncStream[Int]
    def model(m: Model): Model
  }

  private def gen(
    random: Random,
    depth: Int
  ): Gen =
    random.nextInt(16) match {
      case 0 =>
        val k = random.nextInt(5)
        new Gen {
          def stream(s: AsyncStream[Int]) = s.map(_ + k)
          def model(m: Model) = m.mapWith(_ + k)
        }
      case 1 =>
        val k = random.nextInt(3) + 2
        new Gen {
          def stream(s: AsyncStream[Int]) = s.filter(_ % k != 0)
          def model(m: Model) = m.copy(elements = m.elements.filter(_ % k != 0))
        }
      case 2 =>
        new Gen {
          def stream(s: AsyncStream[Int]) = s.mapConcat(i => List.fill(i % 3)(i))
          def model(m: Model) =
            m.copy(elements = m.elements.flatMap(i => Vector.fill(i % 3)(i)))
        }
      case 3 =>
        val n = random.nextInt(8)
        new Gen {
          def stream(s: AsyncStream[Int]) = s.take(n.toLong)
          def model(m: Model) = m.take(n)
        }
      case 4 =>
        val limit = random.nextInt(20)
        val inclusive = random.nextBoolean()
        new Gen {
          def stream(s: AsyncStream[Int]) = s.takeWhile(_ < limit, inclusive)
          def model(m: Model) = {
            val idx = m.elements.indexWhere(_ >= limit)
            if (idx < 0) m
            else Model(m.elements.take(if (inclusive) idx + 1 else idx), None)
          }
        }
      case 5 =>
        val n = random.nextInt(4)
        new Gen {
          def stream(s: AsyncStream[Int]) = s.drop(n.toLong)
          def model(m: Model) = m.copy(elements = m.elements.drop(n))
        }
      case 6 =>
        new Gen {
          def stream(s: AsyncStream[Int]) = s.scan(0)(
            (
              acc,
              i
            ) => (acc + i) % 1000
          )
          def model(m: Model) = m.copy(elements =
            m.elements.scanLeft(0)(
              (
                acc,
                i
              ) => (acc + i) % 1000
            )
          )
        }
      case 7 =>
        new Gen {
          def stream(s: AsyncStream[Int]) = s.fold(0)(
            (
              acc,
              i
            ) => (acc + i) % 1000
          )
          def model(m: Model) =
            if (m.failure.isDefined) Model(Vector.empty, m.failure)
            else
              Model(
                Vector(
                  m.elements.foldLeft(0)(
                    (
                      acc,
                      i
                    ) => (acc + i) % 1000
                  )
                ),
                None
              )
        }
      case 8 =>
        new Gen {
          def stream(s: AsyncStream[Int]) = s.zipWithIndex.map { case (i, idx) =>
            i + idx.toInt
          }
          def model(m: Model) = m.copy(elements = m.elements.zipWithIndex.map {
            case (i, idx) => i + idx
          })
        }
      case 9 =>
        val other = Vector.fill(random.nextInt(4))(random.nextInt(10))
        new Gen {
          def stream(s: AsyncStream[Int]) = s ++ AsyncStream.fromIterable(other)
          def model(m: Model) =
            if (m.failure.isDefined) m else m.copy(elements = m.elements ++ other)
        }
      case 10 =>
        new Gen {
          def stream(s: AsyncStream[Int]) =
            s.flatMapConcat(i => AsyncStream.fromIterable(List.fill(i % 3)(i)).map(_ + 1))
          def model(m: Model) =
            m.copy(elements = m.elements.flatMap(i => Vector.fill(i % 3)(i + 1)))
        }
      case 11 =>
        val async = random.nextBoolean()
        new Gen {
          def stream(s: AsyncStream[Int]) =
            s.evalMap(i => if (async) Future(i * 2) else Future.successful(i * 2))
          def model(m: Model) = m.mapWith(_ * 2)
        }
      case 12 =>
        new Gen {
          def stream(s: AsyncStream[Int]) =
            s.stateful(() => 0)(
              (
                count,
                i
              ) => Step(count + 1, List(i)),
              count => List(1000 + count)
            )
          def model(m: Model) =
            if (m.failure.isDefined) m
            else m.copy(elements = m.elements :+ (1000 + m.elements.size))
        }
      case 13 =>
        new Gen {
          def stream(s: AsyncStream[Int]) = s.recover { case _: Boom => -1 }
          def model(m: Model) = if (m.failure.isDefined) Model(m.elements :+ -1, None) else m
        }
      case 14 =>
        val target = random.nextInt(15)
        val tag = s"boom@$depth"
        new Gen {
          def stream(s: AsyncStream[Int]) =
            s.map(i => if (i == target) throw new Boom(tag) else i)
          def model(m: Model) = m.mapWith(i => if (i == target) throw new Boom(tag) else i)
        }
      case _ =>
        val tag = s"mapped@$depth"
        new Gen {
          def stream(s: AsyncStream[Int]) = s.mapError { case _: Boom => new Boom(tag) }
          def model(m: Model) = m.copy(failure = m.failure.map(_ => tag))
        }
    }

  private def outcome(stream: AsyncStream[Int]): Model = {
    val seen = Vector.newBuilder[Int]
    Try(await(stream.runForeach(seen += _))) match {
      case Success(_)       => Model(seen.result(), None)
      case Failure(e: Boom) => Model(seen.result(), Some(e.getMessage))
      case Failure(e)       => throw e
    }
  }

  "random operator chains" should {

    "match the List model (2000 seeds, plain and through a publisher round trip)" in {
      (1 to 2000).foreach { seed =>
        val random = new Random(seed)
        val source = Vector.fill(random.nextInt(12))(random.nextInt(15))
        val sourceFailure = if (random.nextInt(4) == 0) Some("source") else None
        val gens = (1 to (1 + random.nextInt(6))).map(depth => gen(random, depth))

        val base =
          sourceFailure match {
            case Some(tag) =>
              AsyncStream.fromIterable(source) ++ AsyncStream.failed(new Boom(tag))
            case None => AsyncStream.fromIterable(source)
          }
        val stream = gens.foldLeft(base)(
          (
            s,
            g
          ) => g.stream(s)
        )
        val expected = gens.foldLeft(Model(source, sourceFailure))(
          (
            m,
            g
          ) => g.model(m)
        )

        withClue(s"seed $seed, plan ${stream.describe}: ") {
          outcome(stream) shouldBe expected
          // the same plan again (per-run state), and through the publisher bridges
          outcome(stream) shouldBe expected
          outcome(
            AsyncStream.fromPublisher(() => stream.toPublisher[Int], bufferSize = 2)
          ) shouldBe expected
        }
      }
    }
  }
}
