package io.cequence.wsclient.stream

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

    override def onSubscribe(subscription: Flow.Subscription): Unit = {
      upstreamSub = subscription
      downstream.onSubscribe(new Flow.Subscription {
        override def request(n: Long): Unit = TransformSubscriber.this.synchronized {
          if (!terminated) {
            if (n <= 0) {
              // RS rule 3.9 - non-positive demand is a stream error
              terminated = true
              upstreamSub.cancel()
              downstream.onError(
                new IllegalArgumentException(s"Non-positive request amount: $n")
              )
            } else {
              demand =
                if (Long.MaxValue - n < demand) Long.MaxValue
                else demand + n
              drain()
            }
          }
        }

        override def cancel(): Unit = TransformSubscriber.this.synchronized {
          if (!terminated) {
            terminated = true
            upstreamSub.cancel()
          }
        }
      })
    }

    override def onNext(input: A): Unit = synchronized {
      if (!terminated && !upstreamDone) {
        upstreamRequested = false
        val output =
          try transformer.onInput(input)
          catch {
            case NonFatal(e) =>
              fail(e)
              return
          }
        queue ++= output.elements
        if (output.done) {
          upstreamDone = true
          upstreamSub.cancel()
        }
        drain()
      }
    }

    override def onError(throwable: Throwable): Unit = synchronized {
      if (!terminated) fail(throwable, cancelUpstream = false)
    }

    override def onComplete(): Unit = synchronized {
      if (!terminated && !upstreamDone) {
        upstreamDone = true
        val finalElements =
          try transformer.onUpstreamComplete()
          catch {
            case NonFatal(e) =>
              fail(e, cancelUpstream = false)
              return
          }
        queue ++= finalElements
        drain()
      } else if (!terminated) {
        // early-terminated by the transformer; deliver what is queued, then complete
        drain()
      }
    }

    // must be called under the monitor
    private def fail(
      e: Throwable,
      cancelUpstream: Boolean = true
    ): Unit = {
      terminated = true
      queue.clear()
      if (cancelUpstream && upstreamSub != null) upstreamSub.cancel()
      downstream.onError(e)
    }

    // must be called under the monitor; reentrant-safe via the `draining` flag
    private def drain(): Unit = {
      if (draining) return
      draining = true
      try {
        var continue = true
        while (continue) {
          if (terminated) continue = false
          else if (queue.nonEmpty && demand > 0) {
            demand -= 1
            val element = queue.dequeue()
            downstream.onNext(element)
          } else if (queue.isEmpty && upstreamDone) {
            terminated = true
            downstream.onComplete()
            continue = false
          } else if (queue.isEmpty && demand > 0 && !upstreamRequested) {
            upstreamRequested = true
            // one-at-a-time upstream demand; reentrant synchronous onNext is safe (monitor
            // is reentrant and the flag prevents nested drains)
            upstreamSub.request(1)
            // keep looping if the request was already consumed synchronously - even when it
            // produced no output elements (e.g. a chunk without a complete frame yet), the
            // next upstream element must still be requested
            continue = !upstreamRequested || upstreamDone || terminated
          } else
            continue = false
        }
      } finally draining = false
    }
  }
}
