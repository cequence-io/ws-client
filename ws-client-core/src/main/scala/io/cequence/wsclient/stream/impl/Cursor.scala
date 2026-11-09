package io.cequence.wsclient.stream.impl

import scala.concurrent.{ExecutionContext, Future}

/**
 * One run of a stream on the neutral interpreter, consumed by pulling.
 *
 * Discipline (every implementation and every caller):
 *   - at most ONE `next()` is outstanding at a time; `next()` is never called after a terminal
 *     result (`None` or a failed future) or after `cancel()`
 *   - a `None` result is normal completion; a failed future is a failure
 *   - `cancel()` is idempotent, thread-safe, and may race an outstanding `next()`, which then
 *     completes promptly with `None`. It propagates upstream, down to the leaf (aborting an
 *     HTTP request, closing an `InputStream` / iterator)
 *   - the futures a cursor returns complete either synchronously (already completed when
 *     returned - the fast path) or on a thread of the run's `RunContext.ec`: a foreign
 *     completion (a publisher's `onNext`, a blocking read, a user future) is re-entered
 *     through `ec`, so user code never runs on an IO thread. Internal continuations run on the
 *     [[Trampoline]], i.e. on the thread that completed the future
 */
private[wsclient] trait Cursor[+A] {

  def next(): Future[Option[A]]

  def cancel(): Unit
}

/**
 * The execution environment of one run: `ec` for every user function and every foreign
 * completion, `blockingEc` for blocking reads (`fromInputStream`, `fromBlockingIterator`),
 * `bridges` for the bridges the run builds (its lazy children included).
 */
private[wsclient] final class RunContext(
  val ec: ExecutionContext,
  val blockingEc: ExecutionContext,
  val bridges: BridgeCounter
)

/** A compiled stream for the neutral interpreter: opens one independent run per call. */
private[wsclient] trait Opener[+A] {

  /** Starts a run. Never throws: a failure to start is a cursor whose first `next()` fails. */
  def open(ctx: RunContext): Cursor[A]
}

/**
 * A blocking source of elements (an `InputStream`, a blocking iterator). `next()` and
 * `close()` are only ever called from the run's blocking pool, never concurrently; `close()`
 * is called exactly once, after the last `next()` returned (or never, when the cursor was
 * never opened).
 */
private[wsclient] trait BlockingCursor[+A] {

  def next(): Option[A]

  def close(): Unit
}
