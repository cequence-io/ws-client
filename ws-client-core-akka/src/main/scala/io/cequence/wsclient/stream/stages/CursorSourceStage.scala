package io.cequence.wsclient.stream.stages

import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import io.cequence.wsclient.stream.impl.{Cursor, Trampoline}

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * A neutral [[Cursor]] as a native source: one `next()` per pull, the cursor cancelled when
 * the downstream cancels (or the stage stops abruptly). `open` runs once per materialization,
 * at the first pull.
 *
 * Used where a leaf has ONE implementation for every backend (the blocking read cursor: reads
 * on the blocking pool, a cancel never waits for a stalled read, `close()` exactly once and
 * never a stream failure) - no publisher in between, so not a bridge.
 */
private[wsclient] final class CursorSourceStage[A](
  open: () => Cursor[A],
  name: String
) extends GraphStage[SourceShape[A]] {

  private val out = Outlet[A](s"$name.out")

  override val shape: SourceShape[A] = SourceShape(out)

  override protected def initialAttributes: Attributes = Attributes.name(name)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      // both touched only on the stage's own thread
      private var cursor: Cursor[A] = _
      private var ended = false

      private val onResult = getAsyncCallback[Try[Option[A]]](handle)

      private def handle(result: Try[Option[A]]): Unit =
        // a result that arrives after a cancel is dropped (the cursor answers a cancel with None)
        if (!ended)
          result match {
            case Success(Some(element)) => push(out, element)
            case Success(None) =>
              ended = true
              completeStage()
            case Failure(e) =>
              ended = true
              failStage(e)
          }

      override def onPull(): Unit =
        try {
          if (cursor == null) cursor = open()
          val future = cursor.next()
          future.value match {
            case Some(result) => handle(result)
            case None         => future.onComplete(onResult.invoke)(Trampoline)
          }
        } catch {
          case NonFatal(e) =>
            ended = true
            failStage(e)
        }

      override def onDownstreamFinish(cause: Throwable): Unit = {
        cancelCursor()
        cancelStage(cause)
      }

      // an abrupt stop (e.g. the materializer shut down) still releases the cursor
      override def postStop(): Unit = cancelCursor()

      private def cancelCursor(): Unit =
        if (!ended) {
          ended = true
          if (cursor != null) cursor.cancel()
        }

      setHandler(out, this)
    }
}
