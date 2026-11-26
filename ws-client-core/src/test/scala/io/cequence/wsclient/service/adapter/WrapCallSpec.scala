package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper
import io.cequence.wsclient.stream.{AsyncCall, CallRun, NativeCall}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CancellationException, ConcurrentLinkedQueue}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

/** `ServiceWrapper.wrapCall` and its overrides in the adapters. */
class WrapCallSpec extends AnyWordSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  private def failure(call: AsyncCall[_]): Throwable =
    Try(await(call.toFuture)).failed.getOrElse(
      throw new AssertionError("the call did not fail")
    )

  /** A service with a Future method and its call twin; the calls are cold. */
  class Svc(
    val name: String,
    val result: () => AsyncCall[String] = () => AsyncCall.successful("")
  ) extends CloseableService {
    val calls = new AtomicInteger()
    val closed = new AtomicInteger()

    def foo(): Future[String] = result().toFuture
    def fooCall(): AsyncCall[String] = AsyncCall.defer {
      calls.incrementAndGet()
      result()
    }
    override def close(): Unit = closed.incrementAndGet()
  }

  private object Adapters extends ServiceBaseAdapters[Svc] with ParallelServiceAdapters[Svc] {
    override protected def wrapAndDelegate(delegate: CloseableServiceWrapper[Svc]): Svc =
      new Svc("wrapped") {
        override def foo(): Future[String] = delegate.wrap(_.foo())
        override def fooCall(): AsyncCall[String] = delegate.wrapCall(_.fooCall())
        override def close(): Unit = delegate.close()
      }
  }

  private final class ControlledNative extends NativeCall[String] {
    val started = new AtomicInteger()
    val cancelled = new AtomicInteger()
    override def start(): CallRun[String] = {
      started.incrementAndGet()
      val promise = Promise[String]()
      new CallRun[String] {
        override def result: Future[String] = promise.future
        override def cancel(): Unit = {
          cancelled.incrementAndGet()
          promise.tryFailure(new CancellationException("cancelled"))
          ()
        }
      }
    }
    override def backend: String = "test"
  }

  "the default wrapCall" should {

    "run a wrapper that only knows `wrap` - as a Future leaf, per run" in {
      val service = new Svc("a", () => AsyncCall.successful("x"))
      val oldWrapper = new ServiceWrapper[Svc] {
        override protected[adapter] def wrap[T](fun: Svc => Future[T]): Future[T] =
          fun(service)
      }
      val call = oldWrapper.wrapCall(_.fooCall())
      service.calls.get shouldBe 0
      await(call.toFuture) shouldBe "x"
      await(call.toFuture) shouldBe "x"
      service.calls.get shouldBe 2
    }

    "be forwarded by a delegating wrapper" in {
      val service = new Svc("a", () => AsyncCall.successful("x"))
      val inner = SimpleServiceWrapper(service)
      val outer = new DelegatedCloseableServiceWrapper[Svc, CloseableServiceWrapper[Svc]] {
        override protected def delegate: CloseableServiceWrapper[Svc] = inner
      }
      await(outer.wrapCall(_.fooCall()).toFuture) shouldBe "x"
      service.calls.get shouldBe 1
    }
  }

  "SimpleServiceWrapper" should {
    "apply the function per run" in {
      val service = new Svc("a", () => AsyncCall.successful("x"))
      val call = SimpleServiceWrapper(service).wrapCall(_.fooCall())
      service.calls.get shouldBe 0
      await(call.toFuture) shouldBe "x"
      service.calls.get shouldBe 1
    }
  }

  "roundRobin and randomOrder" should {

    "pick the service per run of a call" in {
      val a = new Svc("a", () => AsyncCall.successful("a"))
      val b = new Svc("b", () => AsyncCall.successful("b"))
      val call = Adapters.roundRobin(a, b).fooCall()
      await(call.toFuture) shouldBe "a"
      await(call.toFuture) shouldBe "b"
      await(call.toFuture) shouldBe "a"
      a.calls.get shouldBe 2
      b.calls.get shouldBe 1
    }

    "share the counter between the Future and the call methods" in {
      val a = new Svc("a", () => AsyncCall.successful("a"))
      val b = new Svc("b", () => AsyncCall.successful("b"))
      val service = Adapters.roundRobin(a, b)
      await(service.foo()) shouldBe "a"
      await(service.fooCall().toFuture) shouldBe "b"
    }

    "use every service with randomOrder" in {
      val a = new Svc("a", () => AsyncCall.successful("a"))
      val b = new Svc("b", () => AsyncCall.successful("b"))
      val call = Adapters.randomOrder(a, b).fooCall()
      (1 to 100).foreach(_ => await(call.toFuture))
      a.calls.get + b.calls.get shouldBe 100
      a.calls.get should be > 0
      b.calls.get should be > 0
    }
  }

  "log" should {

    "write the function name - without the twin's suffix - per run" in {
      val lines = new ConcurrentLinkedQueue[String]()
      val service = Adapters.log(
        new Svc("a", () => AsyncCall.successful("x")),
        "svc",
        line => { lines.add(line); () }
      )
      val call = service.fooCall()
      lines.size shouldBe 0
      await(call.toFuture)
      await(call.toFuture)
      await(service.foo())
      lines.asScala.toList shouldBe List(
        "svc - calling 'foo'",
        "svc - calling 'foo'",
        "svc - calling 'foo'"
      )
    }
  }

  "preAction" should {

    "run the action before every run of the call" in {
      val order = new ConcurrentLinkedQueue[String]()
      val service = Adapters.preAction(
        new Svc(
          "a",
          () =>
            AsyncCall.defer {
              order.add("call")
              AsyncCall.successful("x")
            }
        ),
        () => Future { order.add("action"); () }
      )
      val call = service.fooCall()
      order.size shouldBe 0
      await(call.toFuture)
      await(call.toFuture)
      order.asScala.toList shouldBe List("action", "call", "action", "call")
    }

    "fail the call when the action fails or throws" in {
      val failing = Adapters.preAction(
        new Svc("a"),
        () => Future.failed(new IllegalStateException("action"))
      )
      failure(failing.fooCall()).getMessage shouldBe "action"

      val throwing = Adapters.preAction(
        new Svc("a"),
        () => throw new IllegalStateException("thrown")
      )
      failure(throwing.fooCall()).getMessage shouldBe "thrown"
    }
  }

  "repackExceptions" should {

    val repack: PartialFunction[Throwable, Throwable] = { case e: IllegalStateException =>
      new IllegalArgumentException("repacked", e)
    }

    "repack a failure of the call" in {
      val service = Adapters.repackExceptions(repack)(
        new Svc("a", () => AsyncCall.failed(new IllegalStateException("inner")))
      )
      val e = failure(service.fooCall())
      e shouldBe an[IllegalArgumentException]
      e.getMessage shouldBe "repacked"
    }

    "fail the call with a synchronous throw - repacked too" in {
      val service = Adapters.repackExceptions(repack)(
        new Svc("a", () => throw new IllegalStateException("sync"))
      )
      failure(service.fooCall()) shouldBe an[IllegalArgumentException]
    }

    "leave other failures and values alone" in {
      val other = Adapters.repackExceptions(repack)(
        new Svc("a", () => AsyncCall.failed(new UnsupportedOperationException("other")))
      )
      failure(other.fooCall()) shouldBe an[UnsupportedOperationException]

      val fine =
        Adapters.repackExceptions(repack)(new Svc("a", () => AsyncCall.successful("x")))
      await(fine.fooCall().toFuture) shouldBe "x"
    }

    "keep the failure as suppressed when the repacking itself throws" in {
      val service = Adapters.repackExceptions { case _: IllegalStateException =>
        throw new RuntimeException("repack failed")
      }(new Svc("a", () => AsyncCall.failed(new IllegalStateException("inner"))))
      val e = failure(service.fooCall())
      e.getMessage shouldBe "repack failed"
      e.getSuppressed.map(_.getMessage).toList shouldBe List("inner")
    }
  }

  "parallelTakeFirst" should {

    "complete with the first outcome and cancel the others" in {
      val native = new ControlledNative
      val slow = new Svc("slow", () => AsyncCall.fromNative(native))
      val fast = new Svc("fast", () => AsyncCall.successful("fast"))
      val service = Adapters.parallelTakeFirst(slow, fast)
      await(service.fooCall().toFuture) shouldBe "fast"
      // a loser that had not started yet is never started; one that had is cancelled
      native.cancelled.get shouldBe native.started.get
      fast.calls.get shouldBe 1
    }

    "complete with the first failure too" in {
      val service = Adapters.parallelTakeFirst(
        new Svc("slow", () => AsyncCall.future(Promise[String]().future)),
        new Svc("failing", () => AsyncCall.failed(new IllegalStateException("first")))
      )
      failure(service.fooCall()).getMessage shouldBe "first"
    }

    "fail with a NoSuchElementException without services" in {
      failure(Adapters.parallelTakeFirst().fooCall()) shouldBe a[NoSuchElementException]
    }

    "fail when a service throws synchronously" in {
      val throwing = new Svc("a") {
        override def fooCall(): AsyncCall[String] = throw new IllegalStateException("sync")
      }
      failure(
        Adapters
          .parallelTakeFirst(
            new Svc("b", () => AsyncCall.future(Promise[String]().future)),
            throwing
          )
          .fooCall()
      ) shouldBe an[IllegalStateException]
    }
  }
}
