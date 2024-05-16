package io.cequence.openaiscala.domain

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
