package io.cequence.wsclient.stream

import scala.concurrent.Future

/**
 * A call kept in its producer's native representation (e.g. a ZIO effect) - see
 * [[AsyncCall.fromNative]] (since 1.3.0). The built-in backends recognize their own leaves and
 * run them directly; every other interpreter (the `Future` one included) uses the fallback
 * `start()` - one bridge.
 *
 * Public, so third parties can wrap their own effect types as leaves - consumed through
 * `start()` (a native compiler for a new backend is not a public extension point).
 * Implementations must be immutable blueprints.
 */
abstract class NativeCall[+A] {

  /**
   * The fallback for an interpreter outside the native backend: starts a FRESH run on the
   * producer's own runtime. `cancel()` on the returned run should stop it (e.g. interrupt the
   * effect) where the backend can.
   */
  def start(): CallRun[A]

  /** The backend's name - diagnostics only (e.g. in [[AsyncCall.describe]]). */
  def backend: String
}

/** A running call - see [[AsyncCall.run]] and [[NativeCall.start]] (since 1.3.0). */
trait CallRun[+A] {

  /**
   * The run's outcome; after `cancel()` (before the run ended) a failure with a
   * `java.util.concurrent.CancellationException`.
   */
  def result: Future[A]

  /**
   * Cancels the run (idempotent): it stops at its next step, and the termination hooks see
   * `Cancelled` - see [[AsyncCall]] for what a step in flight does.
   */
  def cancel(): Unit
}
