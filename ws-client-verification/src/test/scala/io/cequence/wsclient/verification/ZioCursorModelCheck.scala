package io.cequence.wsclient.verification

import io.cequence.wsclient.stream.ZioCursor
import zio.stream.ZStream
import zio.{Executor, Runtime, Unsafe, ZIO}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.util.Success

/**
 * `ZioCursor` (a ZIO stream as a neutral run: one fiber serving one chunk per demand) with a
 * `cancel()` racing `next()` and the fiber. Checked: the cancel completes the outstanding
 * `next()`, the cursor is at its end afterwards, and the stream's scope is closed exactly
 * once.
 *
 * The ZIO runtime's fibers run on the scenario's own threads (one per task), so the checker
 * interleaves them with the consumer and the canceller. The runtime itself is built once,
 * outside the checked code.
 *
 * A fiber is an interpreter loop - one iteration per effect: the checker's default loop bound
 * takes a stream run to its end for a livelock ("Concurrent test has hung"), hence the bound.
 */
object ZioCursorModelCheck extends ModelCheckSuite("ZioCursor", loopBound = Some(100000)) {

  import ModelCheckSupport.runContext

  // the runtime's executor: the current scenario's execution context
  private object Switchboard extends ExecutionContext {
    @volatile var current: ExecutionContext = Inline

    override def execute(runnable: Runnable): Unit = current.execute(runnable)
    override def reportFailure(cause: Throwable): Unit = current.reportFailure(cause)
  }

  private val runtime: Runtime[Any] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ZIO.runtime[Any].onExecutor(Executor.fromExecutionContext(Switchboard)))
        .getOrThrowFiberFailure()
    }

  scenario("a cancel racing next() and the fiber: next() completes, the scope closes once") {
    val ec = new ThreadPerTask
    Switchboard.current = ec
    val released = new AtomicInteger
    val stream =
      ZStream
        .fromIterable(List(1, 2, 3))
        .rechunk(1)
        .ensuring(ZIO.succeed(released.incrementAndGet()))
    val cursor = new ZioCursor[Int](stream, runtime, runContext(ec))
    val puller = new Puller(cursor, 2)
    val consumer = thread(puller.pull())
    val canceller = thread(cursor.cancel())
    consumer.join()
    canceller.join()
    ec.joinAll()

    ensure(puller.last.isCompleted, "the cancel left an outstanding next() incomplete")
    ensure(puller.results.forall(_.isSuccess), s"results ${puller.results}")
    ensure(
      puller.elements == Vector(1, 2).take(puller.elements.size),
      s"order ${puller.elements}"
    )
    ensure(cursor.next().value.contains(Success(None)), "next() after the cancel is not None")
    ensure(released.get <= 1, s"the stream's finalizer ran ${released.get} times")
    ensure(
      puller.elements.isEmpty || released.get == 1,
      s"elements ${puller.elements}, but the stream's scope was not closed"
    )
  }

  scenario("every element, then the end: the scope is closed before the end is signalled") {
    val ec = new ThreadPerTask
    Switchboard.current = ec
    val released = new AtomicInteger
    val stream =
      ZStream
        .fromIterable(List(1, 2))
        .rechunk(1)
        .ensuring(ZIO.succeed(released.incrementAndGet()))
    val cursor = new ZioCursor[Int](stream, runtime, runContext(ec))
    val puller = new Puller(cursor, Int.MaxValue)
    val consumer = thread(puller.pull())
    consumer.join()
    ec.joinAll()

    ensure(
      puller.results == Vector(Success(Some(1)), Success(Some(2)), Success(None)),
      s"results ${puller.results}"
    )
    ensure(released.get == 1, s"the stream's finalizer ran ${released.get} times")
  }
}
