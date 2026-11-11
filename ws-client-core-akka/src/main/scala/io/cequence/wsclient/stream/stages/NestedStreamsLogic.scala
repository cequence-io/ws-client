package io.cequence.wsclient.stream.stages

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, Outlet, Shape}
import io.cequence.wsclient.stream.AsyncStream
import io.cequence.wsclient.stream.impl.{
  Concat,
  Defer,
  FlatMapConcat,
  FutureStream,
  Trampoline
}

import java.util.ArrayDeque
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The logic of the two sequencing stages ([[ConcatQueueStage]], [[FlatMapConcatStage]]):
 * streams that follow one another run in ONE stage, however they nest.
 *
 * The state is a chain of FRAMES. A frame is one flatMapConcat: its outer stream (a sub-inlet;
 * the stage's own inlet for the root frame), its function, and what is left of its current
 * inner stream - the next streams of that stream's concats, as a queue of thunks. Only the
 * innermost frame runs a stream; its elements go straight to the outlet. To run a stream, a
 * defer and a future stream are resolved in place, a concat is unrolled into the frame's
 * queue, and a flatMapConcat becomes a frame of its own. A frame with nothing left (its outer
 * stream ended, its queue empty) is dropped - also from the middle of the chain, when its
 * outer stream ends while a frame below it runs.
 *
 * So a recursive stream - paging, polling: `def page(i) = load(i).flatMapConcat(p => items(p)
 * ++ page(i + 1))` or `def page(i) = futureStream(load(i).map(p => items(p) ++ page(i + 1)))`
 * \- runs at a constant depth and in constant memory, as on the neutral interpreter. (Compiled
 * to the built-in operators, every level nests a stage in the previous one and each element
 * crosses them all: quadratic in the depth.)
 *
 * Nothing runs ahead of the demand: a thunk, a function or an outer element is asked for only
 * when the downstream pulls after the previous stream ended. The stage completes as soon as
 * nothing is left, demand or not. A failure of any stream fails the stage at once, as with the
 * built-in operators.
 *
 * @param rootFunction
 *   the function of the root frame; unused when the root frame's outer stream never emits (a
 *   concat queue has none)
 */
private[stages] abstract class NestedStreamsLogic[B](
  shape: Shape,
  out: Outlet[B],
  rootFunction: Any => AsyncStream[Any],
  compile: AsyncStream[Any] => Source[Any, NotUsed],
  inheritedAttributes: Attributes
) extends GraphStageLogic(shape)
    with OutHandler {

  // all state is touched only on the stage's own thread
  protected final class Frame(
    var parent: Frame,
    val f: Any => AsyncStream[Any]
  ) {
    var child: Frame = _
    // the outer stream; null for the root frame (the stage's own inlet, if it has one)
    var outer: SubSinkInlet[Any] = _
    var outerDone = false
    val queue = new ArrayDeque[() => AsyncStream[Any]]()

    def finished: Boolean = outerDone && queue.isEmpty
  }

  protected final val root: Frame = new Frame(null, rootFunction)

  // the innermost frame; null once everything ended
  private var top: Frame = root
  // the innermost frame's running stream, if any
  private var inner: SubSinkInlet[Any] = _
  // the innermost frame's next stream is a future's, still pending
  private var awaiting = false
  private var released = false

  private val onStream = getAsyncCallback[Try[AsyncStream[Any]]] { result =>
    if (!released) {
      awaiting = false
      result match {
        case Success(stream) => run(stream, "The futureStream future")
        case Failure(e)      => failStage(e)
      }
    }
  }

  /** Pulls the stage's own inlet - the root frame's outer stream - unless closed or pulled. */
  protected def pullStageInlet(): Unit

  override def onPull(): Unit =
    if (inner != null) inner.pull()
    else if (!awaiting) settle()

  // an outer stream is pulled only by the innermost frame, with no stream of its own running
  protected final def onOuterElement(
    frame: Frame,
    element: Any
  ): Unit =
    try run(frame.f(element), "The flatMapConcat function")
    catch { case NonFatal(e) => failStage(e) }

  protected final def onOuterFinish(frame: Frame): Unit = {
    frame.outerDone = true
    if (frame eq top) {
      if (inner == null && !awaiting) settle()
    } else if (frame.queue.isEmpty && frame.child != null) {
      // nothing left in a frame in the middle: its child continues below its parent
      frame.child.parent = frame.parent
      if (frame.parent != null) frame.parent.child = frame.child
    }
  }

  /**
   * With no stream running: drops the frames that have nothing left - the stage completes as
   * soon as none remains - and under demand continues with the innermost one (its next queued
   * stream, else its next outer element).
   */
  private def settle(): Unit = {
    while (top != null && top.finished) {
      top = top.parent
      if (top != null) top.child = null
    }
    if (top == null)
      completeStage()
    else if (isAvailable(out)) {
      val thunk = top.queue.pollFirst()
      if (thunk != null)
        try run(thunk(), "The next stream of a concat")
        catch { case NonFatal(e) => failStage(e) }
      else if (top.outer == null)
        pullStageInlet()
      else if (!top.outer.hasBeenPulled)
        top.outer.pull()
    }
  }

  /**
   * Runs `first` as the innermost frame's stream (called under demand). `origin` names what
   * yielded it, for the failure of a null.
   */
  private def run(
    first: AsyncStream[Any],
    origin: String
  ): Unit =
    try {
      var stream = first
      var what = origin
      var resolving = true
      while (resolving) {
        if (stream == null) throw new NullPointerException(s"$what returned null")
        stream match {
          case defer: Defer[_] =>
            stream = defer.asInstanceOf[Defer[Any]].thunk()
            what = "The defer thunk"

          case concat: Concat[_] =>
            val typed = concat.asInstanceOf[Concat[Any]]
            top.queue.addFirst(typed.next)
            stream = typed.upstream

          case future: FutureStream[_] =>
            val pending = future.asInstanceOf[FutureStream[Any]].thunk()
            if (pending == null)
              throw new NullPointerException("The futureStream thunk returned null")
            pending.value match {
              case Some(Success(yielded)) =>
                stream = yielded
                what = "The futureStream future"
              case Some(Failure(e)) =>
                resolving = false
                failStage(e)
              case None =>
                resolving = false
                awaiting = true
                pending.onComplete(onStream.invoke)(Trampoline)
            }

          case nested: FlatMapConcat[_, _] =>
            resolving = false
            val typed = nested.asInstanceOf[FlatMapConcat[Any, Any]]
            runFrame(compile(typed.upstream), typed.f)

          case _ =>
            resolving = false
            runInner(compile(stream))
        }
      }
    } catch { case NonFatal(e) => failStage(e) }

  // a frame of its own - in place of the innermost one when that has nothing left (the tail
  // call of a recursive stream)
  private def runFrame(
    outer: Source[Any, NotUsed],
    f: Any => AsyncStream[Any]
  ): Unit = {
    val parent = if (top.finished) top.parent else top
    val frame = new Frame(parent, f)
    if (parent != null) parent.child = frame
    top = frame

    val inlet = new SubSinkInlet[Any]("nested.outer")
    inlet.setHandler(new InHandler {
      override def onPush(): Unit = onOuterElement(frame, inlet.grab())
      override def onUpstreamFinish(): Unit = onOuterFinish(frame)
      override def onUpstreamFailure(e: Throwable): Unit = failStage(e)
    })
    frame.outer = inlet
    subFusingMaterializer.materialize(outer.to(inlet.sink), inheritedAttributes)
    inlet.pull()
  }

  private def runInner(source: Source[Any, NotUsed]): Unit = {
    val inlet = new SubSinkInlet[Any]("nested.inner")
    inlet.setHandler(new InHandler {
      override def onPush(): Unit = push(out, inlet.grab().asInstanceOf[B])

      override def onUpstreamFinish(): Unit = {
        inner = null
        settle()
      }

      override def onUpstreamFailure(e: Throwable): Unit = failStage(e)
    })
    inner = inlet
    subFusingMaterializer.materialize(source.to(inlet.sink), inheritedAttributes)
    inlet.pull()
  }

  override def onDownstreamFinish(cause: Throwable): Unit = {
    release(cause)
    cancelStage(cause)
  }

  // a failure (or an abrupt stop) of the stage still cancels the streams of every frame
  override def postStop(): Unit =
    release(new IllegalStateException("The stage stopped"))

  private def release(cause: Throwable): Unit =
    if (!released) {
      released = true
      if (inner != null && !inner.isClosed) inner.cancel(cause)
      inner = null
      var frame = top
      while (frame != null) {
        if (frame.outer != null && !frame.outer.isClosed) frame.outer.cancel(cause)
        frame = frame.parent
      }
      top = null
    }
}
