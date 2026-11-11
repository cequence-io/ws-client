package io.cequence.wsclient.stream.stages

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import io.cequence.wsclient.stream.AsyncStream

/**
 * The `flatMapConcat` node as ONE native stage, whatever its inner streams nest (see
 * [[NestedStreamsLogic]]): a recursive stream - paging, polling: `def page(i) =
 * load(i).flatMapConcat(p => items(p) ++ page(i + 1))` - runs at a constant depth and in
 * constant memory. The built-in flatMapConcat nests one stage per level.
 *
 * As with the built-in stage: one inner stream at a time; an outer element is pulled only when
 * the downstream pulls after the previous inner stream ended; a failure of the outer or an
 * inner stream fails the stage at once.
 */
private[wsclient] final class FlatMapConcatStage[A, B](
  f: A => AsyncStream[B],
  compile: AsyncStream[Any] => Source[Any, NotUsed]
) extends GraphStage[FlowShape[A, B]] {

  private val in = Inlet[A]("flatMapConcat.in")
  private val out = Outlet[B]("flatMapConcat.out")

  override val shape: FlowShape[A, B] = FlowShape(in, out)

  override protected def initialAttributes: Attributes = Attributes.name("flatMapConcat")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new NestedStreamsLogic[B](
      shape,
      out,
      f.asInstanceOf[Any => AsyncStream[Any]],
      compile,
      inheritedAttributes
    ) with InHandler {

      override protected def pullStageInlet(): Unit =
        if (!isClosed(in) && !hasBeenPulled(in)) pull(in)

      override def onPush(): Unit = onOuterElement(root, grab(in))

      // the stage outlives its inlet: the frames may still have streams to run
      override def onUpstreamFinish(): Unit = onOuterFinish(root)

      setHandlers(in, out, this)
    }
}
