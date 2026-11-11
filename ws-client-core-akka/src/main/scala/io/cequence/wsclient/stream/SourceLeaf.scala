package io.cequence.wsclient.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{JavaFlowSupport, Source}
import io.cequence.wsclient.stream.impl.NativePreflight

import java.util.concurrent.{Flow => JFlow}
import scala.concurrent.Future

/**
 * An Akka `Source` kept native inside an [[AsyncStream]] (see `SourceInterop.toAsyncStream`):
 * compiled for Akka (`toSource`) it is `source` itself - no bridge; any other consumer runs it
 * through the fallback publisher, materialized on `fallbackMaterializer` (one bridge). A
 * stream over it is preflighted as an Akka graph, on the same materializer.
 */
private[wsclient] final class SourceLeaf[A] private[wsclient] (
  val source: Source[A, NotUsed],
  fallbackMaterializer: () => Materializer
) extends NativeStream[A]
    with NativePreflight {

  override def openPublisher(): JFlow.Publisher[_ <: A] =
    source.runWith(JavaFlowSupport.Sink.asPublisher[A](fanout = false))(fallbackMaterializer())

  override def preflight[B](stream: AsyncStream[B]): Future[AsyncStream[B]] =
    SourceCompiler.preflight(stream, fallbackMaterializer)

  override def backend: String = SourceLeaf.Backend
}

private[wsclient] object SourceLeaf {

  // "akka" / "pekko" - derived, so the generated Pekko flavor names itself
  val Backend: String =
    classOf[Materializer].getName.stripSuffix(".stream.Materializer").split('.').last

  // the fallback of a leaf whose source only ever runs natively (a pipe's probe input)
  val NoFallback: () => Materializer = () =>
    throw new IllegalStateException(
      s"This $Backend stream can only be run as a $Backend Source"
    )
}

/**
 * An Akka `Flow` kept native inside an [[AsyncPipe]] (see `SourceInterop.toAsyncPipe`):
 * applied to a stream compiled for Akka it is `.via(flow)` - no bridge; any other consumer
 * runs it through `transform`, materialized on `fallbackMaterializer` (two bridges).
 */
private[wsclient] final class FlowPipe[A, B] private[wsclient] (
  val flow: akka.stream.scaladsl.Flow[A, B, NotUsed],
  fallbackMaterializer: () => Materializer
) extends NativePipe[A, B] {

  override def transform(upstream: JFlow.Publisher[_ <: A]): JFlow.Publisher[_ <: B] =
    JavaFlowSupport.Source
      .fromPublisher(upstream.asInstanceOf[JFlow.Publisher[A]])
      .via(flow)
      .runWith(JavaFlowSupport.Sink.asPublisher[B](fanout = false))(fallbackMaterializer())

  override def backend: String = SourceLeaf.Backend
}
