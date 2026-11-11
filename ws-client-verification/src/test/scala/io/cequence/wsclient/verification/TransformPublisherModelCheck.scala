package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.{StreamTransformer, TransformPublisher}

import java.util.concurrent.Flow

/**
 * Two chained `TransformPublisher`s - as the jdk / zio-http engines chain the framer and the
 * JSON parser - between an upstream that signals from a thread of its own and a downstream
 * that requests and cancels from another. Checked: the Reactive Streams rules on both sides
 * (`RsProbe`, `ThreadedSource`) and what must hold once every thread finished. A deadlock is
 * what 1.1.5 had here: it called the upstream's subscription while holding its monitor.
 */
object TransformPublisherModelCheck extends ModelCheckSuite("TransformPublisher") {

  private type Transformer = () => StreamTransformer[Integer, Integer]

  private val identity: Transformer = () =>
    new StreamTransformer[Integer, Integer] {
      override def onInput(input: Integer): StreamTransformer.Output[Integer] =
        StreamTransformer.Output(Seq(input))
    }

  // ends the stream at the first element, like the `[DONE]` event
  private val doneAtFirst: Transformer = () =>
    new StreamTransformer[Integer, Integer] {
      override def onInput(input: Integer): StreamTransformer.Output[Integer] =
        StreamTransformer.Output(Seq(input), done = true)
    }

  private def chain(
    source: ThreadedSource,
    second: Transformer = identity
  ): Flow.Publisher[Integer] =
    new TransformPublisher[Integer, Integer](
      new TransformPublisher[Integer, Integer](source, identity),
      second
    )

  private def rules(
    source: ThreadedSource,
    probe: RsProbe
  ): Unit = {
    ensure(probe.violations.isEmpty, probe.violations.mkString("; "))
    ensure(source.violation == null, source.violation)
    ensure(source.cancels <= 1, s"the upstream was cancelled ${source.cancels} times")
    ensure(probe.received == probe.received.indices.toVector, s"order: ${probe.received}")
  }

  scenario("every element and the completion arrive (no lost request, no lost wake-up)") {
    val source = new ThreadedSource(2)
    val probe = new RsProbe()
    chain(source).subscribe(probe)
    val producer = thread(source.produce())
    val consumer = thread {
      probe.request(1)
      probe.request(1)
    }
    producer.join()
    consumer.join()
    rules(source, probe)
    ensure(probe.received == Vector(0, 1), s"received ${probe.received}")
    ensure(probe.completed, "no onComplete")
  }

  scenario("a cancel racing the next element reaches the upstream (the 1.1.5 deadlock)") {
    val source = new ThreadedSource(2)
    val probe = new RsProbe()
    chain(source).subscribe(probe)
    val producer = thread(source.produce())
    val consumer = thread {
      probe.request(1)
      probe.subscription.cancel()
    }
    producer.join()
    consumer.join()
    rules(source, probe)
    ensure(source.cancelled || source.completed, "the cancel never reached the upstream")
    ensure(probe.failure == null, s"failed: ${probe.failure}")
  }

  scenario("a subscriber that re-requests inside onNext, cancelled from another thread") {
    val source = new ThreadedSource(3)
    val probe = new RsProbe(p => p.request(1))
    chain(source).subscribe(probe)
    val producer = thread(source.produce())
    val consumer = thread(probe.request(1))
    consumer.join()
    val canceller = thread(probe.subscription.cancel())
    producer.join()
    canceller.join()
    rules(source, probe)
    ensure(source.cancelled || source.completed, "the cancel never reached the upstream")
  }

  scenario("take(1): a cancel from inside onNext, racing the upstream's next signals") {
    val source = new ThreadedSource(2)
    val probe = new RsProbe(p => p.subscription.cancel())
    chain(source).subscribe(probe)
    val producer = thread(source.produce())
    val consumer = thread(probe.request(2))
    producer.join()
    consumer.join()
    rules(source, probe)
    ensure(probe.received == Vector(0), s"signals after the cancel: ${probe.received}")
    ensure(!probe.completed && probe.failure == null, "a terminal signal after the cancel")
    ensure(source.cancelled, "the cancel never reached the upstream")
  }

  scenario("[DONE]: the transformer ends the stream early - completion down, cancel up") {
    val source = new ThreadedSource(2)
    val probe = new RsProbe()
    chain(source, doneAtFirst).subscribe(probe)
    val producer = thread(source.produce())
    val consumer = thread {
      probe.request(1)
      probe.request(1)
    }
    producer.join()
    consumer.join()
    rules(source, probe)
    ensure(probe.received == Vector(0), s"received ${probe.received}")
    ensure(probe.completed, "no onComplete after the early end")
    ensure(source.cancelled, "the early end never cancelled the upstream")
  }
}
