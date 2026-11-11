package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.impl.{
  Cursor,
  CursorPublisher,
  Opener,
  RunContext,
  SubscriberCursor
}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

/**
 * A cursor of `0 until elements` that checks its side of the `Cursor` contract: at most one
 * outstanding `next()`, which a `cancel()` may race. With `async`, each result completes on
 * that execution context.
 */
final class CountingCursor(
  elements: Int,
  async: Option[ExecutionContext]
) extends Cursor[Integer] {
  // plain fields on purpose: the checker interleaves their accesses
  private var index = 0
  private var outstanding: Future[Option[Integer]] = _
  var violations: List[String] = Nil
  @volatile var cancels = 0
  @volatile var ended = false

  override def next(): Future[Option[Integer]] = {
    if (outstanding != null && !outstanding.isCompleted)
      violations ::= "Cursor contract: next() while the previous one is outstanding"
    if (ended) violations ::= "Cursor contract: next() after the end"
    val result: Option[Integer] =
      if (cancels > 0 || index >= elements) None
      else {
        index += 1
        Some(Integer.valueOf(index - 1))
      }
    if (result.isEmpty) ended = true
    val future = async match {
      case None => Future.successful(result)
      case Some(ec) =>
        val promise = Promise[Option[Integer]]()
        ec.execute(() => promise.success(result))
        promise.future
    }
    outstanding = future
    future
  }

  override def cancel(): Unit = cancels += 1
}

/**
 * The two Reactive Streams bridges of the neutral interpreter: `CursorPublisher` (a run as a
 * publisher) against the publisher's rules (1.1, 1.3, 1.5, 1.7) and the `Cursor` contract it
 * owes its run; `SubscriberCursor` (a publisher as a run) against the subscriber's rule 2.7
 * and the contract it owes its consumer - a `cancel()` completes the outstanding `next()`.
 */
object BridgeModelCheck extends ModelCheckSuite("Bridges") {

  import ModelCheckSupport.runContext

  private def opener(cursor: CountingCursor): Opener[Integer] =
    new Opener[Integer] {
      override def open(ctx: RunContext): Cursor[Integer] = cursor
    }

  private final class PublisherRun(
    elements: Int,
    async: Boolean,
    onElement: RsProbe => Unit = _ => ()
  ) {
    val ec = new ThreadPerTask
    val cursor = new CountingCursor(elements, if (async) Some(ec) else None)
    val probe = new RsProbe(onElement)

    new CursorPublisher[Integer](opener(cursor), runContext(ec)).subscribe(probe)
    // onSubscribe comes from the run's ec
    ec.joinAll()
    ensure(probe.subscription != null, "no onSubscribe")

    def rules(): Unit = {
      ensure(probe.violations.isEmpty, probe.violations.mkString("; "))
      ensure(cursor.violations.isEmpty, cursor.violations.mkString("; "))
      ensure(probe.received == probe.received.indices.toVector, s"order: ${probe.received}")
    }
  }

  Seq(false, true).foreach { async =>
    val kind = if (async) "async cursor" else "sync cursor"

    scenario(s"CursorPublisher ($kind): two racing requests deliver everything") {
      val run = new PublisherRun(2, async)
      run.probe.requested = 2
      val first = thread(run.probe.subscription.request(1))
      val second = thread(run.probe.subscription.request(1))
      first.join()
      second.join()
      run.ec.joinAll()
      run.rules()
      ensure(run.probe.received == Vector(0, 1), s"received ${run.probe.received}")
      ensure(run.probe.completed, "no onComplete (rule 1.5: it needs no further request)")
    }

    scenario(s"CursorPublisher ($kind): a cancel racing a request reaches the run") {
      val run = new PublisherRun(2, async)
      run.probe.requested = 1
      val requester = thread(run.probe.subscription.request(1))
      val canceller = thread(run.probe.subscription.cancel())
      requester.join()
      canceller.join()
      run.ec.joinAll()
      run.rules()
      ensure(run.cursor.cancels >= 1, "the cancel never reached the run")
      ensure(run.probe.failure == null, s"failed: ${run.probe.failure}")
    }

    scenario(
      s"CursorPublisher ($kind): a request inside onNext, cancelled from another thread"
    ) {
      val run = new PublisherRun(3, async, p => p.request(1))
      val requester = thread(run.probe.request(1))
      val canceller = thread(run.probe.subscription.cancel())
      requester.join()
      canceller.join()
      run.ec.joinAll()
      run.rules()
      ensure(run.cursor.cancels >= 1, "the cancel never reached the run")
    }

    scenario(s"CursorPublisher ($kind): a cancel inside onNext stops every further signal") {
      val run = new PublisherRun(2, async, p => p.subscription.cancel())
      val requester = thread(run.probe.request(2))
      requester.join()
      run.ec.joinAll()
      run.rules()
      ensure(
        run.probe.received == Vector(0),
        s"signals after the cancel: ${run.probe.received}"
      )
      ensure(
        !run.probe.completed && run.probe.failure == null,
        "a terminal signal after the cancel"
      )
      ensure(run.cursor.cancels >= 1, "the cancel never reached the run")
    }
  }

  Seq(false, true).foreach { async =>
    val kind = if (async) "hand-over on ec" else "inline hand-over"

    scenario(s"SubscriberCursor ($kind): every element, then the end; serial calls (2.7)") {
      val ec = new ThreadPerTask
      val source = new ThreadedSource(3)
      val cursor =
        SubscriberCursor[Integer](() => source, 2, runContext(if (async) ec else Inline))
      val producer = thread(source.produce())
      val puller = new Puller(cursor, Int.MaxValue)
      val consumer = thread(puller.pull())
      producer.join()
      consumer.join()
      ec.joinAll()
      ensure(source.violation == null, source.violation)
      ensure(
        puller.results == Vector(0, 1, 2).map(i => Success(Some(Integer.valueOf(i)))) :+
          Success(None),
        s"results ${puller.results}"
      )
    }

    scenario(s"SubscriberCursor ($kind): a cancel racing next() and the publisher") {
      val ec = new ThreadPerTask
      val source = new ThreadedSource(3)
      val cursor =
        SubscriberCursor[Integer](() => source, 2, runContext(if (async) ec else Inline))
      val producer = thread(source.produce())
      val puller = new Puller(cursor, 2)
      val consumer = thread(puller.pull())
      val canceller = thread(cursor.cancel())
      producer.join()
      consumer.join()
      canceller.join()
      ec.joinAll()
      ensure(source.violation == null, source.violation)
      ensure(source.cancelled || source.completed, "the cancel never reached the publisher")
      ensure(source.cancels <= 1, s"the publisher was cancelled ${source.cancels} times")
      ensure(puller.last.isCompleted, "the cancel left an outstanding next() incomplete")
      ensure(puller.results.forall(_.isSuccess), s"results ${puller.results}")
      val elements = puller.elements.map(_.intValue)
      ensure(elements == elements.indices.toVector, s"order $elements")
      // once the cancel returned, the cursor is at its end
      ensure(
        cursor.next().value.contains(Success(None)),
        "next() after the cancel is not None"
      )
    }
  }

  scenario("CursorPublisher -> SubscriberCursor: a run through both, cancelled mid-way") {
    val ec = new ThreadPerTask
    val upstream = new CountingCursor(3, None)
    @volatile var opened = false
    val counting = new Opener[Integer] {
      override def open(ctx: RunContext): Cursor[Integer] = {
        opened = true
        upstream
      }
    }
    val publisher = new CursorPublisher[Integer](counting, runContext(ec))
    val cursor = SubscriberCursor[Integer](() => publisher, 2, runContext(ec))
    val puller = new Puller(cursor, 2)
    val consumer = thread(puller.pull())
    val canceller = thread(cursor.cancel())
    consumer.join()
    canceller.join()
    ec.joinAll()
    ensure(upstream.violations.isEmpty, upstream.violations.mkString("; "))
    // a cancel before the run was opened: it never opens (no leaf side effects)
    ensure(
      !opened || upstream.cancels >= 1 || upstream.ended,
      "the cancel never reached the opened run"
    )
    ensure(puller.last.isCompleted, "the cancel left an outstanding next() incomplete")
    ensure(puller.results.forall(_.isSuccess), s"results ${puller.results}")
  }
}
