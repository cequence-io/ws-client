package io.cequence.wsclient.service.adapter

import akka.actor.ActorSystem
import akka.stream.Materializer
import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper
import io.cequence.wsclient.stream.{AsyncCall, CallRun, NativeCall}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

/**
 * The Materializer-based `parallelTakeFirst` (`ServiceBaseAdaptersAkka`): the Future method
 * keeps its semantics, and the call twin (`wrapCall`, since 1.3.0) is the race of the
 * Akka-free variant - the first outcome wins and the losers are cancelled.
 */
class ParallelTakeFirstAdapterSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("parallel-take-first-adapter-spec")
  implicit private val materializer: Materializer = Materializer(system)
  implicit private val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  private def failure(call: AsyncCall[_]): Throwable =
    Try(await(call.toFuture)).failed.getOrElse(
      throw new AssertionError("the call did not fail")
    )

  private class Svc(val result: () => AsyncCall[String]) extends CloseableService {
    val calls = new AtomicInteger()
    val closed = new AtomicInteger()

    def foo(): Future[String] = result().toFuture
    def fooCall(): AsyncCall[String] = AsyncCall.defer {
      calls.incrementAndGet()
      result()
    }
    override def close(): Unit = closed.incrementAndGet()
  }

  private object Adapters extends ServiceBaseAdapters[Svc] with ServiceBaseAdaptersAkka[Svc] {
    override protected def wrapAndDelegate(delegate: CloseableServiceWrapper[Svc]): Svc =
      new Svc(() => AsyncCall.successful("")) {
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

  "the Materializer-based parallelTakeFirst" should {

    "keep the Future method: the first of all services to complete" in {
      val fast = new Svc(() => AsyncCall.successful("fast"))
      val slow = new Svc(() => AsyncCall.future(Promise[String]().future))
      await(Adapters.parallelTakeFirst(slow, fast).foo()) shouldBe "fast"
    }

    "race the call twin and cancel the losers" in {
      val native = new ControlledNative
      val slow = new Svc(() => AsyncCall.fromNative(native))
      val fast = new Svc(() => AsyncCall.successful("fast"))
      val service = Adapters.parallelTakeFirst(slow, fast)
      await(service.fooCall().toFuture) shouldBe "fast"
      // a loser that had not started yet is never started; one that had is cancelled
      native.cancelled.get shouldBe native.started.get
      fast.calls.get shouldBe 1
    }

    "give the first failure too" in {
      val service = Adapters.parallelTakeFirst(
        new Svc(() => AsyncCall.future(Promise[String]().future)),
        new Svc(() => AsyncCall.failed(new IllegalStateException("first")))
      )
      failure(service.fooCall()).getMessage shouldBe "first"
    }

    "run per run of the call: nothing is called while building" in {
      val a = new Svc(() => AsyncCall.successful("a"))
      val call = Adapters.parallelTakeFirst(a).fooCall()
      a.calls.get shouldBe 0
      await(call.toFuture) shouldBe "a"
      await(call.toFuture) shouldBe "a"
      a.calls.get shouldBe 2
    }

    "fail the call without services, and close every service" in {
      failure(Adapters.parallelTakeFirst().fooCall()) shouldBe a[NoSuchElementException]
      val first = new Svc(() => AsyncCall.successful("a"))
      val second = new Svc(() => AsyncCall.successful("b"))
      Adapters.parallelTakeFirst(first, second).close()
      first.closed.get shouldBe 1
      second.closed.get shouldBe 1
    }
  }
}
