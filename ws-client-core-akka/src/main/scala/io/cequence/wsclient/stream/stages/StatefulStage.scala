package io.cequence.wsclient.stream.stages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import io.cequence.wsclient.stream.AsyncStream

import scala.collection.immutable

/**
 * The `stateful` node as a native stage: `init` per materialization, `step` when an element
 * arrives (its elements emitted in order), `onComplete` on NORMAL completion only - its
 * elements go out before the completion, a throw fails the stream. A step with `stop` cancels
 * the upstream, emits its elements and completes. (Akka's `statefulMap` runs its completion on
 * failure and cancellation too, and cannot stop early.)
 */
private[wsclient] final class StatefulStage[A, S, B](
  init: () => S,
  step: (S, A) => AsyncStream.Step[S, B],
  onComplete: S => immutable.Iterable[B],
  name: String
) extends GraphStage[FlowShape[A, B]] {

  private val in = Inlet[A](s"$name.in")
  private val out = Outlet[B](s"$name.out")

  override val shape: FlowShape[A, B] = FlowShape(in, out)

  override protected def initialAttributes: Attributes = Attributes.name(name)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var state: S = _

      override def preStart(): Unit = state = init()

      override def onPush(): Unit = {
        val result = step(state, grab(in))
        state = result.state

        if (result.stop) {
          cancel(in)
          emitMultiple(out, result.emit.iterator, () => completeStage())
        } else if (result.emit.isEmpty)
          pull(in)
        else
          emitMultiple(out, result.emit.iterator)
      }

      override def onUpstreamFinish(): Unit =
        emitMultiple(out, onComplete(state).iterator, () => completeStage())

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }

  override def toString: String = name
}
