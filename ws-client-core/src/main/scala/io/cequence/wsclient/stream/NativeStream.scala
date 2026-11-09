package io.cequence.wsclient.stream

import java.util.concurrent.Flow

/**
 * A stream kept in its producer's native representation (e.g. an Akka `Source`, an fs2 or ZIO
 * stream) - see [[AsyncStream.fromNative]]. The built-in backends (Akka / Pekko, fs2, ZIO)
 * recognize their own leaves and run them directly; every other consumer uses the fallback
 * publisher (one bridge).
 *
 * Public, so third parties can wrap their own stream types as leaves - consumed through
 * `openPublisher` (a native compiler for a new backend is not a public extension point).
 * Implementations must be immutable blueprints.
 */
abstract class NativeStream[+A] {

  /**
   * The fallback for a consumer outside the native backend: a FRESH, cold run per call,
   * executed on the producer's own runtime (e.g. the engine's materializer).
   */
  def openPublisher(): Flow.Publisher[_ <: A]

  /** The backend's name - diagnostics only (e.g. in [[AsyncStream.describe]]). */
  def backend: String
}

/**
 * A stream transformation kept in its producer's native representation (e.g. an Akka `Flow`) -
 * see [[AsyncPipe.fromNative]]. Its own backend applies it natively; every other consumer runs
 * it through `transform` (two bridges: into the native world and back).
 */
abstract class NativePipe[-A, +B] {

  /** Applies the transformation to `upstream`; a fresh, cold run per subscription. */
  def transform(upstream: Flow.Publisher[_ <: A]): Flow.Publisher[_ <: B]

  /** The backend's name - diagnostics only. */
  def backend: String
}
