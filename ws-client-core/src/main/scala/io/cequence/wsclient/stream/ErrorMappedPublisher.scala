package io.cequence.wsclient.stream

import java.util.concurrent.Flow

/**
 * Maps `onError` signals of an upstream publisher through `mapError` (e.g. into the Cequence
 * exception taxonomy) - the publisher-world equivalent of the `recover { case e => throw
 * mapped(e) }` stages the akka/pekko streaming engines apply. All other signals pass through
 * untouched.
 */
private[wsclient] final class ErrorMappedPublisher[T](
  upstream: Flow.Publisher[T],
  mapError: Throwable => Throwable
) extends Flow.Publisher[T] {

  override def subscribe(subscriber: Flow.Subscriber[_ >: T]): Unit = {
    if (subscriber == null)
      throw new NullPointerException("Subscriber must not be null (Reactive Streams rule 1.9)")

    upstream.subscribe(new Flow.Subscriber[T] {
      override def onSubscribe(subscription: Flow.Subscription): Unit =
        subscriber.onSubscribe(subscription)

      override def onNext(item: T): Unit = subscriber.onNext(item)

      override def onError(throwable: Throwable): Unit = {
        val mapped =
          try mapError(throwable)
          catch { case scala.util.control.NonFatal(e) => e }
        subscriber.onError(mapped)
      }

      override def onComplete(): Unit = subscriber.onComplete()
    })
  }
}
