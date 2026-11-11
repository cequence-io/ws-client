package io.cequence.wsclient.stream.stages

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic}
import akka.stream.{Attributes, Outlet, SourceShape}
import io.cequence.wsclient.stream.AsyncStream

/**
 * The next streams of a concat as ONE native source (see [[NestedStreamsLogic]]): a
 * per-materialization queue of the remaining `next` thunks, the current stream run in a
 * sub-inlet. A next stream that is itself a concat, a defer or a future stream is resolved
 * into the queue, and a finished stream is dropped - so a recursive stream (`def loop =
 * single(1) ++ loop`) runs in constant memory, as on the neutral interpreter. A thunk runs
 * only when the downstream pulls after the previous stream ended (no read-ahead: a next stream
 * \- a request - is never started early).
 */
private[wsclient] final class ConcatQueueStage[A](
  first: () => AsyncStream[A],
  compile: AsyncStream[Any] => Source[Any, NotUsed]
) extends GraphStage[SourceShape[A]] {

  private val out = Outlet[A]("concat.out")

  override val shape: SourceShape[A] = SourceShape(out)

  override protected def initialAttributes: Attributes = Attributes.name("concat")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new NestedStreamsLogic[A](
      shape,
      out,
      ConcatQueueStage.NoOuterElement,
      compile,
      inheritedAttributes
    ) {
      // the root frame has no outer stream: only its queue, which starts with the first thunk
      root.outerDone = true
      root.queue.addLast(first)

      override protected def pullStageInlet(): Unit = ()

      setHandler(out, this)
    }
}

private object ConcatQueueStage {
  private val NoOuterElement: Any => AsyncStream[Any] =
    _ => throw new IllegalStateException("A concat queue has no outer stream")
}
