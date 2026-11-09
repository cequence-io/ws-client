package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService

/**
 * The backend-neutral `parallelTakeFirst` (since 1.2.0) - an alternative mixin to
 * ws-client-core-akka's `ServiceBaseAdaptersAkka` (no `Materializer` needed). Mix in one of
 * the two, never both.
 */
trait ParallelServiceAdapters[S <: CloseableService] { self: ServiceBaseAdapters[S] =>

  /**
   * Runs every call on ALL `underlyings` at once and completes with the first one to complete
   * (a response or a failure). Privacy and cost: the full request reaches every provider, and
   * the losing calls are not cancelled - they complete (and are billed) anyway. Only combine
   * providers that may all see the data.
   */
  def parallelTakeFirst(underlyings: S*): S =
    wrapAndDelegate(new FirstCompletedAdapter(underlyings))
}
