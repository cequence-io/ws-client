package io.cequence.wsclient.stream

/**
 * Diagnostics of [[AsyncCall]] (since 1.3.0), for tests and debugging.
 *
 * A BRIDGE is a hop between a backend's effect and a `Future`: a call compiled for its own
 * backend (the zio-http engine's call as a ZIO effect, a `Future`-based engine's call run as a
 * `Future`) costs none; each foreign leaf - a `Future` leaf compiled for ZIO
 * (`ZIO.fromFuture`), a native leaf run by another interpreter (e.g. a ZIO leaf run as a
 * `Future`) - costs one.
 */
object CallDiagnostics {

  /**
   * Runs `body` with a fresh counter for the calls it COMPILES (e.g. `call.toZIO`) or STARTS
   * on the `Future` interpreter (`toFuture`, `run`) - the calls their user functions return
   * included, whenever they run. Read the count once the run completed:
   * {{{
   * val (future, bridges) = CallDiagnostics.counted(call.toFuture)
   * Await.result(future, 10.seconds)
   * bridges.get shouldBe 0
   * }}}
   * Calls that other threads compile or start - other tests in parallel included - are not
   * counted. Streams compiled or run inside `body` count into the same counter.
   */
  def counted[T](body: => T): (T, BridgeCount) = StreamDiagnostics.counted(body)
}
