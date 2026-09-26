package io.cequence.wsclient.service.adapter

import akka.stream.Materializer
import io.cequence.wsclient.service.CloseableService

trait ServiceBaseAdaptersAkka[S <: CloseableService] { self: ServiceBaseAdapters[S] =>

  /**
   * Runs every call on ALL `underlyings` at once and returns the first response. Privacy and
   * cost: the full request reaches every provider, and the losing calls are not cancelled -
   * they complete (and are billed) anyway. Only combine providers that may all see the data.
   */
  def parallelTakeFirst(
    underlyings: S*
  )(
    implicit materializer: Materializer
  ): S =
    wrapAndDelegate(new ParallelTakeFirstAdapter(underlyings))
}
