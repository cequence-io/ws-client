package io.cequence.wsclient.stream

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Flow
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * A stateful synchronous transform applied between an upstream and a downstream stream element
 * type - the minimal operator contract needed for delimiter framing and JSON frame parsing (a
 * `mapConcat` with a completion flush and early termination).
 *
 * Implementations may be stateful; they are never invoked concurrently.
 */
private[wsclient] trait StreamTransformer[A, B] {

  /**
   * Outputs for one input element; return `done = true` to terminate the stream early (the
   * upstream is cancelled and - after the returned outputs are delivered - the downstream is
   * completed). May throw: the exception is delivered downstream as `onError`.
   */
  def onInput(input: A): StreamTransformer.Output[B]

  /**
   * Final outputs when the upstream completes normally (e.g. an unterminated trailing frame
   * when truncation is allowed). May throw.
   */
  def onUpstreamComplete(): Seq[B] = Nil
}

private[wsclient] object StreamTransformer {
  final case class Output[B](
    elements: Seq[B],
    done: Boolean = false
  )
}

/**
 * A single-shot `Flow.Publisher` applying a [[StreamTransformer]] to an upstream publisher.
 *
 * Deliberately conservative: all signals are serialized on a single monitor (reentrant, so
 * same-thread `request` from within `onNext` is fine) and upstream demand is issued one
 * element at a time - entirely adequate for SSE/chat-completion event rates, and simple enough
 * to be verifiably correct against the Reactive Streams rules this library relies on: no
 * signals before `onSubscribe`, no `onNext` after `cancel`/`onComplete`/`onError`, `request`
 * accounting, and cancellation propagation to the upstream.
 *
 * Lock order: the UPSTREAM subscription is never called under the monitor; downstream signals
 * are delivered under it. An upstream (e.g. another TransformPublisher) holds its own monitor
 * while it signals us, so calling it under ours deadlocked two chained transforms - a cancel
 * racing the next element (up to 1.1.5), a request racing the upstream's completion, and a
 * cancel a downstream issued from inside our signal (the `[DONE]` event) racing the upstream's
 * completion (both fixed in 1.2.0). Upstream calls are therefore only recorded where they
 * arise and issued by one serializing loop (`flushUpstream`, RS rule 2.7) from the outermost
 * frame of the thread, once it holds no monitor of this subscriber.
 */
private[wsclient] final class TransformPublisher[A, B](
  upstream: Flow.Publisher[A],
  makeTransformer: () => StreamTransformer[A, B]
) extends Flow.Publisher[B] {

  private val subscribed = new java.util.concurrent.atomic.AtomicBoolean(false)

  override def subscribe(subscriber: Flow.Subscriber[_ >: B]): Unit =
    DeferredPublisher.subscribeOnce(subscribed, subscriber) {
      upstream.subscribe(new TransformSubscriber(subscriber, makeTransformer()))
    }

  private final class TransformSubscriber(
    downstream: Flow.Subscriber[_ >: B],
    transformer: StreamTransformer[A, B]
  ) extends Flow.Subscriber[A] {

    // all state guarded by `this`
    private var upstreamSub: Flow.Subscription = _
    private val queue = mutable.Queue.empty[B]
    private var demand = 0L
    private var upstreamDone = false // upstream completed, or early-terminated by transformer
    private var terminated = false // downstream saw onComplete/onError, or cancelled
    private var upstreamRequested = false // one-at-a-time upstream demand accounting
    private var draining = false // re-entrancy guard for drain()

    // the upstream calls owed - request(1)s and a cancel - issued by flushUpstream
    private val owedRequests = new java.util.concurrent.atomic.AtomicInteger()
    @volatile private var cancelOwed = false
    private val upstreamCalls = new java.util.concurrent.atomic.AtomicInteger()
    private var cancelSent = false // accessed only by the flushUpstream loop

    override def onSubscribe(subscription: Flow.Subscription): Unit = {
      upstreamSub = subscription
      downstream.onSubscribe(new Flow.Subscription {
        override def request(n: Long): Unit = {
          var invalid = false
          val requestOne = TransformSubscriber.this.synchronized {
            if (terminated)
              false
            else if (n <= 0) {
              // RS rule 3.9 - non-positive demand is a stream error
              terminated = true
              invalid = true
              false
            } else {
              demand =
                if (Long.MaxValue - n < demand) Long.MaxValue
                else demand + n
              drain()
            }
          }
          if (invalid) {
            cancelUpstream()
            // outside the monitor (already terminated): a throwing subscriber is only logged
            try
              downstream.onError(
                new IllegalArgumentException(s"Non-positive request amount: $n")
              )
            catch {
              case NonFatal(e) =>
                TransformPublisher.logger.warn(
                  "A Flow.Subscriber signal method threw (Reactive Streams rule 2.13)",
                  e
                )
            }
          } else if (requestOne)
            requestUpstream()
          flushUpstream()
        }

        override def cancel(): Unit = {
          val first = TransformSubscriber.this.synchronized {
            if (terminated)
              false
            else {
              terminated = true
              true
            }
          }
          if (first) cancelUpstream()
          flushUpstream()
        }
      })
    }

    override def onNext(input: A): Unit = {
      var cancelUpstream = false
      val requestOne = synchronized {
        if (!terminated && !upstreamDone) {
          upstreamRequested = false
          val output =
            try Some(transformer.onInput(input))
            catch {
              case NonFatal(e) =>
                cancelUpstream = true
                fail(e)
                None
            }
          output match {
            case Some(result) =>
              queue ++= result.elements
              if (result.done) {
                upstreamDone = true
                cancelUpstream = true
              }
              drain()
            case None =>
              false
          }
        } else
          false
      }
      if (cancelUpstream) this.cancelUpstream()
      if (requestOne) requestUpstream()
      flushUpstream()
    }

    override def onError(throwable: Throwable): Unit = {
      synchronized {
        if (!terminated) fail(throwable)
      }
      flushUpstream()
    }

    override def onComplete(): Unit = {
      val requestOne = synchronized {
        if (!terminated && !upstreamDone) {
          upstreamDone = true
          val finalElements =
            try Some(transformer.onUpstreamComplete())
            catch {
              case NonFatal(e) =>
                fail(e)
                None
            }
          finalElements match {
            case Some(elements) =>
              queue ++= elements
              drain()
            case None =>
              false
          }
        } else if (!terminated) {
          // early-terminated by the transformer; deliver what is queued, then complete
          drain()
        } else
          false
      }
      if (requestOne) requestUpstream()
      flushUpstream()
    }

    // must be called under the monitor; the caller cancels the upstream if it has to
    private def fail(e: Throwable): Unit = {
      terminated = true
      queue.clear()
      signal(downstream.onError(e))
    }

    // a downstream signal, under the monitor; a subscriber must not throw (RS rule 2.13) - one
    // that does is treated as cancelled, so nothing it threw unwinds through this subscriber or
    // the upstream calls
    private def signal(send: => Unit): Unit =
      try send
      catch {
        case NonFatal(e) =>
          TransformPublisher.logger.warn(
            "A Flow.Subscriber signal method threw (Reactive Streams rule 2.13)",
            e
          )
          terminated = true
          queue.clear()
          cancelOwed = true
      }

    // must be called under the monitor; reentrant-safe via the `draining` flag. True when the
    // upstream must be asked for one more element - the caller does that after releasing the
    // monitor (requestUpstream)
    private def drain(): Boolean = {
      if (draining) return false
      draining = true
      try {
        var requestOne = false
        var continue = true
        while (continue) {
          if (terminated) continue = false
          else if (queue.nonEmpty && demand > 0) {
            demand -= 1
            val element = queue.dequeue()
            signal(downstream.onNext(element))
          } else if (queue.isEmpty && upstreamDone) {
            terminated = true
            signal(downstream.onComplete())
            continue = false
          } else if (queue.isEmpty && demand > 0 && !upstreamRequested) {
            // one-at-a-time upstream demand - issued by the caller, outside the monitor
            upstreamRequested = true
            requestOne = true
            continue = false
          } else
            continue = false
        }
        requestOne
      } finally draining = false
    }

    private def requestUpstream(): Unit = {
      owedRequests.incrementAndGet()
      flushUpstream()
    }

    private def cancelUpstream(): Unit = {
      cancelOwed = true
      flushUpstream()
    }

    // issues the owed upstream calls - never under this subscriber's monitor: a frame that still
    // holds it (a re-entrant call from inside a downstream signal) leaves them to the outermost
    // frame, which flushes after releasing the monitor. One loop at a time (RS rule 2.7), and a
    // synchronous onNext from request(1) adds its next request to the running loop - no
    // recursion. After the cancel no request goes out
    private def flushUpstream(): Unit =
      if (!Thread.holdsLock(this) && upstreamCalls.getAndIncrement() == 0) {
        var missed = 1
        while (missed != 0) {
          while (owedRequests.get() > 0 && !cancelOwed) {
            owedRequests.decrementAndGet()
            // a subscription must not throw (rule 3.16): one that does fails the stream, and the
            // loop still releases its count - a later cancel always gets through
            try upstreamSub.request(1)
            catch {
              case NonFatal(e) =>
                cancelOwed = true
                synchronized {
                  if (!terminated) fail(e)
                }
            }
          }
          // checked after the requests: a cancel owed meanwhile goes out in this pass
          if (cancelOwed) {
            owedRequests.set(0)
            if (!cancelSent) {
              cancelSent = true
              try upstreamSub.cancel()
              catch {
                case NonFatal(e) =>
                  TransformPublisher.logger.warn(
                    "A Flow.Subscription.cancel threw (Reactive Streams rule 3.15)",
                    e
                  )
              }
            }
          }
          missed = upstreamCalls.addAndGet(-missed)
        }
      }
  }
}

private[wsclient] object TransformPublisher {
  private val logger = Logger(LoggerFactory.getLogger(classOf[TransformPublisher[_, _]]))
}
