package io.cequence.wsclient.service.ws

import java.net.http.HttpResponse.BodySubscriber
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{
  CompletableFuture,
  CompletionStage,
  Flow,
  ScheduledFuture,
  TimeUnit
}

/**
 * The request deadline over a response BODY: java.net.http enforces `HttpRequest.timeout` only
 * until the response headers arrive, so a body that stalled after them left the call pending
 * forever, its connection open. Delegates to `inner`; when `remainingMs` pass before the body
 * completes, the body fails with an `HttpTimeoutException` and its subscription is cancelled -
 * which makes java.net.http close the connection.
 */
private[ws] final class DeadlineBodySubscriber[T](
  inner: BodySubscriber[T],
  remainingMs: Long,
  timeoutMessage: String
) extends BodySubscriber[T] {

  private val body = new CompletableFuture[T]()
  @volatile private var subscription: Flow.Subscription = _

  private val timer: ScheduledFuture[_] = EngineSupport.deadlineScheduler.schedule(
    new Runnable {
      override def run(): Unit =
        if (body.completeExceptionally(new HttpTimeoutException(timeoutMessage))) {
          val s = subscription
          if (s != null) s.cancel()
        }
    },
    math.max(remainingMs, 0L),
    TimeUnit.MILLISECONDS
  )

  inner.getBody.whenComplete {
    (
      value: T,
      error: Throwable
    ) =>
      timer.cancel(false)
      if (error != null) body.completeExceptionally(error) else body.complete(value)
      ()
  }

  override def getBody: CompletionStage[T] = body

  override def onSubscribe(s: Flow.Subscription): Unit = {
    subscription = s
    inner.onSubscribe(s)
    if (body.isDone) s.cancel() // expired before the body even started
  }

  override def onNext(item: java.util.List[ByteBuffer]): Unit = inner.onNext(item)

  override def onError(t: Throwable): Unit = inner.onError(t)

  override def onComplete(): Unit = inner.onComplete()
}

/**
 * Fails a stream that delivers nothing for `idleMs` while its subscriber waits for data (has
 * outstanding demand): the upstream is cancelled - closing the connection - and the subscriber
 * gets an `HttpTimeoutException`. Time without demand (a slow consumer) never counts, and a
 * healthy long-lived stream is not cut off. The idle clock starts when the upstream
 * subscribes,
 * i.e. after the response headers (until then `HttpRequest.timeout` applies). Downstream
 * signals are serialized, the timeout's onError included.
 */
private[ws] final class IdleTimeoutPublisher[T](
  upstream: Flow.Publisher[T],
  idleMs: Long,
  timeoutMessage: String
) extends Flow.Publisher[T] {

  override def subscribe(downstream: Flow.Subscriber[_ >: T]): Unit =
    upstream.subscribe(new Flow.Subscriber[T] { self =>
      @volatile private var upstreamSubscription: Flow.Subscription = _
      private val demand = new AtomicLong(0)
      @volatile private var lastActivity = System.nanoTime()
      private var done = false // guarded by self
      @volatile private var timer: ScheduledFuture[_] = _

      private def schedule(delayMs: Long): Unit =
        timer = EngineSupport.deadlineScheduler.schedule(
          new Runnable { override def run(): Unit = check() },
          math.max(delayMs, 1L),
          TimeUnit.MILLISECONDS
        )

      private def check(): Unit = {
        val idleForMs = (System.nanoTime() - lastActivity) / 1000000L
        val expired = self.synchronized {
          if (done) false
          else if (demand.get > 0 && idleForMs >= idleMs) {
            done = true
            true
          } else false
        }
        if (expired) {
          upstreamSubscription.cancel()
          self.synchronized(downstream.onError(new HttpTimeoutException(timeoutMessage)))
        } else if (!self.synchronized(done))
          schedule(if (demand.get > 0) idleMs - idleForMs else idleMs)
      }

      private def stopTimer(): Unit = {
        val t = timer
        if (t != null) t.cancel(false)
      }

      override def onSubscribe(s: Flow.Subscription): Unit = {
        upstreamSubscription = s
        lastActivity = System.nanoTime()
        downstream.onSubscribe(new Flow.Subscription {
          override def request(n: Long): Unit = {
            if (n > 0) {
              // demand appearing (again) restarts the idle clock
              if (
                demand.getAndAccumulate(
                  n,
                  (
                    a,
                    b
                  ) => if (a + b < 0) Long.MaxValue else a + b
                ) == 0
              )
                lastActivity = System.nanoTime()
            }
            s.request(n)
          }

          override def cancel(): Unit = {
            self.synchronized { done = true }
            stopTimer()
            s.cancel()
          }
        })
        schedule(idleMs)
      }

      override def onNext(item: T): Unit = {
        lastActivity = System.nanoTime()
        demand.getAndUpdate(d => if (d == Long.MaxValue || d <= 0) d else d - 1)
        self.synchronized(if (!done) downstream.onNext(item))
      }

      override def onError(t: Throwable): Unit = {
        stopTimer()
        self.synchronized {
          if (!done) {
            done = true
            downstream.onError(t)
          }
        }
      }

      override def onComplete(): Unit = {
        stopTimer()
        self.synchronized {
          if (!done) {
            done = true
            downstream.onComplete()
          }
        }
      }
    })
}
