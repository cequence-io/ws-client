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

  /**
   * True only when this cursor is KNOWN to have ended: its next `next()` would return `None`
   * at once, and neither that call nor `cancel()` would do anything observable (no user
   * function, hook, read or request). Asked only by the pulling side, between two `next()`
   * calls. The default - never known - is always correct.
   */
  def exhausted: Boolean = false

  /**
   * The cursor this one has BECOME, or null: every further `next()` / `cancel()` of this
   * cursor would only be passed on to it (and after its end this one ends, with nothing
   * observable). A cursor that holds this one may hold that one instead (`Cursors.resolve`) -
   * which keeps a tail-recursive stream (`def page(i) = load(i).flatMapConcat(p => items(p) ++
   * page(i + 1))`, a `recoverWith` that reconnects) at a constant depth. Asked only by the
   * pulling side, between two `next()` calls. The default - never - is always correct.
   */
  def forwardsTo: Cursor[A] = null
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

  /**
   * Called (once) instead of `open` by a [[CursorPublisher]] subscription that ends before its
   * run was opened - a cancel or an invalid request from inside `onSubscribe`, a throwing
   * `onSubscribe`, an `ec` that rejected the start (since 1.2.1). For an opener that holds a
   * resource for its run (a preflighted stream's response): release it here. Never throws.
   */
  def unopened(): Unit = ()
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
