package io.cequence.wsclient.stream

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, Flow, ThreadFactory}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/** Shared helpers for the AsyncStream specs. */
trait StreamTestSupport {

  /**
   * The run ec: its threads are named "stream-test-ec-N", so specs can tell where code ran.
   */
  implicit val ec: ExecutionContext = StreamTestSupport.testEc

  def await[T](
    future: Future[T],
    timeout: FiniteDuration = 10.seconds
  ): T = Await.result(future, timeout)

  def run[A](stream: AsyncStream[A]): Seq[A] = await(stream.runToSeq)

  def runFailure(stream: AsyncStream[_]): Throwable =
    Try(await(stream.runToSeq)).failed.getOrElse(
      throw new AssertionError("the stream did not fail")
    )

  def onEcThread: Boolean = Thread.currentThread().getName.startsWith("stream-test-ec-")

  /**
   * A cold publisher over `items`, emitting on its own thread ("test-io-N" - like an HTTP
   * client's IO thread), respecting demand and recording subscriptions, cancellations and
   * completions.
   */
  final class TrackedPublisher[A](
    items: Seq[A],
    failWith: Option[Throwable] = None,
    hangAtEnd: Boolean = false
  ) {
    val subscriptions = new AtomicInteger()
    val cancellations = new AtomicInteger()
    val requested = new AtomicLong()
    val emittedOnThreads = new ConcurrentLinkedQueue[String]()

    def stream: AsyncStream[A] = AsyncStream.fromPublisher(() => publisher)

    def publisher: Flow.Publisher[A] = new Flow.Publisher[A] {
      override def subscribe(subscriber: Flow.Subscriber[_ >: A]): Unit = {
        subscriptions.incrementAndGet()
        val executor = StreamTestSupport.ioExecutor
        val iterator = items.iterator
        val cancelled = new AtomicBoolean(false)
        val demand = new AtomicLong()
        val wip = new AtomicInteger()
        val terminated = new AtomicBoolean(false)

        def drain(): Unit =
          if (wip.getAndIncrement() == 0) {
            var missed = 1
            while (missed != 0) {
              while (!cancelled.get() && demand.get() > 0 && iterator.hasNext) {
                demand.decrementAndGet()
                emittedOnThreads.add(Thread.currentThread().getName)
                subscriber.onNext(iterator.next())
              }
              if (
                !cancelled.get() && !iterator.hasNext && !hangAtEnd && terminated
                  .compareAndSet(false, true)
              )
                failWith match {
                  case Some(e) => subscriber.onError(e)
                  case None    => subscriber.onComplete()
                }
              missed = wip.addAndGet(-missed)
            }
          }

        executor.execute(() =>
          subscriber.onSubscribe(new Flow.Subscription {
            override def request(n: Long): Unit = {
              requested.addAndGet(n)
              demand.addAndGet(n)
              executor.execute(() => drain())
            }
            override def cancel(): Unit =
              if (cancelled.compareAndSet(false, true)) cancellations.incrementAndGet()
          })
        )
      }
    }
  }

  /** Counts per-run thunk evaluations. */
  final class Counter {
    private val count = new AtomicInteger()
    def apply(): Int = count.get()
    def inc(): Unit = count.incrementAndGet()
  }
}

object StreamTestSupport {

  private val ecCounter = new AtomicInteger()
  private val ioCounter = new AtomicInteger()

  val testEc: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(
      4,
      new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"stream-test-ec-${ecCounter.incrementAndGet()}")
          t.setDaemon(true)
          t
        }
      }
    )
  )

  val ioExecutor: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(
    2,
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"test-io-${ioCounter.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    }
  )
}
