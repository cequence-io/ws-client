package io.cequence.wsclient.concurrent

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class ActorSchedulersSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("actor-schedulers-spec")
  implicit private val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // whichever AsyncScheduler implicit resolution picks here
  private def resolved(
    implicit scheduler: AsyncScheduler
  ): AsyncScheduler = scheduler

  "ActorSchedulers" should {

    "serve an implicit actor scheduler in scope where an AsyncScheduler is expected" in {
      implicit val actorScheduler: akka.actor.Scheduler = system.scheduler

      {
        // without the import, the default from AsyncScheduler's companion
        resolved shouldBe theSameInstanceAs(AsyncScheduler.default)
      }

      {
        import ActorSchedulers._
        resolved should not be theSameInstanceAs(AsyncScheduler.default)
        Await.result(Futures.after(10.millis)(Future.successful(1)), 10.seconds) shouldBe 1
      }
    }

    "schedule and cancel on the actor scheduler and on a materializer" in {
      Seq(
        ActorSchedulers.fromActorScheduler(system.scheduler),
        ActorSchedulers.fromMaterializer(Materializer(system))
      ).foreach { scheduler =>
        val ran = Promise[Unit]()
        scheduler.scheduleOnce(10.millis)(() => ran.success(()))
        Await.result(ran.future, 10.seconds) shouldBe (())

        val runs = new AtomicInteger()
        val cancellable = scheduler.scheduleOnce(300.millis)(() => runs.incrementAndGet())
        cancellable.cancel() shouldBe true
        cancellable.isCancelled shouldBe true
        Thread.sleep(400)
        runs.get() shouldBe 0
      }
    }
  }
}
