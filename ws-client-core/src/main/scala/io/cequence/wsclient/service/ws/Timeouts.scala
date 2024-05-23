package io.cequence.wsclient.service.ws

case class Timeouts(
  requestTimeout: Option[Int] = None,
  readTimeout: Option[Int] = None,
  connectTimeout: Option[Int] = None,
  pooledConnectionIdleTimeout: Option[Int] = None
) { self =>
  def isDefined: Boolean =
    requestTimeout.isDefined || readTimeout.isDefined || connectTimeout.isDefined || pooledConnectionIdleTimeout.isDefined

  def toOption: Option[Timeouts] =
    if (isDefined) Some(self) else None
}
