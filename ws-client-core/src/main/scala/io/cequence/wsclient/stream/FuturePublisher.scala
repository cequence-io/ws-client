package io.cequence.wsclient.stream

import java.util.concurrent.{CompletableFuture, CompletionException, Flow}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges a `CompletableFuture[Flow.Publisher[T]]` (e.g. an async HTTP response whose body is
 * a publisher) to a plain `Flow.Publisher[T]`: the subscriber is subscribed through once the
 * future completes; a failed future is delivered as `onError`. Single-shot, like every
 * publisher in this package.
 *
 * Note: `onSubscribe` is deferred until the future completes - legal per Reactive Streams (no
 * signal may precede it, and no demand can be serviced before the response exists anyway).
 */
private[wsclient] final class FuturePublisher[T](future: CompletableFuture[Flow.Publisher[T]])
    extends Flow.Publisher[T] {

  private val subscribed = new AtomicBoolean(false)

  override def subscribe(subscriber: Flow.Subscriber[_ >: T]): Unit =
    DeferredPublisher.subscribeOnce(subscribed, subscriber) {
      future.whenComplete {
        (
          publisher,
          error
        ) =>
          if (error != null)
            DeferredPublisher.signalErrorTo(subscriber, unwrap(error))
          else
            publisher.subscribe(subscriber)
          ()
      }
      ()
    }

  private def unwrap(e: Throwable): Throwable = e match {
    case ce: CompletionException if ce.getCause != null => ce.getCause
    case other                                          => other
  }
}
