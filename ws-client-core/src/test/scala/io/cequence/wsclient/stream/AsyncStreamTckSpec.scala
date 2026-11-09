package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl.{Cursor, StreamRuntime, SubscriberCursor}
import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.flow.{
  FlowPublisherVerification,
  FlowSubscriberBlackboxVerification
}
import org.scalatestplus.testng.TestNGSuiteLike

import java.util.concurrent.Flow
import scala.concurrent.Future
import scala.util.{Failure, Success}

/*
 * The Reactive Streams TCK (reactive-streams-tck-flow 1.0.4) over both bridges of the neutral
 * interpreter: CursorPublisher (AsyncStream.toPublisher) for every operator family, and
 * SubscriberCursor (AsyncStream.fromPublisher) as a subscriber.
 */

private[stream] object Tck {
  val env = new TestEnvironment(1000L, 200L)
  implicit val ec: scala.concurrent.ExecutionContext = StreamTestSupport.testEc

  def boxed(stream: AsyncStream[Long]): Flow.Publisher[java.lang.Long] =
    stream.map(l => java.lang.Long.valueOf(l)).toPublisher[java.lang.Long]

  // `elements` longs (Long.MaxValue: infinite)
  def source(elements: Long): AsyncStream[Long] =
    if (elements == Long.MaxValue)
      AsyncStream.fromIterator(() => Iterator.iterate(0L)(_ + 1))
    else
      AsyncStream.fromIterator(() => Iterator.iterate(0L)(_ + 1).take(elements.toInt))

  def failed: Flow.Publisher[java.lang.Long] =
    boxed(AsyncStream.failed(new RuntimeException("TCK failed publisher")))
}

abstract class AsyncStreamPublisherTck(build: Long => AsyncStream[Long])
    extends FlowPublisherVerification[java.lang.Long](Tck.env, 1000L)
    with TestNGSuiteLike {

  override def createFlowPublisher(elements: Long): Flow.Publisher[java.lang.Long] =
    Tck.boxed(build(elements))

  override def createFailedFlowPublisher(): Flow.Publisher[java.lang.Long] = Tck.failed
}

class EmitsTck extends AsyncStreamPublisherTck(Tck.source)

class MapFilterTck
    extends AsyncStreamPublisherTck(n =>
      (if (n == Long.MaxValue) Tck.source(n) else Tck.source(n * 2))
        .filter(_ % 2 == 0)
        .map(_ / 2)
    )

class StatefulEndHookTck
    extends AsyncStreamPublisherTck(n =>
      if (n == Long.MaxValue)
        Tck
          .source(n)
          .stateful(() => 0L)(
            (
              count,
              a
            ) => AsyncStream.Step(count + 1, List(a))
          )
      else
        // n - 1 elements pass the stage, its end hook emits the last one
        Tck
          .source(math.max(n - 1, 0))
          .stateful(() => 0L)(
            (
              count,
              a
            ) => AsyncStream.Step(count + 1, List(a)),
            count =>
              if (n > 0) List(count)
              else Nil
          )
    )

class ConcatTck
    extends AsyncStreamPublisherTck(n =>
      if (n == Long.MaxValue)
        AsyncStream.single(0L) ++ Tck.source(n)
      else
        Tck.source(n / 2) ++ Tck.source(n - n / 2)
    )

class FlatMapConcatTck
    extends AsyncStreamPublisherTck(n =>
      Tck.source(n).flatMapConcat(i => AsyncStream.single(i))
    )

class AsyncEvalMapTck
    extends AsyncStreamPublisherTck(n => Tck.source(n).evalMap(i => Future(i)(Tck.ec)))

class PublisherRoundTripTck
    extends AsyncStreamPublisherTck(n =>
      AsyncStream.fromPublisher(() => Tck.source(n).toPublisher[Long](Tck.ec), bufferSize = 4)
    )

/** SubscriberCursor as a Flow.Subscriber, drained by a consumer pulling every element. */
class SubscriberCursorTck
    extends FlowSubscriberBlackboxVerification[java.lang.Long](Tck.env)
    with TestNGSuiteLike {

  override def createElement(element: Int): java.lang.Long =
    java.lang.Long.valueOf(element.toLong)

  override def createFlowSubscriber(): Flow.Subscriber[java.lang.Long] = {
    val cursor = new SubscriberCursor[java.lang.Long](16, StreamRuntime.context(Tck.ec))
    drain(cursor)
    cursor
  }

  private def drain(cursor: Cursor[java.lang.Long]): Unit =
    cursor
      .next()
      .onComplete {
        case Success(Some(_)) => drain(cursor)
        case Success(None)    => ()
        case Failure(_)       => ()
      }(Tck.ec)
}
