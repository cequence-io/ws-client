package io.cequence.wsclient.stream

import akka.NotUsed
import akka.stream.scaladsl.{Flow, JavaFlowSupport, Keep, Sink, Source}
import akka.stream.{
  ActorAttributes,
  Attributes,
  StreamSubscriptionTimeoutTerminationMode,
  SystemMaterializer
}
import akka.util.ByteString
import io.cequence.wsclient.stream.impl._
import io.cequence.wsclient.stream.stages.{
  ConcatQueueStage,
  ConcurrentTaskStage,
  StatefulStage,
  TerminationStage
}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Flow => JFlow}
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Compiles an [[AsyncStream]] into an Akka `Source` - natively: an Akka leaf (`SourceLeaf`) is
 * returned as is, neutral leaves become Akka sources (`Source(...)`, `lazySource`,
 * `unfoldResource`, ...), and every operator a native stage. A bridge (through
 * `java.util.concurrent.Flow.Publisher`) is built only for a foreign leaf (one) or a foreign
 * native pipe (two). Compiling runs no user code: every thunk runs when the source is
 * materialized, once per materialization.
 */
private[wsclient] object SourceCompiler {

  /** `stream` compiled natively, its bridges counted in the current scope. */
  def toSource[A](stream: AsyncStream[A]): Source[A, NotUsed] =
    new SourceCompiler(BridgeCounter.current()).compile(stream)

  // zero-copy conversions between Bytes and ByteString
  val ToByteString: Bytes => ByteString =
    bytes => ByteString.fromArrayUnsafe(bytes.unsafeArray, bytes.unsafeOffset, bytes.length)

  val FromByteString: ByteString => Bytes = byteString =>
    Bytes.unsafeWrap(byteString.toArrayUnsafe())

  /**
   * `stream` as a `ByteString` source; an Akka `ByteString` leaf comes back as that source.
   */
  def toByteStringSource(stream: AsyncStream[Bytes]): Source[ByteString, NotUsed] =
    stream match {
      case Mapped(Native(leaf: SourceLeaf[_]), f) if f eq FromByteString =>
        leaf.source.asInstanceOf[Source[ByteString, NotUsed]]
      case other =>
        toSource(other).map(ToByteString)
    }

  /**
   * `pipe` as an Akka `Flow`: a pipe that is exactly one Akka flow comes back as that flow;
   * any other pipe is applied to the flow's own input stream, natively. That input is the
   * flow's upstream: it can be consumed only once (a pipe that reads its input twice fails),
   * and it may be opened late - or never (it is then cancelled when the pipe's output ends).
   */
  def toFlow[A, B](pipe: AsyncPipe[A, B]): Flow[A, B, NotUsed] =
    pipe(Nodes.Hole) match {
      case ViaNative(hole, flowPipe: FlowPipe[_, _]) if hole eq Nodes.Hole =>
        flowPipe.flow.asInstanceOf[Flow[A, B, NotUsed]]
      case _ =>
        val compiler = new SourceCompiler(BridgeCounter.current())
        Flow[A]
          .prefixAndTail(0)
          // no subscription timeout on the input: a pipe may open it only after its first
          // outputs went downstream (a leading concat, e.g. scan, under a slow consumer)
          .addAttributes(
            ActorAttributes.streamSubscriptionTimeout(
              1.hour,
              StreamSubscriptionTimeoutTerminationMode.noop
            )
          )
          .flatMapConcat { case (_, tail) =>
            Source.fromMaterializer {
              (
                materializer,
                _
              ) =>
                val opened = new AtomicBoolean(false)
                // an input the pipe never opened would hold the upstream forever
                def cancelUnopened(): Unit =
                  if (opened.compareAndSet(false, true))
                    tail.runWith(Sink.cancelled)(
                      SystemMaterializer(materializer.system).materializer
                    )
                val input = tail.mapMaterializedValue { _ =>
                  opened.set(true)
                  NotUsed
                }
                try
                  compiler
                    .compile(pipe(Native(new SourceLeaf[A](input, SourceLeaf.NoFallback))))
                    .watchTermination() {
                      (
                        _,
                        done
                      ) =>
                        done.onComplete(_ => cancelUnopened())(Trampoline)
                        NotUsed
                    }
                catch {
                  // the pipe's function failed on this run: its output never terminates
                  case NonFatal(e) =>
                    cancelUnopened()
                    throw e
                }
            }
          }
          .mapMaterializedValue(_ => NotUsed)
    }

  // Akka's input buffer (the demand of a publisher bridge) must be a power of two, and Akka
  // preallocates it per materialization: capped at MaxInputBuffer
  val MaxInputBuffer: Int = 4096

  private[stream] def inputBufferSize(bufferSize: Int): Int =
    if (bufferSize <= 1)
      1
    else if (bufferSize >= MaxInputBuffer)
      MaxInputBuffer
    else
      Integer.highestOneBit(bufferSize - 1) << 1
}

/**
 * The compiler instance: lazy children (concat tails, flatMapConcat inners, recoveries) are
 * compiled at materialization by the same instance, so its bridge counter covers them.
 */
private[wsclient] final class SourceCompiler(bridges: BridgeCounter) extends StreamCompiler {

  type R[+A] = Source[A, NotUsed]

  def compile[A](stream: AsyncStream[A]): Source[A, NotUsed] =
    StreamCompiler.compile(this, stream)

  private def lazily[A](create: () => Source[A, NotUsed]): Source[A, NotUsed] =
    Source.lazySource(create).mapMaterializedValue(_ => NotUsed)

  // ------------------------------------------------------------------------------ leaves

  def emits[A](as: immutable.Iterable[A]): Source[A, NotUsed] = Source(as)

  def failed[A](error: Throwable): Source[A, NotUsed] = Source.failed(error)

  def defer[A](thunk: () => AsyncStream[A]): Source[A, NotUsed] =
    lazily(() => compile(thunk()))

  def futureStream[A](thunk: () => Future[AsyncStream[A]]): Source[A, NotUsed] =
    Source.lazyFutureSource { () =>
      val future =
        try thunk()
        catch { case NonFatal(e) => Future.failed(e) }
      future.map(stream => compile(stream))(Trampoline)
    }.mapMaterializedValue(_ => NotUsed)

  // a publisher is foreign to Akka: one bridge. Its demand is `bufferSize` up front and half
  // as much after each half consumed (Akka's input buffer, rounded up to a power of two - the
  // lazy source hands its attributes to the inner graph)
  def fromPublisher[A](
    factory: () => JFlow.Publisher[_ <: A],
    bufferSize: Int
  ): Source[A, NotUsed] = {
    bridges.add(1)
    val size = SourceCompiler.inputBufferSize(bufferSize)
    lazily(() =>
      JavaFlowSupport.Source.fromPublisher(factory().asInstanceOf[JFlow.Publisher[A]])
    ).addAttributes(Attributes.inputBuffer(size, size))
  }

  def fromBlocking[A](open: () => BlockingCursor[A]): Source[A, NotUsed] =
    // unfoldResource reads on the blocking IO dispatcher and closes exactly once
    Source.unfoldResource[A, BlockingCursor[A]](open, _.next(), _.close())

  def native[A](leaf: NativeStream[A]): Source[A, NotUsed] =
    leaf match {
      case own: SourceLeaf[A @unchecked] => own.source
      case foreign =>
        bridges.add(1)
        lazily(() =>
          JavaFlowSupport.Source.fromPublisher(
            foreign.openPublisher().asInstanceOf[JFlow.Publisher[A]]
          )
        )
    }

  // a run of the neutral interpreter (hub sources, preflight): one bridge
  def fromCursor[A](
    open: RunContext => Cursor[A],
    name: String
  ): Source[A, NotUsed] = {
    bridges.add(1)
    val opener = NeutralCompiler.fromCursor(open, name)
    Source.fromMaterializer {
      (
        materializer,
        _
      ) =>
        JavaFlowSupport.Source.fromPublisher(
          new CursorPublisher[A](
            opener,
            StreamRuntime.context(materializer.executionContext, bridges)
          )
        )
    }.mapMaterializedValue(_ => NotUsed)
  }

  // --------------------------------------------------------------------------- operators

  def map[A, B](
    up: Source[A, NotUsed],
    f: A => B
  ): Source[B, NotUsed] = up.map(f)

  def mapConcat[A, B](
    up: Source[A, NotUsed],
    f: A => immutable.Iterable[B]
  ): Source[B, NotUsed] = up.mapConcat(f)

  def stateful[A, S, B](
    up: Source[A, NotUsed],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B],
    name: String
  ): Source[B, NotUsed] =
    up.via(new StatefulStage(init, step, onComplete, name))

  // the next streams in one stage, flattened (constant memory for recursive concats)
  def concat[A](
    up: Source[A, NotUsed],
    next: () => AsyncStream[A]
  ): Source[A, NotUsed] =
    up.concatLazy(Source.fromGraph(new ConcatQueueStage[A](next, stream => compile(stream))))

  def flatMapConcat[A, B](
    up: Source[A, NotUsed],
    f: A => AsyncStream[B]
  ): Source[B, NotUsed] =
    up.flatMapConcat(a => compile(f(a)))

  def evalMap[A, B](
    up: Source[A, NotUsed],
    f: A => Future[B]
  ): Source[B, NotUsed] = up.mapAsync(1)(f)

  def mapError[A](
    up: Source[A, NotUsed],
    pf: PartialFunction[Throwable, Throwable]
  ): Source[A, NotUsed] = up.mapError(pf)

  def recoverWith[A](
    up: Source[A, NotUsed],
    pf: PartialFunction[Throwable, AsyncStream[A]]
  ): Source[A, NotUsed] =
    up.recoverWithRetries(1, pf.andThen(stream => compile(stream)))

  def onTerminate[A](
    up: Source[A, NotUsed],
    f: Termination => Future[Unit]
  ): Source[A, NotUsed] =
    up.via(new TerminationStage[A](f))

  def concurrently[A](
    up: Source[A, NotUsed],
    task: () => Future[Any],
    awaitCompletion: Boolean
  ): Source[A, NotUsed] =
    up.via(new ConcurrentTaskStage[A](task, awaitCompletion))

  def viaNative[A, B](
    up: Source[A, NotUsed],
    pipe: NativePipe[A, B]
  ): Source[B, NotUsed] =
    pipe match {
      case own: FlowPipe[A @unchecked, B @unchecked] => up.via(own.flow)
      case foreign                                   =>
        // the upstream as a publisher into the foreign pipe, its output back: two bridges
        bridges.add(2)
        Source.fromMaterializer {
          (
            materializer,
            _
          ) =>
            val upstream =
              up.toMat(JavaFlowSupport.Sink.asPublisher[A](fanout = false))(Keep.right)
                .run()(materializer)
            JavaFlowSupport.Source.fromPublisher(
              foreign.transform(upstream).asInstanceOf[JFlow.Publisher[B]]
            )
        }.mapMaterializedValue(_ => NotUsed)
    }
}
