package io.cequence.wsclient.stream

import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/**
 * A COLD, SINGLE-SHOT `Flow.Publisher`: the underlying publisher is created by `factory` only
 * when the first subscriber arrives - so for HTTP-backed publishers no request is fired until
 * `subscribe` (mirroring the laziness of an akka/pekko `Source` blueprint, where the request
 * fires at materialization).
 *
 * A second subscription is rejected with `onError(IllegalStateException)` (after the mandatory
 * `onSubscribe`, per Reactive Streams rule 1.9), matching the single-materialization semantics
 * of the historical `Source`-typed streaming API.
 */
private[wsclient] final class DeferredPublisher[T](factory: () => Flow.Publisher[T])
    extends Flow.Publisher[T] {

  private val subscribed = new AtomicBoolean(false)

  override def subscribe(subscriber: Flow.Subscriber[_ >: T]): Unit =
    DeferredPublisher.subscribeOnce(subscribed, subscriber) {
      val underlying =
        try Some(factory())
        catch {
          case NonFatal(e) =>
            DeferredPublisher.signalErrorTo(subscriber, e)
            None
        }
      underlying.foreach(_.subscribe(subscriber))
    }
}

private[wsclient] object DeferredPublisher {

  private object NoopSubscription extends Flow.Subscription {
    override def request(n: Long): Unit = ()
    override def cancel(): Unit = ()
  }

  /** RS-compliant error delivery to a subscriber that never got a real subscription. */
  private[wsclient] def signalErrorTo(
    subscriber: Flow.Subscriber[_],
    error: Throwable
  ): Unit = {
    try subscriber.onSubscribe(NoopSubscription)
    catch { case NonFatal(_) => }
    subscriber.onError(error)
  }

  /**
   * The single-shot subscribe guard shared by every publisher in this package: the first
   * subscriber runs `onFirst`; any later subscriber is rejected RS-compliantly with
   * `onError(IllegalStateException)` (rule 1.9: `onSubscribe` first, and never throw).
   */
  private[wsclient] def subscribeOnce(
    subscribed: AtomicBoolean,
    subscriber: Flow.Subscriber[_]
  )(
    onFirst: => Unit
  ): Unit = {
    if (subscriber == null)
      throw new NullPointerException("Subscriber must not be null (Reactive Streams rule 1.9)")

    if (subscribed.compareAndSet(false, true))
      onFirst
    else
      signalErrorTo(
        subscriber,
        new IllegalStateException(
          "This publisher supports only a single subscriber - it is backed by a one-shot stream."
        )
      )
  }
}
