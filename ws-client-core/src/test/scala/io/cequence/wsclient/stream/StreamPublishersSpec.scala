package io.cequence.wsclient.stream

import io.cequence.wsclient.domain.CequenceWSException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

/**
 * Unit tests for the backend-agnostic stream publishers - a plain, synchronous,
 * demand-respecting stub publisher stands in for an HTTP response body.
 */
class StreamPublishersSpec extends AnyWordSpec with Matchers {

  // a single-shot, demand-respecting publisher over a fixed list (synchronous delivery)
  private class ListPublisher[T](items: Seq[T]) extends Flow.Publisher[T] {
    override def subscribe(subscriber: Flow.Subscriber[_ >: T]): Unit = {
      val iterator = items.iterator
      var cancelled = false
      var emitting = false
      var pending = 0L

      subscriber.onSubscribe(new Flow.Subscription {
        override def request(n: Long): Unit = {
          pending += n
          if (!emitting) {
            emitting = true
            try {
              while (pending > 0 && iterator.hasNext && !cancelled) {
                pending -= 1
                subscriber.onNext(iterator.next())
              }
              if (!iterator.hasNext && !cancelled) {
                cancelled = true
                subscriber.onComplete()
              }
            } finally emitting = false
          }
        }
        override def cancel(): Unit = cancelled = true
      })
    }
  }

  private class CollectingSubscriber[T](cancelAfter: Option[Int] = None)
      extends Flow.Subscriber[T] {
    val received = new ListBuffer[T]
    var error: Option[Throwable] = None
    var completed = false
    private var subscription: Flow.Subscription = _

    override def onSubscribe(s: Flow.Subscription): Unit = {
      subscription = s
      s.request(1)
    }
    override def onNext(item: T): Unit = {
      received += item
      if (cancelAfter.contains(received.size)) subscription.cancel()
      else subscription.request(1)
    }
    override def onError(t: Throwable): Unit = error = Some(t)
    override def onComplete(): Unit = completed = true
  }

  private def chunks(strings: String*): Seq[ByteBuffer] =
    strings.map(s => ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)))

  private def framed(
    input: Seq[ByteBuffer],
    delimiter: String = "\n\n",
    maxFrameLength: Int = 20000
  ): CollectingSubscriber[ByteBuffer] = {
    val publisher = new TransformPublisher(
      new ListPublisher(input),
      () =>
        new StreamTransformers.DelimiterFramer(
          delimiter.getBytes(StandardCharsets.UTF_8),
          maxFrameLength
        )
    )
    val subscriber = new CollectingSubscriber[ByteBuffer]
    publisher.subscribe(subscriber)
    subscriber
  }

  private def frameStrings(subscriber: CollectingSubscriber[ByteBuffer]): Seq[String] =
    subscriber.received.toList.map { buffer =>
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      new String(bytes, StandardCharsets.UTF_8)
    }

  "DeferredPublisher" should {

    "not invoke the factory before the first subscribe and invoke it exactly once" in {
      val invocations = new AtomicInteger(0)
      val publisher = new DeferredPublisher[Int](() => {
        invocations.incrementAndGet()
        new ListPublisher(Seq(1, 2, 3))
      })

      invocations.get() shouldBe 0

      val subscriber = new CollectingSubscriber[Int]
      publisher.subscribe(subscriber)

      invocations.get() shouldBe 1
      subscriber.received.toList shouldBe List(1, 2, 3)
      subscriber.completed shouldBe true
    }

    "reject a second subscriber with onError" in {
      val publisher = new DeferredPublisher[Int](() => new ListPublisher(Seq(1)))
      publisher.subscribe(new CollectingSubscriber[Int])

      val second = new CollectingSubscriber[Int]
      publisher.subscribe(second)

      second.error.map(_.getClass.getSimpleName) shouldBe Some("IllegalStateException")
      second.received shouldBe empty
    }
  }

  "DelimiterFramer via TransformPublisher" should {

    "split frames on the delimiter" in {
      val s = framed(chunks("a\n\nb\n\nc\n\n"))
      frameStrings(s) shouldBe Seq("a", "b", "c")
      s.completed shouldBe true
    }

    "reassemble a delimiter split across chunk boundaries" in {
      val s = framed(chunks("first\n", "\nsec", "ond\n\nthi", "rd\n\n"))
      frameStrings(s) shouldBe Seq("first", "second", "third")
      s.completed shouldBe true
    }

    "emit a trailing unterminated frame on completion (allowTruncation)" in {
      val s = framed(chunks("a\n\ntrailing"))
      frameStrings(s) shouldBe Seq("a", "trailing")
      s.completed shouldBe true
    }

    "fail with CequenceWSFrameTooLongException when a frame exceeds maxFrameLength" in {
      val s = framed(chunks("x" * 50), maxFrameLength = 10)
      s.error.map(_.getClass.getSimpleName) shouldBe Some("CequenceWSFrameTooLongException")
      s.completed shouldBe false
    }

    "fail when an oversized frame IS terminated by a delimiter (akka Framing parity)" in {
      val s = framed(chunks(("x" * 50) + "\n\n"), maxFrameLength = 10)
      s.error.map(_.getClass.getSimpleName) shouldBe Some("CequenceWSFrameTooLongException")
      s.received shouldBe empty
      s.completed shouldBe false
    }

    "support a multi-character delimiter (Gemini style)" in {
      val s = framed(chunks("{\"a\":1}\n,\r\n{\"a\":2}\n,\r\n"), delimiter = "\n,\r\n")
      frameStrings(s) shouldBe Seq("{\"a\":1}", "{\"a\":2}")
    }
  }

  "JsonFrameParser via TransformPublisher" should {

    def jsonStream(
      input: Seq[ByteBuffer],
      delimiter: String = "\n\n",
      stripPrefix: Option[String] = None,
      stripSuffix: Option[String] = None
    ) = {
      val framedPublisher = new TransformPublisher(
        new ListPublisher(input),
        () =>
          new StreamTransformers.DelimiterFramer(
            delimiter.getBytes(StandardCharsets.UTF_8),
            20000
          )
      )
      val jsonPublisher = new TransformPublisher(
        framedPublisher,
        () => new StreamTransformers.JsonFrameParser(stripPrefix, stripSuffix)
      )
      val subscriber = new CollectingSubscriber[play.api.libs.json.JsValue]
      jsonPublisher.subscribe(subscriber)
      subscriber
    }

    "parse data:-prefixed SSE events and complete early at [DONE]" in {
      val s = jsonStream(
        chunks("data: {\"i\":1}\n\ndata: {\"i\":2}\n\ndata: [DONE]\n\ndata: {\"i\":3}\n\n")
      )
      s.received.toList shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
      s.completed shouldBe true
      s.error shouldBe None
    }

    "ignore SSE comment frames (keep-alives) and blank frames" in {
      val s = jsonStream(
        chunks(
          "data: {\"i\":1}\n\n: OPENROUTER PROCESSING\n\n\n\n: ping\r\n\r\ndata: {\"i\":2}\n\ndata: [DONE]\n\n"
        )
      )
      s.received.toList shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
      s.completed shouldBe true
      s.error shouldBe None
    }

    "strip a prefix and suffix (Gemini array style)" in {
      val s = jsonStream(
        chunks("[{\"i\":1}\n,\r\n{\"i\":2}]\n,\r\n"),
        delimiter = "\n,\r\n",
        stripPrefix = Some("["),
        stripSuffix = Some("]")
      )
      s.received.toList shouldBe List(Json.obj("i" -> 1), Json.obj("i" -> 2))
    }

    "fail with CequenceWSException on a non-JSON frame" in {
      val s = jsonStream(chunks("data: not-json\n\n"))
      s.error.map(_.getClass.getSimpleName) shouldBe Some("CequenceWSException")
      s.error.get shouldBe a[CequenceWSException]
    }
  }

  "TransformPublisher" should {

    "not deadlock when a downstream cancel races an element through two chained transforms" in {
      // up to 1.1.5 the cancel took the inner transform's monitor, then the outer's; the
      // upstream thread delivering an element held the outer's, then took the inner's
      val executor = java.util.concurrent.Executors.newCachedThreadPool()
      try {
        (1 to 300).foreach { _ =>
          // an endless upstream emitting on its own thread
          val upstream = new Flow.Publisher[Int] {
            override def subscribe(subscriber: Flow.Subscriber[_ >: Int]): Unit = {
              val demand = new java.util.concurrent.atomic.AtomicLong()
              val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
              subscriber.onSubscribe(new Flow.Subscription {
                override def request(n: Long): Unit = { demand.addAndGet(n); () }
                override def cancel(): Unit = cancelled.set(true)
              })
              executor.execute(new Runnable {
                override def run(): Unit = {
                  var i = 0
                  while (!cancelled.get()) {
                    if (demand.get() > 0) {
                      demand.decrementAndGet()
                      subscriber.onNext(i)
                      i += 1
                    } else Thread.onSpinWait()
                  }
                }
              })
            }
          }
          def identityTransform = () =>
            new StreamTransformer[Int, Int] {
              override def onInput(input: Int): StreamTransformer.Output[Int] =
                StreamTransformer.Output(Seq(input))
            }
          val chained = new TransformPublisher(
            new TransformPublisher(upstream, identityTransform),
            identityTransform
          )

          val first = new java.util.concurrent.CountDownLatch(1)
          val subscription =
            new java.util.concurrent.atomic.AtomicReference[Flow.Subscription]()
          chained.subscribe(new Flow.Subscriber[Int] {
            override def onSubscribe(s: Flow.Subscription): Unit = {
              subscription.set(s)
              s.request(Long.MaxValue)
            }
            override def onNext(item: Int): Unit = first.countDown()
            override def onError(throwable: Throwable): Unit = ()
            override def onComplete(): Unit = ()
          })
          first.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

          val cancelled = executor.submit(new Runnable {
            override def run(): Unit = subscription.get().cancel()
          })
          cancelled.get(5, java.util.concurrent.TimeUnit.SECONDS) // a deadlock times out here
        }
      } finally executor.shutdownNow()
    }

    "not deadlock when a downstream request races the upstream's completion (two transforms)" in {
      // up to 1.2.0-SNAPSHOT the request took the outer transform's monitor, then (requesting
      // its upstream) the inner's; the upstream completing held the inner's, then (completing
      // downstream) took the outer's
      val executor = java.util.concurrent.Executors.newCachedThreadPool()
      try {
        (1 to 500).foreach { _ =>
          val upstreamSubscriber =
            new java.util.concurrent.atomic.AtomicReference[Flow.Subscriber[_ >: Int]]()
          val upstream = new Flow.Publisher[Int] {
            override def subscribe(subscriber: Flow.Subscriber[_ >: Int]): Unit = {
              upstreamSubscriber.set(subscriber)
              subscriber.onSubscribe(new Flow.Subscription {
                override def request(n: Long): Unit = ()
                override def cancel(): Unit = ()
              })
            }
          }
          def identityTransform = () =>
            new StreamTransformer[Int, Int] {
              override def onInput(input: Int): StreamTransformer.Output[Int] =
                StreamTransformer.Output(Seq(input))
            }
          val chained = new TransformPublisher(
            new TransformPublisher(upstream, identityTransform),
            identityTransform
          )

          val ended = new java.util.concurrent.CountDownLatch(1)
          val subscription =
            new java.util.concurrent.atomic.AtomicReference[Flow.Subscription]()
          chained.subscribe(new Flow.Subscriber[Int] {
            override def onSubscribe(s: Flow.Subscription): Unit = subscription.set(s)
            override def onNext(item: Int): Unit = ()
            override def onError(throwable: Throwable): Unit = ended.countDown()
            override def onComplete(): Unit = ended.countDown()
          })

          val start = new java.util.concurrent.CountDownLatch(1)
          executor.execute(new Runnable {
            override def run(): Unit = { start.await(); subscription.get().request(1) }
          })
          executor.execute(new Runnable {
            override def run(): Unit = { start.await(); upstreamSubscriber.get().onComplete() }
          })
          start.countDown()
          // a deadlock times out here
          ended.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        }
      } finally executor.shutdownNow()
    }

    "not deadlock when a re-entrant [DONE] cancel races the upstream's completion (three transforms)" in {
      // the consumer's request makes the middle transform deliver a queued DONE, on which the
      // last one terminates and cancels the middle one - re-entrantly, under the middle's
      // monitor; up to 1.2.0-SNAPSHOT that cancel went upstream right there, while the source
      // completing held the first transform's monitor and waited for the middle's
      val executor = java.util.concurrent.Executors.newCachedThreadPool()
      try {
        (1 to 500).foreach { _ =>
          val sourceSubscriber =
            new java.util.concurrent.atomic.AtomicReference[Flow.Subscriber[_ >: String]]()
          val requested = new java.util.concurrent.CountDownLatch(1)
          val source = new Flow.Publisher[String] {
            override def subscribe(subscriber: Flow.Subscriber[_ >: String]): Unit = {
              sourceSubscriber.set(subscriber)
              subscriber.onSubscribe(new Flow.Subscription {
                override def request(n: Long): Unit = requested.countDown()
                override def cancel(): Unit = ()
              })
            }
          }
          val identity = () =>
            new StreamTransformer[String, String] {
              override def onInput(input: String): StreamTransformer.Output[String] =
                StreamTransformer.Output(Seq(input))
            }
          // one input, two outputs: "a" and the terminating "DONE"
          val split = () =>
            new StreamTransformer[String, String] {
              override def onInput(input: String): StreamTransformer.Output[String] =
                StreamTransformer.Output(Seq("a", "DONE"))
            }
          val untilDone = () =>
            new StreamTransformer[String, String] {
              override def onInput(input: String): StreamTransformer.Output[String] =
                if (input == "DONE") StreamTransformer.Output(Nil, done = true)
                else StreamTransformer.Output(Seq(input))
            }
          val chained = new TransformPublisher(
            new TransformPublisher(new TransformPublisher(source, identity), split),
            untilDone
          )

          val first = new java.util.concurrent.CountDownLatch(1)
          val ended = new java.util.concurrent.CountDownLatch(1)
          val subscription =
            new java.util.concurrent.atomic.AtomicReference[Flow.Subscription]()
          chained.subscribe(new Flow.Subscriber[String] {
            override def onSubscribe(s: Flow.Subscription): Unit = {
              subscription.set(s)
              s.request(1)
            }
            override def onNext(item: String): Unit = first.countDown()
            override def onError(throwable: Throwable): Unit = ended.countDown()
            override def onComplete(): Unit = ended.countDown()
          })
          requested.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
          sourceSubscriber.get().onNext("x") // "a" goes out, "DONE" stays queued in the middle
          first.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

          val start = new java.util.concurrent.CountDownLatch(1)
          executor.execute(new Runnable {
            override def run(): Unit = { start.await(); subscription.get().request(1) }
          })
          executor.execute(new Runnable {
            override def run(): Unit = { start.await(); sourceSubscriber.get().onComplete() }
          })
          start.countDown()
          // a deadlock times out here
          ended.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        }
      } finally executor.shutdownNow()
    }

    "treat a subscriber that throws as cancelled - the cancel reaches the upstream" in {
      val upstreamCancelled = new java.util.concurrent.CountDownLatch(1)
      val upstream = new Flow.Publisher[Int] {
        override def subscribe(subscriber: Flow.Subscriber[_ >: Int]): Unit =
          subscriber.onSubscribe(new Flow.Subscription {
            private val sent = new java.util.concurrent.atomic.AtomicBoolean(false)
            override def request(n: Long): Unit =
              if (sent.compareAndSet(false, true)) subscriber.onNext(1)
            override def cancel(): Unit = upstreamCancelled.countDown()
          })
      }
      def identityTransform = () =>
        new StreamTransformer[Int, Int] {
          override def onInput(input: Int): StreamTransformer.Output[Int] =
            StreamTransformer.Output(Seq(input))
        }
      new TransformPublisher(
        new TransformPublisher(upstream, identityTransform),
        identityTransform
      ).subscribe(new Flow.Subscriber[Int] {
        override def onSubscribe(s: Flow.Subscription): Unit = s.request(1)
        override def onNext(item: Int): Unit =
          throw new IllegalStateException("subscriber bug")
        override def onError(throwable: Throwable): Unit = ()
        override def onComplete(): Unit = ()
      })
      upstreamCancelled.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    }

    "stop delivering after downstream cancellation" in {
      val publisher = new TransformPublisher[Int, Int](
        new ListPublisher((1 to 100).toList),
        () =>
          new StreamTransformer[Int, Int] {
            override def onInput(input: Int): StreamTransformer.Output[Int] =
              StreamTransformer.Output(Seq(input))
          }
      )
      val subscriber = new CollectingSubscriber[Int](cancelAfter = Some(3))
      publisher.subscribe(subscriber)

      subscriber.received.toList shouldBe List(1, 2, 3)
      subscriber.completed shouldBe false
      subscriber.error shouldBe None
    }
  }
}
