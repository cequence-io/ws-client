package io.cequence.wsclient.domain

class CequenceWSException(
  message: String,
  cause: Throwable
) extends RuntimeException(message, cause) {
  def this(message: String) = this(message, null)
}

class CequenceWSTimeoutException(
  message: String,
  cause: Throwable
) extends CequenceWSException(message, cause) {
  def this(message: String) = this(message, null)
}

class CequenceWSUnknownHostException(
  message: String,
  cause: Throwable
) extends CequenceWSException(message, cause) {
  def this(message: String) = this(message, null)
}

/**
 * A non-acceptable HTTP status, as a structured failure: `statusCode` and `body` (for a
 * streamed call the error body is bounded - at most `EngineSupport.MaxErrorBodyBytes`).
 * Streaming calls fail with it on a non-2xx status, and the default `handleErrorCodes` of
 * non-streamed calls throws it too; services classify it through their own `handleErrorCodes`
 * via `WSClientBase.mapHttpStatusErrors`.
 */
class CequenceWSHttpStatusException(
  message: String,
  val statusCode: Int,
  val body: String
) extends CequenceWSException(message)
