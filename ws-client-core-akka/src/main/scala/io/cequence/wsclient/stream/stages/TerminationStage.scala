package io.cequence.wsclient.stream.stages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import io.cequence.wsclient.stream.Termination
import io.cequence.wsclient.stream.impl.Trampoline
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The `guaranteeCase` node as a native stage: the hook is called exactly once per
 * materialization. On `Completed` / `Failed` the completion / failure goes downstream only
 * after the hook's future completed (a failing hook fails a completed stream, and is attached
 * as suppressed to a failure); on `Cancelled` the upstream is cancelled first and the hook is
 * not awaited. (`watchTermination` cannot delay the completion and reports a cancel as
 * `Done`.)
 */
private[wsclient] final class TerminationStage[A](hook: Termination => Future[Unit])
    extends GraphStage[FlowShape[A, A]] {

  private val in = Inlet[A]("onTerminate.in")
  private val out = Outlet[A]("onTerminate.out")

  override val shape: FlowShape[A, A] = FlowShape(in, out)

  override protected def initialAttributes: Attributes = Attributes.name("onTerminate")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var fired = false

      private def fire(termination: Termination): Future[Unit] = {
        fired = true
        try {
          val result = hook(termination)
          if (result == null)
            Future.failed(new NullPointerException("The termination hook returned null"))
          else
            result
        } catch { case NonFatal(e) => Future.failed(e) }
      }

      // continues on the stage's own thread once the hook's future completed
      private def afterHook(
        hookFuture: Future[Unit]
      )(
        continue: Try[Unit] => Unit
      ): Unit =
        hookFuture.value match {
          case Some(result) => continue(result)
          case None =>
            val callback = getAsyncCallback[Try[Unit]](continue)
            hookFuture.onComplete(callback.invoke)(Trampoline)
        }

      override def onPush(): Unit = push(out, grab(in))

      // the stage outlives its upstream while the hook is pending: a pull then must not reach
      // the closed inlet (Akka fails the stage with "Cannot pull closed port")
      override def onPull(): Unit = if (!isClosed(in)) pull(in)

      override def onUpstreamFinish(): Unit =
        afterHook(fire(Termination.Completed)) {
          case Success(_) => completeStage()
          case Failure(e) => failStage(e)
        }

      override def onUpstreamFailure(e: Throwable): Unit =
        afterHook(fire(Termination.Failed(e))) {
          case Success(_) => failStage(e)
          case Failure(hookError) =>
            if (hookError ne e) e.addSuppressed(hookError)
            failStage(e)
        }

      override def onDownstreamFinish(cause: Throwable): Unit = {
        val cancelled = !fired
        cancelStage(cause)
        if (cancelled) logFailure(fire(Termination.Cancelled))
      }

      // an abrupt stop (e.g. the materializer shut down) still reports the run once
      override def postStop(): Unit =
        if (!fired) logFailure(fire(Termination.Cancelled))

      setHandlers(in, out, this)
    }

  private def logFailure(hookFuture: Future[Unit]): Unit =
    hookFuture.failed.foreach { e =>
      TerminationStage.logger.warn("A termination hook failed on cancel", e)
    }(Trampoline)
}

private object TerminationStage {
  private val logger = LoggerFactory.getLogger(classOf[TerminationStage[_]])
}
