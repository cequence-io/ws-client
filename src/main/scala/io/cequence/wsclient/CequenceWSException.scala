package io.cequence.wsclient

class CequenceWSException(
  message: String,
  cause: Throwable
) extends RuntimeException(message, cause) {
  def this(message: String) = this(message, null)
}
