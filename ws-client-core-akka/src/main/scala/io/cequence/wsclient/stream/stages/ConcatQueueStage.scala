package io.cequence.wsclient.stream.stages

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import io.cequence.wsclient.stream.AsyncStream
import io.cequence.wsclient.stream.impl.{Concat, Defer}

import java.util.ArrayDeque
import scala.util.control.NonFatal

/**
 * The next streams of a concat as ONE native source: a per-materialization queue of the
 * remaining `next` thunks, the current stream run in a sub-inlet. A next stream that is itself
 * a concat (or a defer) is unrolled into the queue, and a finished stream is dropped - so a
 * recursive stream (`def loop = single(1) ++ loop`) runs in constant memory, as on the neutral
 * interpreter. A thunk runs only when the downstream pulls after the previous stream ended (no
 * read-ahead: a next stream - a request - is never started early).
 */
private[wsclient] final class ConcatQueueStage[A](
  first: () => AsyncStream[A],
  compile: AsyncStream[A] => Source[A, NotUsed]
) extends GraphStage[SourceShape[A]] {

  private val out = Outlet[A]("concat.out")

  override val shape: SourceShape[A] = SourceShape(out)

  override protected def initialAttributes: Attributes = Attributes.name("concat")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private val queue = new ArrayDeque[() => AsyncStream[A]]()
      queue.addLast(first)

      private var current: SubSinkInlet[A] = _

      override def onPull(): Unit =
        if (current != null)
          current.pull()
        else
          openNext()

      // the next stream (defers resolved, concats unrolled into the queue), or the end
      private def openNext(): Unit = {
        val thunk = queue.pollFirst()
        if (thunk == null)
          completeStage()
        else {
          val source =
            try {
              var stream = thunk()
              var resolving = true
              while (resolving)
                stream match {
                  case defer: Defer[_] =>
                    stream = defer.asInstanceOf[Defer[A]].thunk()
                  case concat: Concat[_] =>
                    val typed = concat.asInstanceOf[Concat[A]]
                    queue.addFirst(typed.next)
                    stream = typed.upstream
                  case _ =>
                    resolving = false
                }
              compile(stream)
            } catch { case NonFatal(e) => Source.failed[A](e) }

          val inlet = new SubSinkInlet[A]("concat.in")
          inlet.setHandler(new InHandler {
            override def onPush(): Unit = push(out, inlet.grab())

            // the last stream ended: complete now, demand or not; else the next stream opens on
            // demand
            override def onUpstreamFinish(): Unit = {
              current = null
              if (queue.isEmpty) completeStage()
              else if (isAvailable(out)) openNext()
            }

            override def onUpstreamFailure(e: Throwable): Unit = failStage(e)
          })
          current = inlet
          subFusingMaterializer.materialize(source.to(inlet.sink), inheritedAttributes)
          inlet.pull()
        }
      }

      override def onDownstreamFinish(cause: Throwable): Unit = {
        val running = current
        current = null
        if (running != null) running.cancel(cause)
        cancelStage(cause)
      }

      setHandler(out, this)
    }
}
