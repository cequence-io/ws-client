package io.cequence.wsclient.stream

import io.cequence.wsclient.stream.impl.BridgeCounter

/**
 * Diagnostics of the stream layer (since 1.2.0), for tests and debugging.
 *
 * A BRIDGE is a hop through `java.util.concurrent.Flow.Publisher` between two stream
 * libraries: a stream compiled for its own backend (an Akka engine's stream consumed as an
 * Akka `Source`, a neutral stream run by the neutral interpreter) costs none; a foreign leaf
 * costs one, a foreign native pipe two. Count them with [[counted]] - scoped to the streams
 * compiled or run inside it, so suites running in parallel do not interfere.
 */
object StreamDiagnostics {

  /**
   * Runs `body` with a fresh counter for the streams it COMPILES (e.g. `stream.toSource`) or
   * STARTS on the neutral interpreter (`runToSeq`, `runForeach`, `toPublisher`, `preflight`,
   * ...) - their lazy children included, whenever they compile. Read the count once the run
   * completed:
   * {{{
   * val (source, bridges) = StreamDiagnostics.counted(stream.toSource)
   * Await.result(source.runWith(Sink.seq), 10.seconds)
   * bridges.get shouldBe 0
   * }}}
   * Streams that other threads compile or start - other tests in parallel included - are not
   * counted.
   */
  def counted[T](body: => T): (T, BridgeCount) = {
    val counter = BridgeCounter.scoped()
    val result = BridgeCounter.withCounter(counter)(body)
    (result, new BridgeCount(counter))
  }
}

/** The bridges counted by [[StreamDiagnostics.counted]] - read it after the run. */
final class BridgeCount private[stream] (counter: BridgeCounter) {

  def get: Long = counter.get

  override def toString: String = s"BridgeCount($get)"
}
