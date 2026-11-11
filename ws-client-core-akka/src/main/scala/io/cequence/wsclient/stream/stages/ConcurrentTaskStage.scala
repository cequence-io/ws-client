package io.cequence.wsclient.stream.stages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import io.cequence.wsclient.stream.impl.Trampoline

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The `concurrently` node as a native stage: the task starts when the stage starts (once per
 * materialization, before any demand). Its failure before the stream ended cancels the
 * upstream and fails the stream; with `awaitCompletion` the completion waits for the task and
 * fails if it fails. A downstream cancel ignores the task.
 */
private[wsclient] final class ConcurrentTaskStage[A](
  task: () => Future[Any],
  awaitCompletion: Boolean
) extends GraphStage[FlowShape[A, A]] {

  private val in = Inlet[A]("concurrently.in")
  private val out = Outlet[A]("concurrently.out")

  override val shape: FlowShape[A, A] = FlowShape(in, out)

  override protected def initialAttributes: Attributes = Attributes.name("concurrently")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var upstreamFinished = false
      private var taskResult: Option[Try[Any]] = None

      override def preStart(): Unit = {
        val taskFuture =
          try {
            val result = task()
            if (result == null)
              Future.failed(new NullPointerException("The concurrent task returned null"))
            else
              result
          } catch { case NonFatal(e) => Future.failed(e) }

        taskFuture.value match {
          case Some(result) => onTask(result)
          case None =>
            val callback = getAsyncCallback[Try[Any]](onTask)
            taskFuture.onComplete(callback.invoke)(Trampoline)
        }
      }

      private def onTask(result: Try[Any]): Unit = {
        taskResult = Some(result)
        result match {
          case Failure(e) => failStage(e) // cancels the upstream
          case Success(_) =>
            if (upstreamFinished && awaitCompletion) completeStage()
        }
      }

      override def onPush(): Unit = push(out, grab(in))

      // the stage outlives its upstream while the awaited task is pending: a pull then must not reach
      // the closed inlet (Akka fails the stage with "Cannot pull closed port")
      override def onPull(): Unit = if (!isClosed(in)) pull(in)

      override def onUpstreamFinish(): Unit = {
        upstreamFinished = true
        if (!awaitCompletion)
          completeStage()
        else
          taskResult match {
            case Some(Success(_)) => completeStage()
            case Some(Failure(e)) => failStage(e)
            case None             => () // the task's completion completes the stage
          }
      }

      setHandlers(in, out, this)
    }
}
