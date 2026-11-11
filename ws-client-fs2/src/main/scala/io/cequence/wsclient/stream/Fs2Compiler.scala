package io.cequence.wsclient.stream

import cats.effect.kernel.Async
import fs2.{Chunk, Pull, Stream}
import io.cequence.wsclient.stream.impl._

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Flow => JFlow}
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private[wsclient] object Fs2Compiler {

  // demand of a publisher bridge into fs2 (a foreign leaf, a foreign pipe's output, an fs2
  // pipe's publisher input)
  val BridgeChunkSize = 16

  // the most elements an element stage reads ahead from an emitted iterable (one output chunk)
  val MaxOutputChunk = 64

  /** A publisher as an fs2 stream - the pull bridge (one per subscription of the stream). */
  def publisherStream[F[_], A](
    publisher: => JFlow.Publisher[_ <: A],
    demand: Int,
    bridges: BridgeCounter
  )(
    implicit F: Async[F]
  ): Stream[F, A] =
    cursorStream[F, A](ctx => SubscriberCursor[A](() => publisher, demand, ctx), bridges)

  // a cursor run as an fs2 stream: opened per run, cancelled when the stream ends; each element
  // goes out as it arrives, results already completed join it in one chunk
  def cursorStream[F[_], A](
    open: RunContext => Cursor[A],
    bridges: BridgeCounter
  )(
    implicit F: Async[F]
  ): Stream[F, A] =
    Stream.eval(F.executionContext).flatMap { ec =>
      Stream
        .bracket(F.delay(open(StreamRuntime.context(ec, bridges))))(cursor =>
          F.delay(cursor.cancel())
        )
        .flatMap(cursor => pullCursor[F, A](cursor).stream)
    }

  private def pullCursor[F[_], A](
    cursor: Cursor[A]
  )(
    implicit F: Async[F]
  ): Pull[F, A, Unit] = {
    // a cancelable wait: an interrupt is never held up by the pending next()
    def await(next: Future[Option[A]]): Pull[F, A, Unit] =
      Pull.eval(F.fromFutureCancelable(F.delay((next, F.unit)))).flatMap {
        case None        => Pull.done
        case Some(first) => gather(first)
      }

    // at most one outstanding next(): an incomplete one is awaited by the next round
    def gather(first: A): Pull[F, A, Unit] =
      Pull.suspend {
        val out = Vector.newBuilder[A]
        out += first
        var n = 1
        var pending: Future[Option[A]] = null
        var ended = false
        var failure: Throwable = null
        while (
          pending == null && !ended && failure == null && n < Fs2Compiler.MaxOutputChunk
        ) {
          val next = cursor.next()
          next.value match {
            case Some(Success(Some(element))) =>
              out += element
              n += 1
            case Some(Success(None)) => ended = true
            case Some(Failure(e))    => failure = e
            case None                => pending = next
          }
        }
        val emitted = Pull.output(Chunk.from(out.result()))
        if (failure != null)
          emitted >> Pull.raiseError[F](failure)
        else if (ended)
          emitted
        else if (pending != null) {
          val awaiting = pending
          emitted >> await(awaiting)
        } else
          emitted >> await(cursor.next())
      }

    Pull.suspend(await(cursor.next()))
  }

  def toFs2[F[_], A](
    stream: AsyncStream[A]
  )(
    implicit F: Async[F]
  ): Stream[F, A] =
    new Fs2Compiler[F](BridgeCounter.current()).compile(stream)

  /**
   * `pipe` as an fs2 pipe: a pipe that is exactly one fs2 pipe (of this `Async`) comes back as
   * that pipe; any other pipe is applied to the pipe's own input stream, natively.
   */
  def toFs2Pipe[F[_], A, B](
    pipe: AsyncPipe[A, B]
  )(
    implicit F: Async[F]
  ): fs2.Pipe[F, A, B] =
    pipe(Nodes.Hole) match {
      case ViaNative(hole, own: Fs2Pipe[F @unchecked, A @unchecked, B @unchecked])
          if (hole eq Nodes.Hole) && (own.async eq F) =>
        own.pipe
      case _ =>
        val compiler = new Fs2Compiler[F](BridgeCounter.current())
        in => compiler.compile(pipe(Native(new Fs2Leaf[F, A](in, F, Fs2Runner.none[F]))))
    }
}

/**
 * Compiles an [[AsyncStream]] into an fs2 stream - natively: an fs2 leaf of the same `Async`
 * instance is returned as is, neutral leaves become fs2 streams (a blocking source: the
 * neutral blocking cursor, pulled), and every operator an fs2 operator. A bridge (through
 * `java.util.concurrent.Flow`) is built only for a foreign leaf (one) or a foreign native pipe
 * (two). Compiling runs no user code: every thunk runs per run. Element functions process a
 * chunk element by element - a failure emits the outputs of the elements before it first.
 */
private[wsclient] final class Fs2Compiler[F[_]](
  bridges: BridgeCounter
)(
  implicit F: Async[F]
) extends StreamCompiler {

  type R[+A] = Stream[F, A]

  def compile[A](stream: AsyncStream[A]): Stream[F, A] = StreamCompiler.compile(this, stream)

  // a publisher into fs2: a pull bridge over the neutral SubscriberCursor (fs2's own flow
  // subscriber holds elements back until `demand` of them arrived - an SSE stream reached fs2 in
  // bursts, and a stalled stream withheld up to demand - 1 elements)
  private def bridge[A](
    publisher: => JFlow.Publisher[_ <: A],
    demand: Int = Fs2Compiler.BridgeChunkSize
  ): Stream[F, A] =
    cursorStream[A](ctx => SubscriberCursor[A](() => publisher, demand, ctx))

  private def cursorStream[A](open: RunContext => Cursor[A]): Stream[F, A] =
    Fs2Compiler.cursorStream[F, A](open, bridges)

  // a cancelable wait (the future itself runs on - a Future cannot be cancelled), so a cancel or
  // an interrupt (`concurrently`) is never held up by a user future
  private def fromFuture[A](future: => Future[A]): F[A] =
    F.fromFutureCancelable(F.delay((future, F.unit)))

  // an awaited wait: termination hooks finish before the run ends
  private def awaitFuture[A](future: => Future[A]): F[A] =
    F.fromFuture(F.delay(future))

  // a stream built per run by user code: a throw while building it is a failure of the stream.
  // (A throw out of Stream.suspend's own thunk is no stream failure where the suspended stream
  // follows a `++` or is stepped by a pull: a later recover / mapError never saw it.)
  private def suspended[A](build: => Stream[F, A]): Stream[F, A] =
    Stream.suspend(
      try build
      catch { case NonFatal(e) => Stream.raiseError[F](e) }
    )

  private def output[A](out: Vector[A]): Pull[F, A, Unit] =
    if (out.isEmpty) Pull.done else Pull.output(Chunk.from(out))

  // an iterator, read lazily chunk by chunk (`size` elements, doubling up to MaxOutputChunk): a
  // throw emits the elements read before it, then fails
  private def readIterator[A](
    it: Iterator[A],
    size: Int
  ): Pull[F, A, Unit] =
    Pull.suspend {
      val out = Vector.newBuilder[A]
      var n = 0
      var error: Throwable = null
      try
        while (n < size && it.hasNext) {
          out += it.next()
          n += 1
        }
      catch { case NonFatal(e) => error = e }
      val emitted = output(out.result())
      if (error != null)
        emitted >> Pull.raiseError[F](error)
      else if (n == size)
        emitted >> readIterator(it, math.min(size * 2, Fs2Compiler.MaxOutputChunk))
      else
        emitted
    }

  // ------------------------------------------------------------------------------ leaves

  // the first chunk is one element, so the head of a slow iterator is not held back for a
  // whole chunk (fs2's fromIterator reads a full chunk first, and drops it when a read throws)
  def emits[A](as: immutable.Iterable[A]): Stream[F, A] =
    suspended(readIterator(as.iterator, 1).stream)

  def failed[A](error: Throwable): Stream[F, A] = Stream.raiseError[F](error)

  def defer[A](thunk: () => AsyncStream[A]): Stream[F, A] = suspended(compile(thunk()))

  def futureStream[A](thunk: () => Future[AsyncStream[A]]): Stream[F, A] =
    Stream.eval(fromFuture(thunk())).flatMap(stream => suspended(compile(stream)))

  // a publisher is foreign to fs2: one bridge (SubscriberCursor: `bufferSize` requested up
  // front, half as much after each half consumed)
  def fromPublisher[A](
    factory: () => JFlow.Publisher[_ <: A],
    bufferSize: Int
  ): Stream[F, A] = {
    bridges.add(1)
    bridge[A](factory(), bufferSize)
  }

  // the neutral blocking cursor (ws-client's blocking pool), as on every backend: a cancel
  // never waits for a stalled read, close() follows the last read exactly once, and a throwing
  // close() is logged, not a stream failure
  def fromBlocking[A](open: () => BlockingCursor[A]): Stream[F, A] =
    cursorStream[A](ctx => new BlockingReadCursor[A](open, ctx))

  def native[A](leaf: NativeStream[A]): Stream[F, A] =
    leaf match {
      case own: Fs2Leaf[F @unchecked, A @unchecked] if own.async eq F => own.stream
      case foreign =>
        bridges.add(1)
        bridge[A](foreign.openPublisher())
    }

  // a run of the neutral interpreter (hub sources, preflight): one bridge
  def fromCursor[A](
    open: RunContext => Cursor[A],
    name: String
  ): Stream[F, A] = {
    bridges.add(1)
    val opener = NeutralCompiler.fromCursor(open, name)
    cursorStream[A](ctx => opener.open(ctx))
  }

  // --------------------------------------------------------------------------- operators

  // the element stages: per-run state, each element processed in order; a throw emits the
  // outputs before it, then fails; `stop` emits and ends (the upstream is not pulled again). An
  // element's outputs are read lazily, at most MaxOutputChunk per output chunk: a lazy or
  // infinite expansion is back-pressured (read ahead by less than one chunk), never forced whole
  private def elementwise[A, S, B](
    up: Stream[F, A],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B]
  ): Stream[F, B] =
    suspended {
      val limit = Fs2Compiler.MaxOutputChunk
      var state = init()

      // the rest of one element's outputs, chunk by chunk
      def drain(it: Iterator[B]): Pull[F, B, Unit] = readIterator(it, limit)

      // the elements of `chunk` from index `from`
      def elements(
        chunk: Chunk[A],
        from: Int,
        rest: Stream[F, A]
      ): Pull[F, B, Unit] =
        Pull.suspend {
          val out = Vector.newBuilder[B]
          var n = 0
          var pending: Iterator[B] = null
          var stop = false
          var error: Throwable = null
          var i = from
          while (i < chunk.size && n < limit && pending == null && !stop && error == null) {
            try {
              val result = step(state, chunk(i))
              state = result.state
              stop = result.stop
              val it = result.emit.iterator
              while (n < limit && it.hasNext) {
                out += it.next()
                n += 1
              }
              if (it.hasNext) pending = it
            } catch { case NonFatal(e) => error = e }
            i += 1
          }
          val emitted = output(out.result())
          val next = i
          def continue: Pull[F, B, Unit] =
            if (stop) Pull.done
            else if (next < chunk.size) elements(chunk, next, rest)
            else go(rest)

          if (error != null)
            emitted >> Pull.raiseError[F](error)
          else if (pending != null) {
            val remaining = pending
            emitted >> drain(remaining) >> continue
          } else
            emitted >> continue
        }

      def go(s: Stream[F, A]): Pull[F, B, Unit] =
        s.pull.uncons.flatMap {
          case Some((chunk, rest)) => elements(chunk, 0, rest)
          case None =>
            Pull.suspend(
              try drain(onComplete(state).iterator)
              catch { case NonFatal(e) => Pull.raiseError[F](e) }
            )
        }

      go(up).stream
    }

  def map[A, B](
    up: Stream[F, A],
    f: A => B
  ): Stream[F, B] =
    elementwise[A, Unit, B](
      up,
      () => (),
      (
        _,
        a
      ) => AsyncStream.Step((), f(a) :: Nil),
      _ => Nil
    )

  def mapConcat[A, B](
    up: Stream[F, A],
    f: A => immutable.Iterable[B]
  ): Stream[F, B] =
    elementwise[A, Unit, B](
      up,
      () => (),
      (
        _,
        a
      ) => AsyncStream.Step((), f(a)),
      _ => Nil
    )

  def stateful[A, S, B](
    up: Stream[F, A],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B],
    name: String
  ): Stream[F, B] =
    elementwise(up, init, step, onComplete)

  def concat[A](
    up: Stream[F, A],
    next: () => AsyncStream[A]
  ): Stream[F, A] =
    up ++ suspended(compile(next()))

  def flatMapConcat[A, B](
    up: Stream[F, A],
    f: A => AsyncStream[B]
  ): Stream[F, B] =
    up.flatMap(a => suspended(compile(f(a))))

  def evalMap[A, B](
    up: Stream[F, A],
    f: A => Future[B]
  ): Stream[F, B] =
    up.evalMap(a => fromFuture(f(a)))

  def mapError[A](
    up: Stream[F, A],
    pf: PartialFunction[Throwable, Throwable]
  ): Stream[F, A] =
    up.handleErrorWith { e =>
      Stream.raiseError[F](
        try pf.applyOrElse(e, identity[Throwable])
        catch { case NonFatal(thrown) => thrown }
      )
    }

  // switches once: a failure of the recovery stream is final
  def recoverWith[A](
    up: Stream[F, A],
    pf: PartialFunction[Throwable, AsyncStream[A]]
  ): Stream[F, A] =
    up.handleErrorWith { e =>
      suspended(
        if (pf.isDefinedAt(e)) compile(pf(e))
        else Stream.raiseError[F](e)
      )
    }

  // the hook exactly once per run: Completed / Failed gate the downstream signal; any other end
  // (a cancel, a downstream stop - which fs2 reports as a success) is Cancelled
  def onTerminate[A](
    up: Stream[F, A],
    f: Termination => Future[Unit]
  ): Stream[F, A] =
    Stream.suspend {
      val fired = new AtomicBoolean(false)
      def once(effect: => F[Unit]): F[Unit] =
        F.defer(if (fired.compareAndSet(false, true)) effect else F.unit)
      def hook(termination: Termination): F[Unit] = awaitFuture(f(termination))

      val failedOrCompleted =
        up.handleErrorWith { e =>
          Stream.exec(
            once(F.handleError(hook(Termination.Failed(e))) { hookError =>
              if (hookError ne e) e.addSuppressed(hookError)
            })
          ) ++ Stream.raiseError[F](e)
        } ++ Stream.exec(once(hook(Termination.Completed)))

      failedOrCompleted.onFinalize(once(F.handleError(hook(Termination.Cancelled))(_ => ())))
    }

  def concurrently[A](
    up: Stream[F, A],
    task: () => Future[Any],
    awaitCompletion: Boolean
  ): Stream[F, A] =
    Stream.eval(F.delay(task())).flatMap { taskFuture =>
      val taskResult = F.void(fromFuture(taskFuture))
      val main = up.concurrently(Stream.eval(taskResult))
      if (awaitCompletion) main ++ Stream.exec(taskResult) else main
    }

  def viaNative[A, B](
    up: Stream[F, A],
    pipe: NativePipe[A, B]
  ): Stream[F, B] =
    pipe match {
      case own: Fs2Pipe[F @unchecked, A @unchecked, B @unchecked] if own.async eq F =>
        up.through(own.pipe)
      case foreign =>
        // the upstream as a publisher into the foreign pipe, its output back: two bridges
        bridges.add(2)
        Stream
          .resource(fs2.interop.flow.toPublisher(up))
          .flatMap(upstream => bridge[B](foreign.transform(upstream)))
    }
}
