package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl.Trampoline
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise

class TrampolineSpec extends AnyWordSpec with Matchers {

  "the Trampoline" should {

    "run a task on the calling thread, queuing nested tasks after it" in {
      val order = ListBuffer.empty[String]
      val caller = Thread.currentThread()
      Trampoline.execute(() => {
        Thread.currentThread() shouldBe caller
        order += "outer-start"
        Trampoline.execute(() => order += "nested")
        order += "outer-end"
      })
      order.toList shouldBe List("outer-start", "outer-end", "nested")
    }

    "not grow the stack over a long chain of callbacks" in {
      val start = Promise[Int]()
      val end = (1 to 100000).foldLeft(start.future)(
        (
          f,
          _
        ) => f.map(_ + 1)(Trampoline)
      )
      start.success(0)
      end.value.flatMap(_.toOption) shouldBe Some(100000)
    }

    "keep running queued tasks after one throws" in {
      val order = ListBuffer.empty[String]
      Trampoline.execute(() => {
        Trampoline.execute(() => throw new RuntimeException("reported, not propagated"))
        Trampoline.execute(() => order += "after")
      })
      order.toList shouldBe List("after")
    }
  }
}
