package io.cequence.wsclient.testkit

import java.util.concurrent.{CountDownLatch, Flow, TimeUnit}
import scala.collection.mutable.ListBuffer

/**
 * A plain `java.util.concurrent.Flow.Subscriber` - deliberately no stream library - that
 * requests one element at a time and records everything it receives.
 */
class LatchedSubscriber[T] extends Flow.Subscriber[T] {
  private val buffer = ListBuffer[T]()
  @volatile var error: Option[Throwable] = None
  @volatile var completed = false
  private val done = new CountDownLatch(1)
  private var subscription: Flow.Subscription = _

  def received: List[T] = buffer.synchronized(buffer.toList)

  override def onSubscribe(s: Flow.Subscription): Unit = {
    subscription = s
    s.request(1)
  }

  override def onNext(item: T): Unit = {
    buffer.synchronized(buffer += item)
    subscription.request(1)
  }

  override def onError(t: Throwable): Unit = {
    error = Some(t)
    done.countDown()
  }

  override def onComplete(): Unit = {
    completed = true
    done.countDown()
  }

  def awaitDone(seconds: Int): Boolean =
    done.await(seconds.toLong, TimeUnit.SECONDS)
}
