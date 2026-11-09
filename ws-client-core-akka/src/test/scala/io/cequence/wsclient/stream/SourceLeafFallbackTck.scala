package io.cequence.wsclient.stream

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import io.cequence.wsclient.stream.SourceInterop._
import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.flow.FlowPublisherVerification
import org.scalatestplus.testng.TestNGSuiteLike

import java.util.concurrent.Flow
import scala.concurrent.ExecutionContext

/**
 * The Reactive Streams TCK over a native leaf consumed outside its backend: the Akka source
 * runs through its fallback publisher, into the neutral interpreter, and out as a publisher.
 */
class SourceLeafFallbackTck
    extends FlowPublisherVerification[java.lang.Long](new TestEnvironment(1000L, 200L), 1000L)
    with TestNGSuiteLike {

  private lazy val system = ActorSystem("source-leaf-fallback-tck")
  implicit private lazy val materializer: Materializer = Materializer(system)
  implicit private lazy val ec: ExecutionContext = system.dispatcher

  private def source(elements: Long): Source[java.lang.Long, akka.NotUsed] = {
    val all = Source.unfold(0L)(n => Some((n + 1, java.lang.Long.valueOf(n))))
    if (elements == Long.MaxValue) all else all.take(elements)
  }

  override def createFlowPublisher(elements: Long): Flow.Publisher[java.lang.Long] =
    source(elements).toAsyncStream.map(identity).toPublisher[java.lang.Long]

  override def createFailedFlowPublisher(): Flow.Publisher[java.lang.Long] =
    Source
      .failed[java.lang.Long](new RuntimeException("TCK failed source"))
      .toAsyncStream
      .toPublisher
}
