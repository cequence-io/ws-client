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

    "fail with CequenceWSException when a frame exceeds maxFrameLength" in {
      val s = framed(chunks("x" * 50), maxFrameLength = 10)
      s.error.map(_.getClass.getSimpleName) shouldBe Some("CequenceWSException")
      s.completed shouldBe false
    }

    "fail when an oversized frame IS terminated by a delimiter (akka Framing parity)" in {
      val s = framed(chunks(("x" * 50) + "\n\n"), maxFrameLength = 10)
      s.error.map(_.getClass.getSimpleName) shouldBe Some("CequenceWSException")
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
