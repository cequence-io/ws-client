package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.impl.{BridgeCounter, Cursor, RunContext}

import java.util.ArrayDeque
import java.util.concurrent.Flow
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * An execution context with every task on a thread of its own, so the checker interleaves the
 * tasks; `joinAll()` waits until none is left (quiescence).
 */
final class ThreadPerTask extends ExecutionContext {
  // guarded by `this`
  private val threads = new ArrayDeque[Thread]()

  override def execute(runnable: Runnable): Unit = {
    val thread = new Thread(runnable)
    synchronized(threads.add(thread))
    thread.start()
  }

  override def reportFailure(cause: Throwable): Unit = throw new AssertionError(cause)

  def joinAll(): Unit = {
    var thread = synchronized(threads.poll())
    while (thread != null) {
      thread.join()
      thread = synchronized(threads.poll())
    }
  }
}

/** Every task on the calling thread. */
object Inline extends ExecutionContext {
  override def execute(runnable: Runnable): Unit = runnable.run()
  override def reportFailure(cause: Throwable): Unit = throw new AssertionError(cause)
}

object ModelCheckSupport {

  /** A run context with `ec` for everything. */
  def runContext(ec: ExecutionContext): RunContext =
    new RunContext(ec, ec, BridgeCounter.Global)
}

/**
 * Pulls a cursor until its end (or `limit` results), callback style - no blocking wait inside
 * the model. One `next()` at a time, as the `Cursor` contract asks.
 */
final class Puller[A](
  cursor: Cursor[A],
  limit: Int
) {
  @volatile var results: Vector[Try[Option[A]]] = Vector.empty
  @volatile var last: Future[Option[A]] = _

  def pull(): Unit = {
    val future = cursor.next()
    last = future
    future.onComplete { result =>
      results :+= result
      result match {
        case Success(Some(_)) if results.size < limit => pull()
        case _                                        => ()
      }
    }(Inline)
  }

  def elements: Vector[A] = results.collect { case Success(Some(element)) => element }
}

/**
 * A legal upstream whose signals come from a thread of their own (`produce()`), not from
 * inside `request` - what an HTTP client's executor does. Written from the Reactive Streams
 * rules: onNext only against demand (1.1), signals serial (1.3), nothing after the terminal
 * signal (1.7), signals stop after cancel (1.8; one in-flight onNext may still arrive, 2.8).
 * It records what a subscriber owes it: serial subscription calls (2.7).
 */
final class ThreadedSource(elements: Int) extends Flow.Publisher[Integer] {

  // guarded by `this`
  private var subscriber: Flow.Subscriber[_ >: Integer] = _
  private var demand = 0L
  private var cancelledFlag = false
  private var cancelCalls = 0
  private var completedFlag = false
  // deliberately unguarded: a concurrent pair of subscription calls is what rule 2.7 forbids,
  // and the checker interleaves these plain accesses
  private var inCall = false
  @volatile var violation: String = _

  override def subscribe(s: Flow.Subscriber[_ >: Integer]): Unit = {
    synchronized { subscriber = s }
    s.onSubscribe(new Flow.Subscription {
      override def request(n: Long): Unit = {
        enter("request")
        ThreadedSource.this.synchronized {
          demand = if (Long.MaxValue - n < demand) Long.MaxValue else demand + n
          ThreadedSource.this.notifyAll()
        }
        inCall = false
      }

      override def cancel(): Unit = {
        enter("cancel")
        ThreadedSource.this.synchronized {
          cancelCalls += 1
          cancelledFlag = true
          ThreadedSource.this.notifyAll()
        }
        inCall = false
      }
    })
  }

  private def enter(name: String): Unit = {
    if (inCall)
      violation = s"rule 2.7: $name called concurrently with another subscription call"
    inCall = true
  }

  /** The producer thread's body: emits each element once it is demanded, then completes. */
  def produce(): Unit = {
    var next = 0
    var open = true
    while (open && next < elements) {
      val emit = synchronized {
        while (demand == 0 && !cancelledFlag) wait()
        if (cancelledFlag) false
        else {
          demand -= 1
          true
        }
      }
      if (emit) {
        subscriber.onNext(Integer.valueOf(next))
        next += 1
      } else open = false
    }
    if (open && !synchronized(cancelledFlag)) {
      synchronized { completedFlag = true }
      subscriber.onComplete()
    }
  }

  def cancelled: Boolean = synchronized(cancelledFlag)

  /** The source signalled onComplete itself - a later cancel has nothing left to reach. */
  def completed: Boolean = synchronized(completedFlag)

  def cancels: Int = synchronized(cancelCalls)
}

/**
 * The downstream end: records the signals and checks them against the Reactive Streams rules a
 * `Flow.Publisher` owes its subscriber. `onElement` runs inside onNext (to re-request or
 * cancel from there).
 */
final class RsProbe(onElement: RsProbe => Unit = _ => ()) extends Flow.Subscriber[Integer] {

  @volatile var subscription: Flow.Subscription = _
  // plain fields on purpose: the checker interleaves their accesses
  private var inSignal = false
  private var terminal = false
  var requested = 0L // set by the requesting side BEFORE it calls request
  var received: Vector[Int] = Vector.empty
  var completed = false
  var failure: Throwable = _
  var violations: List[String] = Nil

  private def enter(name: String): Unit = {
    if (inSignal) violations ::= s"rule 1.3: $name concurrent with another signal"
    inSignal = true
    if (terminal) violations ::= s"rule 1.7: $name after the terminal signal"
  }

  override def onSubscribe(s: Flow.Subscription): Unit = {
    if (subscription != null) violations ::= "rule 1.9 / 2.5: second onSubscribe"
    subscription = s
  }

  override def onNext(item: Integer): Unit = {
    enter("onNext")
    received :+= item.intValue
    if (received.size > requested)
      violations ::= s"rule 1.1: ${received.size} onNext for $requested requested"
    onElement(this)
    inSignal = false
  }

  override def onError(t: Throwable): Unit = {
    enter("onError")
    terminal = true
    failure = t
    inSignal = false
  }

  override def onComplete(): Unit = {
    enter("onComplete")
    terminal = true
    completed = true
    inSignal = false
  }

  def request(n: Long): Unit = {
    requested += n
    subscription.request(n)
  }
}
