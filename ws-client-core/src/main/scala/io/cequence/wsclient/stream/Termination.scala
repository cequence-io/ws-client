package io.cequence.wsclient.stream

import scala.concurrent.Future

/** How one run of an [[AsyncStream]] ended - see [[AsyncStream.guaranteeCase]]. */
sealed trait Termination

object Termination {

  /** The upstream completed normally. */
  case object Completed extends Termination

  /** The run failed with `error`. */
  final case class Failed(error: Throwable) extends Termination

  /** The consumer cancelled the run (e.g. a downstream `take` was satisfied). */
  case object Cancelled extends Termination
}

/** A running stream started by [[AsyncStream.run]]. */
trait StreamRun {

  /**
   * Completes when the run ends: successfully on completion or after `cancel()`, failed when
   * the stream (or the element function) failed.
   */
  def done: Future[Unit]

  /** Cancels the run (idempotent): the cancellation travels upstream to the leaf. */
  def cancel(): Unit
}
