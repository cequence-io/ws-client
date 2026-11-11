package io.cequence.wsclient.stream.impl

import io.cequence.wsclient.stream.AsyncStream

import scala.concurrent.Future

/**
 * A native leaf (a `NativeStream`) whose backend can preflight a stream itself - so the
 * preflighted stream stays native (no bridge for a consumer of that backend) instead of
 * becoming a run of the neutral interpreter. `AsyncStream.preflight` delegates to the leaf of
 * a stream that has one.
 *
 * Obligations: `preflight` never throws (a failed future instead), compiles `stream` - whose
 * leaf this is - whole with its backend's compiler, starts that run at once on the leaf's own
 * runtime, and keeps `AsyncStream.preflight`'s contract: the future completes at the first
 * element or the empty completion and fails if the run fails before; the returned stream
 * replays that element and continues the same run - once ([[NativePreflight.singleRunOnly]]
 * for any further run) - and holds the run open until it is run.
 */
private[wsclient] trait NativePreflight {
  def preflight[A](stream: AsyncStream[A]): Future[AsyncStream[A]]
}

private[wsclient] object NativePreflight {

  /** What every run but the first of a preflighted stream fails with. */
  def singleRunOnly(): IllegalStateException =
    new IllegalStateException("A preflighted stream supports a single run only")
}
