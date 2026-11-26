package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.impl.{Cursor, FromCursor, NeutralRunner}

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/**
 * The neutral preflight (`NeutralRunner.startPreflight`, the `RunPreflight` call) against its
 * contract: a preflight that is cancelled before it completed holds NOTHING - the request it
 * sent is aborted - and one that completed hands its stream over, uncancelled, to its caller.
 * Never both, never neither: a cancel racing the first element must leave the cursor either
 * cancelled (the result is a cancellation) or in the stream the result carries.
 *
 * The first element comes from a thread of its own, `cancel()` from another, and the
 * preflight's own start from a third (`ThreadPerTask`): the checker interleaves the start, the
 * pull, the hand-over and the cancel.
 */
object PreflightModelCheck extends ModelCheckSuite("neutral preflight") {

  /** A cursor whose first element the scenario delivers; records how often it is cancelled. */
  private final class ControlledCursor extends Cursor[Int] {
    val cancelled = new AtomicInteger()
    val first: Promise[Option[Int]] = Promise[Option[Int]]()

    override def next(): Future[Option[Int]] = first.future

    // idempotent, and it ends the outstanding next() (the Cursor contract)
    override def cancel(): Unit = {
      cancelled.incrementAndGet()
      first.trySuccess(None)
      ()
    }
  }

  scenario("cancel() racing the first element: a cancelled preflight holds no cursor") {
    val ec = new ThreadPerTask
    val cursor = new ControlledCursor
    val opens = new AtomicInteger()
    val stream = FromCursor[Int](
      _ => {
        opens.incrementAndGet()
        cursor
      },
      "controlled"
    )

    val run = NeutralRunner.startPreflight(stream, ModelCheckSupport.runContext(ec))
    val producer = thread {
      cursor.first.trySuccess(Some(1))
      ()
    }
    val canceller = thread(run.cancel())
    producer.join()
    canceller.join()
    ec.joinAll()

    val outcome = run.result.value
    ensure(outcome.isDefined, "the preflight did not end")
    outcome.get match {
      case Success(_) =>
        // the first element won: the stream is handed over. A cancel that raced it disclaims
        // the stream (the caller drops it - and, if the cancel came first, has the cursor
        // cancelled: both are the caller's business, see the window `preflightCall` documents)
        ()
      case Failure(_: CancellationException) =>
        // the cancel won: nothing may stay held - the cursor was opened and is cancelled
        // (also when the first element arrived in the instant before: that stream is dropped,
        // here, by the preflight), or it was never opened
        ensure(
          opens.get == 0 || cursor.cancelled.get >= 1,
          s"a cancelled preflight left its cursor open (opens ${opens.get}, cancels ${cursor.cancelled.get})"
        )
      case Failure(other) => ensure(false, s"neither a stream nor a cancellation: $other")
    }
  }

  scenario("cancel() racing the start: a cursor that was opened is cancelled") {
    val ec = new ThreadPerTask
    val cursor = new ControlledCursor
    val opens = new AtomicInteger()
    val stream = FromCursor[Int](
      _ => {
        opens.incrementAndGet()
        cursor
      },
      "controlled"
    )

    // the first element never comes: the cancel is what ends the preflight
    val run = NeutralRunner.startPreflight(stream, ModelCheckSupport.runContext(ec))
    val canceller = thread(run.cancel())
    canceller.join()
    ec.joinAll()

    ensure(
      run.result.value.exists(_.failed.toOption.exists(_.isInstanceOf[CancellationException])),
      s"a cancelled preflight did not fail with a cancellation: ${run.result.value}"
    )
    ensure(
      opens.get == 0 || cursor.cancelled.get >= 1,
      s"a cancelled preflight left its cursor open (opens ${opens.get}, cancels ${cursor.cancelled.get})"
    )
  }
}
