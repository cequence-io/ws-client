package io.cequence.wsclient.service

import akka.stream.Materializer
import akka.stream.scaladsl.{JavaFlowSupport, Source}
import io.cequence.wsclient.stream.DeferredPublisher

import java.util.concurrent.Flow

/**
 * Bridges `Source` blueprints to the backend-agnostic `java.util.concurrent.Flow` world.
 */
object SourcePublishersAkka {

  /**
   * A COLD, SINGLE-SHOT `Flow.Publisher` from a `Source` blueprint: nothing runs until the
   * first `subscribe`, at which point the blueprint is materialized on `materializer` (for
   * HTTP-backed blueprints this is when the request fires - the same moment it would fire for
   * a directly materialized `Source`). Downstream cancellation propagates into the
   * materialized stream and aborts the underlying HTTP connection, exactly as with a native
   * `runWith`.
   */
  def deferred[T](
    blueprint: => Source[T, _]
  )(
    implicit materializer: Materializer
  ): Flow.Publisher[T] =
    new DeferredPublisher[T](() =>
      blueprint.runWith(JavaFlowSupport.Sink.asPublisher(fanout = false))
    )
}
