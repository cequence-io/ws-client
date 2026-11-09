package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl.{
  Cursor,
  Cursors,
  FromCursor,
  RunContext,
  StreamRuntime,
  Trampoline
}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * A HOT fan-out of one upstream run to any number of consumers (Akka `AsyncBroadcastHub`
 * semantics), running on the neutral interpreter. The upstream starts when the hub is created.
 *
 *   - `source(prefix)` is a re-runnable stream; each run registers a consumer WHEN THE RUN
 *     STARTS, emits `prefix` first (live elements are buffered for it meanwhile), then the
 *     live elements. A cancelled or finished run unregisters its consumer.
 *   - The slowest registered consumer paces the hub: at most `bufferSize` elements are
 *     buffered. A new consumer starts at the oldest buffered element. Without consumers the
 *     hub fills its buffer and then back-pressures - unless `drain = true`, which adds an
 *     always-on consumer (elements nobody consumes are dropped).
 *   - When the upstream completes (fails), every consumer gets the remaining buffered
 *     elements, then the completion (failure); a consumer registering after that completes
 *     (fails) at once.
 *   - `shutdown()` cancels the upstream and completes every consumer.
 *
 * In a native backend (Akka, fs2, ZIO), a hub source is bridged once.
 */
final class AsyncBroadcastHub[A] private (
  upstream: AsyncStream[A],
  bufferSize: Int,
  drain: Boolean
)(
  implicit ec: ExecutionContext
) {

  // all state guarded by `lock`
  private val lock = new Object
  private val ring = new Array[Any](bufferSize)
  private var base = 0L // offset of the oldest buffered element
  private var head = 0L // offset of the next element from the upstream
  private var terminal: Option[Try[Unit]] = None
  private var pulling = false
  private val consumers = mutable.LinkedHashSet.empty[HubConsumer]

  private val completionPromise = Promise[Unit]()
  // opened on the ec (the upstream's thunks never run on the creating thread)
  @volatile private var upstreamCursor: Cursor[A] = _

  /**
   * Completes when the upstream completed (or the hub was shut down); fails when it failed.
   */
  def completion: Future[Unit] = completionPromise.future

  /** The number of consumers currently registered (without the `drain` one). */
  def consumerCount: Int = lock.synchronized(consumers.size)

  /** A stream of the hub's elements, after `prefix` - see the class description. */
  def source(prefix: AsyncStream[A] = AsyncStream.empty): AsyncStream[A] =
    AsyncStream.defer {
      val consumer = register()
      val live: AsyncStream[A] = FromCursor[A](
        ctx => {
          consumer.attach(ctx)
          consumer
        },
        "broadcastHub"
      )
      (prefix ++ live).onTerminate(_ => consumer.cancel())
    }

  /** Cancels the upstream and completes every consumer. */
  def shutdown(): Unit = {
    val waiting = lock.synchronized {
      if (terminal.isEmpty) terminal = Some(Success(()))
      takeAllWaiting()
    }
    val cursor = upstreamCursor
    if (cursor != null) cursor.cancel()
    waiting.foreach { case (promise, consumer) => consumer.deliver(promise, Success(None)) }
    completionPromise.trySuccess(())
  }

  // ------------------------------------------------------------------------------ internal

  private def register(): HubConsumer = {
    val consumer = new HubConsumer
    lock.synchronized {
      consumer.offset = if (terminal.isDefined) head else base
      if (terminal.isEmpty) consumers += consumer
      else consumer.lateTerminal = terminal
    }
    consumer
  }

  // under lock: the waiting consumers, removed from waiting, with the offset each one waits for
  // (each promise stays reachable as `delivering` until the consumer's next next() - by then it
  // has completed - or its cancel)
  private def takeAllWaiting(): List[(Promise[Option[A]], HubConsumer)] = {
    val waiting = consumers.toList.collect {
      case consumer if consumer.waiting != null => (consumer.waiting, consumer)
    }
    waiting.foreach { case (promise, consumer) =>
      consumer.waiting = null
      consumer.delivering = promise
    }
    waiting
  }

  // under lock: drop the elements every consumer has passed
  private def trim(): Unit = {
    val minOffset =
      if (consumers.nonEmpty) consumers.iterator.map(_.offset).min
      else if (drain) head
      else base
    while (base < minOffset) {
      ring((base % bufferSize).toInt) = null
      base += 1
    }
  }

  // pulls the upstream while there is buffer space (a loop: synchronous results do not recurse)
  private def pullUpstream(): Unit = {
    val cursor = upstreamCursor
    var continue = cursor != null
    while (continue) {
      val shouldPull = lock.synchronized {
        trim()
        if (!pulling && terminal.isEmpty && head - base < bufferSize) {
          pulling = true
          true
        } else
          false
      }

      if (!shouldPull)
        continue = false
      else {
        val future = cursor.next()
        future.value match {
          case Some(result) => onUpstream(result)
          case None =>
            continue = false
            future.onComplete { result =>
              onUpstream(result)
              pullUpstream()
            }(Trampoline)
        }
      }
    }
  }

  private def onUpstream(result: Try[Option[A]]): Unit = {
    val (delivered, terminated) = lock.synchronized {
      pulling = false
      result match {
        case Success(Some(element)) =>
          ring((head % bufferSize).toInt) = element
          head += 1
          // consumers waiting for exactly this element get it now
          val waiting = takeAllWaiting()
          waiting.foreach(_._2.offset += 1)
          (waiting.map(w => (w, Success(Some(element)): Try[Option[A]])), None)
        case Success(None) =>
          terminal = Some(Success(()))
          (takeAllWaiting().map(w => (w, Success(None): Try[Option[A]])), terminal)
        case Failure(e) =>
          terminal = Some(Failure(e))
          (takeAllWaiting().map(w => (w, Failure(e): Try[Option[A]])), terminal)
      }
    }

    delivered.foreach { case ((promise, consumer), value) => consumer.deliver(promise, value) }
    terminated.foreach {
      case Success(_) => completionPromise.trySuccess(())
      case Failure(e) => completionPromise.tryFailure(e)
    }
  }

  private final class HubConsumer extends Cursor[A] {
    // guarded by the hub's lock
    var offset = 0L
    var waiting: Promise[Option[A]] = _
    // handed over, completing on the consumer's ec: a cancel meanwhile completes it with None
    var delivering: Promise[Option[A]] = _
    var lateTerminal: Option[Try[Unit]] = None
    var cancelled = false
    // the consumer run's context: its waiting next() completes on its own ec
    @volatile private var runContext: RunContext = _

    def attach(ctx: RunContext): Unit = runContext = ctx

    def deliver(
      promise: Promise[Option[A]],
      value: Try[Option[A]]
    ): Unit = {
      val ctx = runContext
      if (ctx == null)
        promise.tryComplete(value)
      else
        try
          ctx.ec.execute(new Runnable {
            def run(): Unit = promise.tryComplete(value)
          })
        catch { case scala.util.control.NonFatal(_) => promise.tryComplete(value) }
    }

    def next(): Future[Option[A]] = {
      val result: Future[Option[A]] = lock.synchronized {
        delivering = null // the previous hand-over has completed (one next() at a time)
        if (cancelled)
          Cursors.NoneFuture
        else if (lateTerminal.isDefined)
          terminalFuture(lateTerminal.get)
        else if (offset < head) {
          val element = ring((offset % bufferSize).toInt).asInstanceOf[A]
          offset += 1
          Future.successful(Some(element))
        } else if (terminal.isDefined)
          terminalFuture(terminal.get)
        else {
          val promise = Promise[Option[A]]()
          waiting = promise
          promise.future
        }
      }
      pullUpstream()
      result
    }

    def cancel(): Unit = {
      val promises = lock.synchronized {
        if (cancelled)
          Nil
        else {
          cancelled = true
          consumers -= this
          val outstanding = List(waiting, delivering).filter(_ != null)
          waiting = null
          delivering = null
          outstanding
        }
      }
      promises.foreach(_.trySuccess(None))
      // the freed buffer space is refilled on the hub's ec: pulling runs the upstream's user
      // functions, which never run on the cancelling thread
      try
        ec.execute(new Runnable {
          def run(): Unit = pullUpstream()
        })
      catch { case scala.util.control.NonFatal(_) => pullUpstream() }
    }

    private def terminalFuture(result: Try[Unit]): Future[Option[A]] =
      result match {
        case Success(_) => Cursors.NoneFuture
        case Failure(e) => Future.failed(e)
      }
  }

  // the run context on the creating thread: a StreamDiagnostics.counted scope covers the hub
  private val upstreamContext = StreamRuntime.context(ec)

  ec.execute(new Runnable {
    def run(): Unit =
      // shut down before this task ran: the upstream (its request) is never started
      if (!lock.synchronized(terminal.isDefined)) {
        upstreamCursor = Cursors.open(upstream, upstreamContext)
        if (lock.synchronized(terminal.isDefined))
          upstreamCursor.cancel() // shut down meanwhile
        else pullUpstream()
      }
  })
}

object AsyncBroadcastHub {

  /**
   * A hub running `upstream` now (HOT), on `ec`.
   *
   * @param bufferSize
   *   the elements buffered for the slowest consumer
   * @param drain
   *   an always-on consumer: the hub never back-pressures for lack of consumers
   */
  def apply[A](
    upstream: AsyncStream[A],
    bufferSize: Int = 256,
    drain: Boolean = false
  )(
    implicit ec: ExecutionContext
  ): AsyncBroadcastHub[A] = {
    require(bufferSize > 0, s"bufferSize must be positive: $bufferSize")
    new AsyncBroadcastHub(upstream, bufferSize, drain)
  }
}
