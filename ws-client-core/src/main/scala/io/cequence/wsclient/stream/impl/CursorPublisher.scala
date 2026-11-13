package io.cequence.wsclient.stream.impl

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Flow
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The bridge OUT of the neutral interpreter: a cold `Flow.Publisher` running one independent
 * run (cursor) per subscription.
 *
 * RS rules: a null subscriber throws a `NullPointerException` (1.9); `onSubscribe` comes
 * first, from the run's `ec`, and the run is opened after it; one result is pulled ahead of
 * demand, so a failing run signals `onError` without demand (1.4) and the completion needs no
 * extra request (1.5); a null element fails the run (2.13); demand is summed and capped at
 * `Long.MaxValue`; `request(n <= 0)` signals `onError(IllegalArgumentException)` (3.9); a
 * work-in-progress drain loop serializes all signals and bounds the recursion of a `request`
 * from inside `onNext` (3.3); `cancel` cancels the run, stops signalling and drops the
 * subscriber reference (3.5, 3.7, 3.13).
 *
 * Threads: the run's user functions run on its `ec`, never on the subscriber's thread - a
 * `request` from outside the drain loop (e.g. an Akka dispatcher, an HTTP client's thread)
 * drains on `ec`, and the loop yields `ec` every `YieldEvery` synchronous elements.
 */
private[wsclient] final class CursorPublisher[A](
  opener: Opener[A],
  ctx: RunContext
) extends Flow.Publisher[A] {

  override def subscribe(subscriber: Flow.Subscriber[_ >: A]): Unit = {
    if (subscriber == null)
      throw new NullPointerException("Subscriber must not be null (Reactive Streams rule 1.9)")

    new CursorSubscription[A](opener, ctx, subscriber).start()
  }
}

private[wsclient] final class CursorSubscription[A](
  opener: Opener[A],
  ctx: RunContext,
  initialSubscriber: Flow.Subscriber[_ >: A]
) extends Flow.Subscription {

  import CursorSubscription._

  @volatile private var subscriber: Flow.Subscriber[_ >: A] = initialSubscriber
  @volatile private var cursor: Cursor[A] = _
  @volatile private var cancelled = false
  @volatile private var invalidRequest: Throwable = _
  @volatile private var asyncResult: Try[Option[A]] = _

  private val demand = new AtomicLong()
  private val wip = new AtomicInteger()

  // accessed only inside the drain loop: one result is pulled ahead of demand, so a failure
  // (or the completion) is signalled without demand (rules 1.4, 1.5) - an element waits for it
  private var done = false
  private var inFlight = false
  private var hasHeld = false
  private var held: A = _

  def start(): Unit = {
    val begin = new Runnable {
      def run(): Unit = {
        // holds the drain loop while onSubscribe and the open run: a request (or cancel) from
        // inside onSubscribe only registers, and the loop below delivers after it returned
        wip.getAndIncrement()
        val delivered =
          try {
            initialSubscriber.onSubscribe(CursorSubscription.this)
            true
          } catch {
            case NonFatal(e) =>
              logger.warn("Flow.Subscriber.onSubscribe threw (Reactive Streams rule 2.13)", e)
              cancelled = true
              false
          }

        // a cancel or an invalid request from inside onSubscribe: the run is never opened (no
        // leaf side effects - e.g. a request - for a run that is over already)
        if (delivered && !cancelled && invalidRequest == null) {
          val opened = opener.open(ctx) // never throws
          cursor = opened
          if (cancelled) opened.cancel()
        } else
          unopened()
        drainLoop() // releases the count taken above
      }
    }

    try ctx.ec.execute(begin)
    catch {
      case NonFatal(e) =>
        // the ec rejected the task: still onSubscribe first (rule 1.9), then the failure
        try {
          initialSubscriber.onSubscribe(NoopSubscription)
          initialSubscriber.onError(e)
        } catch { case NonFatal(_) => }
        unopened()
    }
  }

  // the run ends without being opened: an opener holding a resource for it releases it
  private def unopened(): Unit =
    try opener.unopened()
    catch { case NonFatal(e) => logger.warn("A stream's release of an unopened run threw", e) }

  // the drain loop on the run's ec; false when the ec rejected it (the caller runs it inline)
  private val drainOnEc: Runnable = new Runnable {
    def run(): Unit = drainLoop()
  }

  private def drainLater(): Boolean =
    try {
      ctx.ec.execute(drainOnEc)
      true
    } catch { case NonFatal(_) => false }

  override def request(n: Long): Unit = {
    if (n <= 0)
      invalidRequest = new IllegalArgumentException(
        s"Non-positive request amount: $n (Reactive Streams rule 3.9)"
      )
    else {
      var updated = false
      while (!updated) {
        val current = demand.get()
        val next =
          if (Long.MaxValue - n < current)
            Long.MaxValue
          else
            current + n
        updated = demand.compareAndSet(current, next)
      }
    }
    // a request from inside onNext joins the running loop; any other one starts the loop on
    // ec - pulling the cursor runs the run's user functions, which never run on the
    // subscriber's thread
    if (wip.getAndIncrement() == 0 && !drainLater()) drainLoop()
  }

  override def cancel(): Unit =
    if (!cancelled) {
      cancelled = true
      val current = cursor
      if (current != null) current.cancel()
      drain() // releases the subscriber reference
    }

  private def drain(): Unit =
    if (wip.getAndIncrement() == 0) drainLoop()

  // the work-in-progress loop: entered by the caller that took wip from 0 to 1. After
  // YieldEvery synchronously consumed results it continues on a fresh ec task (still holding
  // its wip count), so an endless synchronous source never pins one thread
  private def drainLoop(): Unit = {
    var missed = 1
    var budget = YieldEvery
    while (missed != 0) {
      var continue = true
      while (continue) {
        continue = false
        if (budget <= 0) {
          if (drainLater())
            return
          else
            budget = YieldEvery // the ec rejects: continue here (iteratively, never recursing)
        }

        if (cancelled) {
          subscriber = null // rule 3.13
          asyncResult = null
        } else if (!done) {
          val badRequest = invalidRequest
          val ready = asyncResult
          val current = cursor

          if (badRequest != null) {
            done = true
            cancelled = true
            val opened = cursor // read after `cancelled` is set: an open meanwhile sees it
            if (opened != null) opened.cancel()
            signal(_.onError(badRequest), evenIfCancelled = true)
          } else if (ready != null) {
            asyncResult = null
            inFlight = false
            accept(ready)
            continue = true
          } else if (hasHeld) {
            if (demand.get() > 0) {
              val element = held
              hasHeld = false
              held = null.asInstanceOf[A]
              if (demand.get() != Long.MaxValue) demand.decrementAndGet()
              signal(_.onNext(element))
              continue = true
            }
          } else if (!inFlight && current != null) {
            val future = Cursors.pull(current)
            future.value match {
              case Some(result) =>
                accept(result)
                budget -= 1
                continue = true
              case None =>
                inFlight = true
                listen(future)
            }
          }
        }
      }
      missed = wip.addAndGet(-missed)
    }
  }

  private def listen(future: Future[Option[A]]): Unit =
    future.onComplete { result =>
      asyncResult = result
      drain()
    }(Trampoline)

  // an element is held until there is demand; a terminal signal goes out at once
  private def accept(result: Try[Option[A]]): Unit =
    result match {
      case Success(Some(element)) =>
        if (element == null) {
          done = true
          cursor.cancel()
          signal(
            _.onError(
              new NullPointerException("Stream element is null (Reactive Streams rule 2.13)")
            )
          )
        } else {
          hasHeld = true
          held = element
        }
      case Success(None) =>
        done = true
        signal(_.onComplete())
      case Failure(e) =>
        done = true
        signal(_.onError(e))
    }

  // nothing goes out after a cancel (rules 3.6, 3.7) - except the rule 3.9 error, which cancels
  private def signal(
    f: Flow.Subscriber[_ >: A] => Unit,
    evenIfCancelled: Boolean = false
  ): Unit = {
    val current = subscriber
    if (current != null && (!cancelled || evenIfCancelled))
      try f(current)
      catch {
        case NonFatal(e) =>
          // a subscriber must not throw (rule 2.13): treat it as a cancellation
          logger.warn("A Flow.Subscriber signal method threw (Reactive Streams rule 2.13)", e)
          cancel()
      }
  }
}

private[wsclient] object CursorSubscription {

  private val logger = Logger(LoggerFactory.getLogger(classOf[CursorSubscription[_]]))

  // synchronously consumed results per ec task of the drain loop
  private val YieldEvery = 1024

  private object NoopSubscription extends Flow.Subscription {
    override def request(n: Long): Unit = ()
    override def cancel(): Unit = ()
  }
}
