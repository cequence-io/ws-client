package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.impl.Cursors
import io.cequence.wsclient.stream.{AsyncBroadcastHub, AsyncStream}

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Success

/**
 * `AsyncBroadcastHub` against its scaladoc: `completion` "completes when the upstream
 * completed (or the hub was shut down); fails when it failed", "a consumer registering after
 * that completes (fails) at once" - so the two always agree, whichever end came first - and a
 * consumer that leaves (or registers) while the hub delivers or ends neither loses an element
 * of the others nor stays registered.
 */
object HubModelCheck extends ModelCheckSuite("AsyncBroadcastHub") {

  import ModelCheckSupport.runContext

  private implicit val ec: ExecutionContext = Inline

  scenario("shutdown() racing an upstream failure: completion and a late consumer agree") {
    val upstream = Promise[Int]()
    val hub = AsyncBroadcastHub(AsyncStream.future(upstream.future), bufferSize = 4)
    val stopper = thread(hub.shutdown())
    val failer = thread {
      upstream.tryFailure(new RuntimeException("upstream failed"))
      ()
    }
    stopper.join()
    failer.join()
    val late = hub.source().runToSeq.value
    val completion = hub.completion.value
    ensure(late.isDefined, "the late consumer did not end")
    ensure(completion.isDefined, "completion did not end")
    ensure(
      late.exists(_.isSuccess) == completion.exists(_.isSuccess),
      s"completion = $completion, but a consumer registering afterwards got $late"
    )
  }

  scenario("shutdown() racing an upstream element and completion: every consumer ends") {
    val upstream = Promise[Int]()
    val hub = AsyncBroadcastHub(AsyncStream.future(upstream.future), bufferSize = 4)
    val early = hub.source().runToSeq
    val stopper = thread(hub.shutdown())
    val emitter = thread {
      upstream.trySuccess(1)
      ()
    }
    stopper.join()
    emitter.join()
    ensure(early.value.exists(_.isSuccess), s"the registered consumer: ${early.value}")
    ensure(hub.completion.value.exists(_.isSuccess), s"completion: ${hub.completion.value}")
    val late = hub.source().runToSeq.value
    ensure(late.exists(_.isSuccess), s"the late consumer: $late")
    ensure(hub.consumerCount == 0, s"consumers left: ${hub.consumerCount}")
  }

  scenario("a consumer's cancel racing an upstream element: the others get everything") {
    val first = Promise[Int]()
    val hub =
      AsyncBroadcastHub(
        AsyncStream.future(first.future) ++ AsyncStream.single(2),
        bufferSize = 2
      )
    val staying = hub.source().runToSeq
    val leaving = Cursors.open(hub.source(), runContext(Inline))
    val pending = leaving.next()
    val canceller = thread(leaving.cancel())
    val emitter = thread {
      first.trySuccess(1)
      ()
    }
    canceller.join()
    emitter.join()
    ensure(
      staying.value.contains(Success(Seq(1, 2))),
      s"the staying consumer: ${staying.value}"
    )
    ensure(pending.isCompleted, "the cancel left the leaving consumer's next() incomplete")
    ensure(
      pending.value.exists(_.isSuccess),
      s"the leaving consumer's next(): ${pending.value}"
    )
    ensure(hub.completion.value.exists(_.isSuccess), s"completion: ${hub.completion.value}")
    ensure(hub.consumerCount == 0, s"consumers left: ${hub.consumerCount}")
  }

  scenario(
    "a consumer registering while the upstream ends: it ends, with the element or not"
  ) {
    val upstream = Promise[Int]()
    val hub = AsyncBroadcastHub(AsyncStream.future(upstream.future), bufferSize = 2)
    val early = hub.source().runToSeq
    @volatile var late: Option[scala.concurrent.Future[Seq[Int]]] = None
    val registrar = thread { late = Some(hub.source().runToSeq) }
    val emitter = thread {
      upstream.trySuccess(1)
      ()
    }
    registrar.join()
    emitter.join()
    ensure(early.value.contains(Success(Seq(1))), s"the registered consumer: ${early.value}")
    val lateResult = late.flatMap(_.value)
    ensure(
      lateResult.contains(Success(Seq(1))) || lateResult.contains(Success(Seq.empty)),
      s"the consumer registering meanwhile: $lateResult"
    )
    ensure(hub.completion.value.exists(_.isSuccess), s"completion: ${hub.completion.value}")
    ensure(hub.consumerCount == 0, s"consumers left: ${hub.consumerCount}")
  }

  scenario("shutdown() racing a consumer's registration: the consumer ends empty") {
    val upstream = Promise[Int]()
    val hub = AsyncBroadcastHub(AsyncStream.future(upstream.future), bufferSize = 2)
    @volatile var run: Option[scala.concurrent.Future[Seq[Int]]] = None
    val registrar = thread { run = Some(hub.source().runToSeq) }
    val stopper = thread(hub.shutdown())
    registrar.join()
    stopper.join()
    val result = run.flatMap(_.value)
    ensure(result.contains(Success(Seq.empty)), s"the consumer: $result")
    ensure(hub.completion.value.exists(_.isSuccess), s"completion: ${hub.completion.value}")
    ensure(hub.consumerCount == 0, s"consumers left: ${hub.consumerCount}")
  }
}
