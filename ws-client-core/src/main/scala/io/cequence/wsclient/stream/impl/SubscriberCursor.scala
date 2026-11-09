package io.cequence.wsclient.stream.impl

import java.util.ArrayDeque
import java.util.concurrent.Flow
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

private[wsclient] object SubscriberCursor {

  /**
   * A run of a publisher: the factory is called and its publisher subscribed at open (one
   * factory call per run).
   */
  def apply[A](
    factory: () => Flow.Publisher[_ <: A],
    bufferSize: Int,
    ctx: RunContext
  ): Cursor[A] = {
    val cursor = new SubscriberCursor[A](bufferSize, ctx)
    try
      Cursors
        .nonNull(factory(), "The publisher factory")
        .asInstanceOf[Flow.Publisher[A]]
        .subscribe(cursor)
    catch { case NonFatal(e) => cursor.failBeforeSubscription(e) }
    cursor
  }
}

/**
 * The bridge INTO the neutral interpreter: a Reactive Streams `Flow.Subscriber` consumed as a
 * [[Cursor]].
 *
 * Demand: `bufferSize` up front, then `bufferSize / 2` more after each `bufferSize / 2`
 * elements handed to the consumer - so at most `bufferSize` elements are ever buffered; a
 * publisher that sends more than requested fails the stream (`IllegalStateException`).
 * Elements received before an `onError` are delivered before the error. Every completion of a
 * waiting `next()` is handed to the run's `ec` (publisher signals arrive on IO threads).
 *
 * RS rules: a second `onSubscribe` is cancelled (2.5); a null element, error or subscription
 * throws a `NullPointerException` (2.13); `request` / `cancel` calls are serialized (2.7) by a
 * lock-free work-in-progress loop - never under a lock, since a publisher may hold its own
 * monitor while it calls `onNext` (which can request more) and while it serves `request`;
 * `cancel()` cancels the subscription and completes a waiting `next()` with `None`.
 */
private[wsclient] final class SubscriberCursor[A](
  bufferSize: Int,
  ctx: RunContext
) extends Cursor[A]
    with Flow.Subscriber[A] {

  private val batch = math.max(1, bufferSize / 2)

  // all guarded by `lock`
  private val lock = new Object
  private var subscription: Flow.Subscription = _
  private val queue = new ArrayDeque[A]()
  private var done = false
  private var error: Throwable = _
  private var cancelled = false
  private var pending: Promise[Option[A]] = _
  // the promise last handed over (completing on ec): a cancel meanwhile completes it with None
  private var delivering: Promise[Option[A]] = null
  private var delivered = 0L
  private var deliveredAtLastRequest = 0L

  // the calls on the subscription (RS 2.7), serialized by a work-in-progress counter: a caller
  // records its request / cancel and leaves; whoever owns the loop makes the calls
  private val subscriptionWip = new java.util.concurrent.atomic.AtomicInteger()
  private val pendingRequests = new java.util.concurrent.atomic.AtomicLong()
  @volatile private var cancelPending = false
  private var cancelSent = false // inside the loop only

  // ----------------------------------------------------------------------------- subscriber

  override def onSubscribe(s: Flow.Subscription): Unit = {
    if (s == null)
      throw new NullPointerException(
        "Subscription must not be null (Reactive Streams rule 2.13)"
      )

    val accepted = lock.synchronized {
      if (subscription != null || cancelled || done)
        false
      else {
        subscription = s
        true
      }
    }

    if (accepted)
      requestFromSubscription(bufferSize)
    else
      // a second subscription (rule 2.5), or this run is already over
      try s.cancel()
      catch { case NonFatal(_) => }
  }

  override def onNext(element: A): Unit = {
    if (element == null)
      throw new NullPointerException("Element must not be null (Reactive Streams rule 2.13)")

    var handOver: Promise[Option[A]] = null
    var overflow = false
    var requestMore = 0

    lock.synchronized {
      if (!done && !cancelled) {
        if (pending != null) {
          handOver = pending
          pending = null
          delivering = handOver
          requestMore = countDelivery()
        } else if (queue.size >= bufferSize)
          overflow = true
        else
          queue.addLast(element)
      }
    }

    if (handOver != null) {
      deliver(handOver, Success(Some(element)))
      if (requestMore > 0) requestFromSubscription(requestMore)
    } else if (overflow) {
      cancelSubscription()
      onError(
        new IllegalStateException(
          s"The publisher sent more elements than requested (buffer of $bufferSize)"
        )
      )
    }
  }

  override def onError(throwable: Throwable): Unit = {
    if (throwable == null)
      throw new NullPointerException("Error must not be null (Reactive Streams rule 2.13)")
    terminate(Failure(throwable), throwable)
  }

  override def onComplete(): Unit = terminate(Success(None), null)

  private def terminate(
    signal: Try[Option[A]],
    failure: Throwable
  ): Unit = {
    var handOver: Promise[Option[A]] = null
    lock.synchronized {
      if (!done) {
        done = true
        error = failure
        // a waiting consumer means nothing is buffered: hand the terminal signal over now
        if (pending != null) {
          handOver = pending
          pending = null
          delivering = handOver
        }
      }
    }
    if (handOver != null) deliver(handOver, signal)
  }

  private[impl] def failBeforeSubscription(e: Throwable): Unit = terminate(Failure(e), e)

  // --------------------------------------------------------------------------------- cursor

  def next(): Future[Option[A]] = {
    var result: Future[Option[A]] = null
    var requestMore = 0

    lock.synchronized {
      // at most one outstanding next(): the previous hand-over has completed - drop it
      delivering = null
      if (cancelled)
        result = Cursors.NoneFuture
      else if (!queue.isEmpty) {
        result = Future.successful(Some(queue.pollFirst()))
        requestMore = countDelivery()
      } else if (done)
        result =
          if (error != null)
            Future.failed(error)
          else
            Cursors.NoneFuture
      else {
        val promise = Promise[Option[A]]()
        pending = promise
        result = promise.future
      }
    }

    if (requestMore > 0) requestFromSubscription(requestMore)
    result
  }

  def cancel(): Unit = {
    var waiting: Promise[Option[A]] = null
    var handedOver: Promise[Option[A]] = null
    val first = lock.synchronized {
      if (cancelled)
        false
      else {
        cancelled = true
        queue.clear()
        waiting = pending
        pending = null
        handedOver = delivering
        delivering = null
        true
      }
    }

    if (first) {
      // an element (or the end) already handed over but not yet delivered on ec is dropped:
      // the outstanding next() completes with None, as the Cursor contract requires
      if (waiting != null) waiting.trySuccess(None)
      if (handedOver != null) handedOver.trySuccess(None)
      cancelSubscription()
    }
  }

  // ---------------------------------------------------------------------------------- aux

  // under `lock`: the amount to request after one more element was handed to the consumer
  private def countDelivery(): Int = {
    delivered += 1
    if (delivered - deliveredAtLastRequest >= batch) {
      deliveredAtLastRequest = delivered
      batch
    } else
      0
  }

  // a waiting consumer continues on the run's ec, never on the publisher's thread
  private def deliver(
    promise: Promise[Option[A]],
    result: Try[Option[A]]
  ): Unit =
    try
      ctx.ec.execute(new Runnable {
        def run(): Unit = promise.tryComplete(result)
      })
    catch { case NonFatal(_) => promise.tryComplete(result) }

  private def requestFromSubscription(n: Int): Unit = {
    pendingRequests.addAndGet(n.toLong)
    drainSubscriptionCalls()
  }

  private def cancelSubscription(): Unit = {
    cancelPending = true
    drainSubscriptionCalls()
  }

  private def drainSubscriptionCalls(): Unit =
    if (subscriptionWip.getAndIncrement() == 0) {
      var missed = 1
      while (missed != 0) {
        val s = lock.synchronized(subscription)
        if (s != null) {
          if (cancelPending) {
            if (!cancelSent) {
              cancelSent = true
              try s.cancel()
              catch { case NonFatal(_) => }
            }
          } else {
            val n = pendingRequests.getAndSet(0)
            if (n > 0)
              try s.request(n)
              catch { case NonFatal(e) => onError(e) }
          }
        }
        missed = subscriptionWip.addAndGet(-missed)
      }
    }
}
