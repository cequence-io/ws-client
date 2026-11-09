package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl._
import zio._
import zio.stream.{ZChannel, ZPipeline, ZStream}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Flow => JFlow}
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.control.NonFatal

private[wsclient] object ZioCompiler {

  // the most elements an element stage reads ahead from an emitted iterable (one output chunk)
  val MaxOutputChunk = 64

  // a native ZIO leaf's defect (a throw inside the user's own ZStream.map, ZStream.die) counts as
  // a failure for recover / mapError / onTerminate - as the other backends see it through the
  // cursor bridge. An interruption stays an interruption on ZIO (the hook sees Cancelled); a
  // ZIO leaf ending with an interruption it raised itself (e.g. joining an interrupted fiber)
  // reaches the other backends as a failure instead - a sanctioned difference
  private[stream] def failureOf(cause: Cause[Throwable]): Option[Throwable] =
    if (cause.isInterruptedOnly) None else Some(cause.squash)

  def toZStream[A](stream: AsyncStream[A]): ZStream[Any, Throwable, A] =
    new ZioCompiler(BridgeCounter.current()).compile(stream)

  /**
   * `pipe` as a ZIO stream function: a pipe that is exactly one ZIO pipeline applies it; any
   * other pipe is applied to the function's own input stream, natively.
   */
  def toZStreamFunction[A, B](
    pipe: AsyncPipe[A, B]
  ): ZStream[Any, Throwable, A] => ZStream[Any, Throwable, B] =
    pipe(Nodes.Hole) match {
      case ViaNative(hole, own: ZioPipe[A @unchecked, B @unchecked]) if hole eq Nodes.Hole =>
        _.via(own.pipeline)
      case _ =>
        val compiler = new ZioCompiler(BridgeCounter.current())
        in => compiler.compile(pipe(Native(new ZioLeaf[A](in, Runtime.default))))
    }
}

/**
 * Compiles an [[AsyncStream]] into a ZIO stream - natively: a ZIO leaf is returned as is,
 * neutral leaves become ZIO streams, and every operator a ZIO stream operator. User functions
 * run under `ZIO.attempt` (a throw is a failure, never a defect); element functions process a
 * chunk element by element - a failure emits the outputs of the elements before it first
 * (Akka's element order). A bridge is built only for a foreign leaf (one) or a foreign native
 * pipe (two). Compiling runs no user code: every thunk runs per run.
 */
private[wsclient] final class ZioCompiler(bridges: BridgeCounter) extends StreamCompiler {

  type R[+A] = ZStream[Any, Throwable, A]

  def compile[A](stream: AsyncStream[A]): ZStream[Any, Throwable, A] =
    StreamCompiler.compile(this, stream)

  // a stream built per run; a throw while building it is a failure
  private def safely[A](build: => ZStream[Any, Throwable, A]): ZStream[Any, Throwable, A] =
    ZStream.unwrap(ZIO.attempt(build))

  private def fromFuture[A](future: => Future[A]): Task[A] = ZIO.fromFuture(_ => future)

  // ------------------------------------------------------------------------------ leaves

  def emits[A](as: immutable.Iterable[A]): ZStream[Any, Throwable, A] =
    ZStream
      .fromZIO(ZIO.attempt(as.iterator))
      .flatMap(iterator => ZStream.fromIterator(iterator, 64))

  def failed[A](error: Throwable): ZStream[Any, Throwable, A] = ZStream.fail(error)

  def defer[A](thunk: () => AsyncStream[A]): ZStream[Any, Throwable, A] = safely(
    compile(thunk())
  )

  def futureStream[A](thunk: () => Future[AsyncStream[A]]): ZStream[Any, Throwable, A] =
    ZStream.fromZIO(fromFuture(thunk())).flatMap(stream => safely(compile(stream)))

  // a publisher is foreign to ZIO: one bridge
  def fromPublisher[A](
    factory: () => JFlow.Publisher[_ <: A],
    bufferSize: Int
  ): ZStream[Any, Throwable, A] = {
    bridges.add(1)
    ZioBridges.cursorStream[A](ctx => SubscriberCursor[A](factory, bufferSize, ctx), bridges)
  }

  // open and every read on the blocking pool; close exactly once, after the last read returned
  def fromBlocking[A](open: () => BlockingCursor[A]): ZStream[Any, Throwable, A] =
    ZStream
      .acquireReleaseWith(ZIO.attemptBlocking(open()))(cursor =>
        ZIO.attemptBlocking(cursor.close()).ignore
      )
      .flatMap { cursor =>
        ZStream.repeatZIOOption(
          ZIO.attemptBlocking(cursor.next()).mapError(Some(_)).flatMap {
            case Some(element) => ZIO.succeed(element)
            case None          => ZIO.fail(None)
          }
        )
      }

  def native[A](leaf: NativeStream[A]): ZStream[Any, Throwable, A] =
    leaf match {
      case own: ZioLeaf[A @unchecked] => own.stream
      case foreign =>
        bridges.add(1)
        ZioBridges.cursorStream[A](
          ctx =>
            SubscriberCursor[A](() => foreign.openPublisher(), Cursors.BridgeBufferSize, ctx),
          bridges
        )
    }

  // a run of the neutral interpreter (hub sources, preflight): one bridge
  def fromCursor[A](
    open: RunContext => Cursor[A],
    name: String
  ): ZStream[Any, Throwable, A] = {
    bridges.add(1)
    ZioBridges.cursorStream(open, bridges)
  }

  // --------------------------------------------------------------------------- operators

  // the element stages: per-run state, each element processed in order; a throw emits the
  // outputs before it, then fails; `stop` emits and ends (the upstream is not read again). An
  // element's outputs are read lazily, at most MaxOutputChunk per output chunk: a lazy or
  // infinite expansion is back-pressured (read ahead by less than one chunk), never forced whole
  private def elementwise[A, S, B](
    up: ZStream[Any, Throwable, A],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B]
  ): ZStream[Any, Throwable, B] = {
    type Channel = ZChannel[Any, ZNothing, Chunk[A], Any, Throwable, Chunk[B], Any]
    val limit = ZioCompiler.MaxOutputChunk

    def output(out: Chunk[B]): Channel =
      if (out.isEmpty) ZChannel.unit else ZChannel.write(out)

    // the rest of one element's outputs, chunk by chunk
    def drain(it: Iterator[B]): Channel =
      ZChannel.suspend {
        val out = ChunkBuilder.make[B]()
        var n = 0
        var more = false
        var error: Throwable = null
        try {
          while (n < limit && it.hasNext) {
            out += it.next()
            n += 1
          }
          more = it.hasNext
        } catch { case NonFatal(e) => error = e }
        val emitted = output(out.result())
        if (error != null)
          emitted *> ZChannel.fail(error)
        else if (more)
          emitted *> drain(it)
        else
          emitted
      }

    // the elements of `chunk` from index `from` (a pipeline channel never sees an upstream
    // failure - the stream propagates it)
    def elements(
      chunk: Chunk[A],
      from: Int,
      state: S
    ): Channel =
      ZChannel.suspend {
        var s = state
        val out = ChunkBuilder.make[B]()
        var n = 0
        var pending: Iterator[B] = null
        var stop = false
        var error: Throwable = null
        var i = from
        while (i < chunk.size && n < limit && pending == null && !stop && error == null) {
          try {
            val result = step(s, chunk(i))
            s = result.state
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
        val nextState = s
        def continue: Channel =
          if (stop) ZChannel.unit
          else if (next < chunk.size) elements(chunk, next, nextState)
          else loop(nextState)

        if (error != null)
          emitted *> ZChannel.fail(error)
        else if (pending != null) {
          val remaining = pending
          emitted *> drain(remaining) *> continue
        } else
          emitted *> continue
      }

    def loop(state: S): Channel =
      ZChannel.readWithCause(
        (chunk: Chunk[A]) => elements(chunk, 0, state),
        (cause: Cause[ZNothing]) => ZChannel.refailCause(cause),
        (_: Any) =>
          ZChannel.suspend(
            try drain(onComplete(state).iterator)
            catch { case NonFatal(e) => ZChannel.fail(e) }
          )
      )

    up.via(
      ZPipeline.fromChannel(
        ZChannel.suspend[Any, ZNothing, Chunk[A], Any, Throwable, Chunk[B], Any](
          try loop(init())
          catch { case NonFatal(e) => ZChannel.fail(e) }
        )
      )
    )
  }

  def map[A, B](
    up: ZStream[Any, Throwable, A],
    f: A => B
  ): ZStream[Any, Throwable, B] =
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
    up: ZStream[Any, Throwable, A],
    f: A => immutable.Iterable[B]
  ): ZStream[Any, Throwable, B] =
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
    up: ZStream[Any, Throwable, A],
    init: () => S,
    step: (S, A) => AsyncStream.Step[S, B],
    onComplete: S => immutable.Iterable[B],
    name: String
  ): ZStream[Any, Throwable, B] =
    elementwise(up, init, step, onComplete)

  def concat[A](
    up: ZStream[Any, Throwable, A],
    next: () => AsyncStream[A]
  ): ZStream[Any, Throwable, A] =
    up ++ safely(compile(next()))

  def flatMapConcat[A, B](
    up: ZStream[Any, Throwable, A],
    f: A => AsyncStream[B]
  ): ZStream[Any, Throwable, B] =
    up.flatMap(a => safely(compile(f(a))))

  def evalMap[A, B](
    up: ZStream[Any, Throwable, A],
    f: A => Future[B]
  ): ZStream[Any, Throwable, B] =
    up.mapZIO(a => fromFuture(f(a)))

  def mapError[A](
    up: ZStream[Any, Throwable, A],
    pf: PartialFunction[Throwable, Throwable]
  ): ZStream[Any, Throwable, A] =
    up.catchAllCause { cause =>
      ZioCompiler.failureOf(cause) match {
        case Some(e) =>
          // the whole partial function (its guards too) runs inside the try: a throw is a
          // failure, never a defect
          val mapped =
            try pf.applyOrElse(e, identity[Throwable])
            catch { case NonFatal(thrown) => thrown }
          if (mapped eq e) ZStream.failCause(cause) else ZStream.fail(mapped)
        case None => ZStream.failCause(cause)
      }
    }

  // switches once: a failure of the recovery stream is final
  def recoverWith[A](
    up: ZStream[Any, Throwable, A],
    pf: PartialFunction[Throwable, AsyncStream[A]]
  ): ZStream[Any, Throwable, A] =
    up.catchAllCause { cause =>
      ZioCompiler.failureOf(cause) match {
        case Some(e) =>
          safely(
            if (pf.isDefinedAt(e)) compile(pf(e))
            else ZStream.failCause(cause)
          )
        case None => ZStream.failCause(cause)
      }
    }

  // the hook exactly once per run: Completed / Failed gate the downstream signal; any other end
  // (an interrupt, a downstream stop) is Cancelled
  def onTerminate[A](
    up: ZStream[Any, Throwable, A],
    f: Termination => Future[Unit]
  ): ZStream[Any, Throwable, A] =
    safely {
      val fired = new AtomicBoolean(false)
      def once(effect: => Task[Unit]): Task[Unit] =
        ZIO.suspend(if (fired.compareAndSet(false, true)) effect else ZIO.unit)
      def hook(termination: Termination): Task[Unit] = fromFuture(f(termination))

      val failedOrCompleted =
        up.catchAllCause { cause =>
          ZioCompiler.failureOf(cause) match {
            case Some(e) =>
              ZStream
                .fromZIO(
                  once(hook(Termination.Failed(e)).catchAll { hookError =>
                    ZIO.succeed(if (hookError ne e) e.addSuppressed(hookError))
                  })
                )
                .drain ++ ZStream.failCause(cause)
            case None => ZStream.failCause(cause)
          }
        } ++ ZStream.fromZIO(once(hook(Termination.Completed))).drain

      failedOrCompleted.ensuring(once(hook(Termination.Cancelled)).ignore)
    }

  def concurrently[A](
    up: ZStream[Any, Throwable, A],
    task: () => Future[Any],
    awaitCompletion: Boolean
  ): ZStream[Any, Throwable, A] =
    ZStream.unwrap(ZIO.attempt(task()).map { taskFuture =>
      val taskResult = fromFuture(taskFuture).unit
      // a failure of the task interrupts the upstream and fails the stream; its success is ignored
      val main = up.interruptWhen(taskResult *> ZIO.never)
      if (awaitCompletion) main ++ ZStream.fromZIO(taskResult).drain else main
    })

  def viaNative[A, B](
    up: ZStream[Any, Throwable, A],
    pipe: NativePipe[A, B]
  ): ZStream[Any, Throwable, B] =
    pipe match {
      case own: ZioPipe[A @unchecked, B @unchecked] => up.via(own.pipeline)
      case foreign                                  =>
        // the upstream as a publisher into the foreign pipe, its output back: two bridges
        bridges.add(2)
        ZStream.unwrap(ZIO.runtime[Any].map { runtime =>
          val upstream = ZioBridges.publisher(up, runtime)
          ZioBridges.cursorStream[B](
            ctx =>
              SubscriberCursor[B](
                () => foreign.transform(upstream),
                Cursors.BridgeBufferSize,
                ctx
              ),
            bridges
          )
        })
    }
}
