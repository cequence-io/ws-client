package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

class FirstCompletedAdapterSpec extends AnyWordSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  private class Svc(val result: Future[String]) extends CloseableService {
    val closed = new AtomicInteger()
    def call(): Future[String] = result
    override def close(): Unit = closed.incrementAndGet()
  }

  private object Adapters extends ServiceBaseAdapters[Svc] with ParallelServiceAdapters[Svc] {
    override protected def wrapAndDelegate(delegate: CloseableServiceWrapper[Svc]): Svc =
      new Svc(Future.successful("")) {
        override def call(): Future[String] = delegate.wrap(_.call())
        override def close(): Unit = delegate.close()
      }
  }

  "ParallelServiceAdapters.parallelTakeFirst" should {

    "complete with the first call to complete - a response or a failure" in {
      val slow = Promise[String]()
      val a = new Svc(slow.future)
      val b = new Svc(Future.successful("fast"))
      val combined = Adapters.parallelTakeFirst(a, b)
      await(combined.call()) shouldBe "fast"

      val failing =
        Adapters.parallelTakeFirst(
          new Svc(slow.future),
          new Svc(Future.failed(new RuntimeException("first")))
        )
      Try(await(failing.call())).failed.get.getMessage shouldBe "first"

      combined.close()
      a.closed.get() shouldBe 1
      b.closed.get() shouldBe 1
    }

    "fail at once when a call throws synchronously" in {
      val throwing = new Svc(Future.successful("never used")) {
        override def call(): Future[String] = throw new IllegalStateException("sync")
      }
      Try(
        await(Adapters.parallelTakeFirst(new Svc(Promise[String]().future), throwing).call())
      ).failed.get shouldBe an[
        IllegalStateException
      ]
    }
  }
}
