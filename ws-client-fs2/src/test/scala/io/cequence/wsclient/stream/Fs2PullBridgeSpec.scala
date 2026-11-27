package io.cequence.wsclient.stream

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.cequence.wsclient.stream.impl.{BridgeCounter, Cursor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * The pull bridge into fs2 (`Fs2Compiler.cursorStream`: every publisher, every foreign leaf)
 * against a scripted cursor, on EVERY script of a small scope under every way a run can end:
 *
 *   - at most ONE `next()` is outstanding at any time (the cursor contract: the pulling side
 *     never asks again before the previous result is in) - the bridge reads ahead results that
 *     are already complete, never a second pending one;
 *   - the cursor is cancelled EXACTLY ONCE, on every exit: the end, a failure, a `take`, an
 *     interrupt that finds a `next()` pending;
 *   - nothing is pulled after the cancel;
 *   - what the consumer sees is the script's elements in order, from the start, none lost or
 *     twice, then the end or the failure of the script - or a prefix of them where the
 *     consumer stopped early.
 *
 * The cursor's results are immediate or arrive later on another thread (a race with the
 * bridge's own bookkeeping); a cancel completes a pending `next()` with `None`, as the neutral
 * cursors do.
 */
class Fs2PullBridgeSpec extends AnyWordSpec with Matchers {

  private sealed trait Step
  private case class Elem(async: Boolean) extends Step
  private case class End(async: Boolean) extends Step
  private case class Fail(async: Boolean) extends Step

  private val boom = new RuntimeException("script failure")

  private val timers: ScheduledExecutorService = Executors.newScheduledThreadPool(
    2,
    (r: Runnable) => {
      val t = new Thread(r, "fs2-bridge-spec-timer")
      t.setDaemon(true)
      t
    }
  )

  /** Records what the bridge does to it. */
  private final class ScriptedCursor(script: Vector[Step]) extends Cursor[Int] {
    private val lock = new Object
    private var position = 0
    private var pending: Option[Promise[Option[Int]]] = None
    private var cancelled = false

    val outstandingNow = new AtomicInteger()
    val maxOutstanding = new AtomicInteger()
    val nexts = new AtomicInteger()
    val cancels = new AtomicInteger()
    val opened = new AtomicBoolean(false)
    val nextAfterCancel = new AtomicBoolean(false)
    val secondPending = new AtomicBoolean(false)

    def next(): Future[Option[Int]] = {
      nexts.incrementAndGet()
      val promise = Promise[Option[Int]]()
      // the step, and the ordinal of an element (every step before a terminal one is an element)
      val (step, element): (Option[Step], Int) = lock.synchronized {
        if (cancelled) nextAfterCancel.set(true)
        if (pending.isDefined) secondPending.set(true)
        val now = outstandingNow.incrementAndGet()
        if (now > maxOutstanding.get()) maxOutstanding.set(now)
        if (position < script.size) {
          val s = script(position)
          position += 1
          (Some(s), position)
        } else (None, position)
      }

      def complete(result: Try[Option[Int]]): Unit = {
        val first = lock.synchronized {
          if (pending.contains(promise)) pending = None
          val open = !promise.isCompleted
          if (open) outstandingNow.decrementAndGet()
          open
        }
        if (first) promise.complete(result)
      }

      def result(s: Step): Try[Option[Int]] = s match {
        case _: Elem => Success(Some(element))
        case _: End  => Success(None)
        case _: Fail => Failure(boom)
      }

      step match {
        case None                  => complete(Success(None))
        case Some(s @ Elem(false)) => complete(result(s))
        case Some(s @ End(false))  => complete(result(s))
        case Some(s @ Fail(false)) => complete(result(s))
        case Some(s: Step) =>
          lock.synchronized { pending = Some(promise) }
          timers.schedule(
            new Runnable { def run(): Unit = complete(result(s)) },
            1,
            TimeUnit.MILLISECONDS
          )
          ()
      }
      promise.future
    }

    def cancel(): Unit = {
      cancels.incrementAndGet()
      val toEnd = lock.synchronized {
        cancelled = true
        val p = pending
        pending = None
        p
      }
      toEnd.foreach { p =>
        if (p.trySuccess(None)) {
          outstandingNow.decrementAndGet()
          ()
        }
      }
    }
  }

  // the script's observable behavior: its elements (ids are the order of the next() calls),
  // then the end or the failure
  private def expected(script: Vector[Step]): (Vector[Int], Option[Boolean]) = {
    val builder = Vector.newBuilder[Int]
    var n = 0
    var ending: Option[Boolean] = None // Some(false): ended, Some(true): failed
    val it = script.iterator
    while (ending.isEmpty && it.hasNext) {
      it.next() match {
        case _: Elem =>
          n += 1
          builder += n
        case _: End  => ending = Some(false)
        case _: Fail => ending = Some(true)
      }
    }
    (builder.result(), ending)
  }

  private def stream(cursor: ScriptedCursor): fs2.Stream[IO, Int] =
    Fs2Compiler.cursorStream[IO, Int](
      _ => {
        cursor.opened.set(true)
        cursor
      },
      BridgeCounter.Global
    )

  private val asyncFlags = Seq(false, true)

  private def steps: Seq[Step] =
    asyncFlags.flatMap(a => Seq(Elem(a), End(a), Fail(a)))

  // every script up to `length` steps in which a terminal step is the last one
  private def scripts(length: Int): Seq[Vector[Step]] = {
    def build(n: Int): Seq[Vector[Step]] =
      if (n == 0) Seq(Vector.empty)
      else
        for {
          prefix <- build(n - 1)
          if prefix.forall(_.isInstanceOf[Elem])
          step <- steps
        } yield prefix :+ step
    (0 to length).flatMap(build)
  }

  private def checkInvariants(
    label: String,
    cursor: ScriptedCursor
  ): Unit = {
    withClue(s"$label: ") {
      cursor.maxOutstanding.get() should be <= 1
      cursor.secondPending.get() shouldBe false
      cursor.nextAfterCancel.get() shouldBe false
    }
  }

  // a cursor is opened per run (a run that never started - `take(0)`, interrupted at once - opens
  // none) and cancelled exactly once when it was
  private def awaitCancelled(
    label: String,
    cursor: ScriptedCursor
  ): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (cursor.opened.get() && cursor.cancels.get() == 0 && System.nanoTime() < deadline)
      Thread.sleep(1)
    // a late second cancel would show here
    Thread.sleep(2)
    withClue(s"$label: cancels: ") {
      cursor.cancels.get() shouldBe (if (cursor.opened.get()) 1 else 0)
    }
  }

  "the pull bridge" should {

    "deliver the script and cancel the cursor once - every script of up to 4 steps" in {
      scripts(4).foreach { script =>
        val cursor = new ScriptedCursor(script)
        val label = script.toString
        val (elements, ending) = expected(script)
        val run = stream(cursor).compile.toVector.attempt.unsafeRunTimed(15.seconds)
        withClue(s"$label: ") { run should not be None }
        (run.get, ending) match {
          case (Right(seen), Some(false) | None) =>
            withClue(s"$label: ") { seen shouldBe elements }
          case (Left(e), Some(true)) =>
            withClue(s"$label: ") { e shouldBe boom }
          case (other, end) =>
            fail(s"$label: unexpected result $other for an ending of $end")
        }
        awaitCancelled(label, cursor)
        checkInvariants(label, cursor)
      }
    }

    "stop early without losing or repeating an element, and cancel once - take(k)" in {
      scripts(4).foreach { script =>
        val (elements, _) = expected(script)
        (0 to elements.size).foreach { k =>
          val cursor = new ScriptedCursor(script)
          val label = s"$script take($k)"
          val run = stream(cursor).take(k.toLong).compile.toVector.unsafeRunTimed(15.seconds)
          withClue(s"$label: ") { run shouldBe Some(elements.take(k)) }
          awaitCancelled(label, cursor)
          checkInvariants(label, cursor)
        }
      }
    }

    "cancel once when the run is interrupted - also with a next() pending" in {
      scripts(3).foreach { script =>
        Seq(0L, 1L, 2L, 4L).foreach { delayMs =>
          val cursor = new ScriptedCursor(script)
          val label = s"$script interrupted after ${delayMs}ms"
          val (future, cancel) =
            stream(cursor).compile.toVector.attempt.unsafeToFutureCancelable()
          if (delayMs > 0) Thread.sleep(delayMs)
          val cancelled = cancel()
          Await.result(cancelled, 10.seconds)
          // the run was over before the interrupt, or it was interrupted: either way it ended
          withClue(s"$label: ") { future.isCompleted || cancelled.isCompleted shouldBe true }
          awaitCancelled(label, cursor)
          checkInvariants(label, cursor)
          // what a run that finished sees is still a prefix of the script
          if (future.isCompleted) {
            val (elements, _) = expected(script)
            Try(Await.result(future, 5.seconds)) match {
              case Success(Right(seen)) => elements.take(seen.size) shouldBe seen
              case Success(Left(e))     => e shouldBe boom
              case Failure(_)           => () // the interrupted run
            }
          }
        }
      }
    }

    "read results that are already complete ahead - up to a chunk - and never a pending one" in {
      // 150 immediate elements, then an asynchronous one, then the end: the chunk limit (64)
      // and the hand-over to the pending result are both crossed
      val script = Vector.fill(150)(Elem(false)) ++ Vector(Elem(true), End(false))
      val cursor = new ScriptedCursor(script)
      val seen = stream(cursor).compile.toVector.unsafeRunTimed(15.seconds)
      seen shouldBe Some((1 to 151).toVector)
      awaitCancelled("long script", cursor)
      checkInvariants("long script", cursor)
    }

    "take from a long script and cancel once - the early stop falls inside a chunk" in {
      val script = Vector.fill(200)(Elem(false))
      Seq(1, 63, 64, 65, 128, 199).foreach { k =>
        val cursor = new ScriptedCursor(script)
        val seen = stream(cursor).take(k.toLong).compile.toVector.unsafeRunTimed(15.seconds)
        seen shouldBe Some((1 to k).toVector)
        awaitCancelled(s"take($k) of 200", cursor)
        checkInvariants(s"take($k) of 200", cursor)
      }
    }
  }
}
