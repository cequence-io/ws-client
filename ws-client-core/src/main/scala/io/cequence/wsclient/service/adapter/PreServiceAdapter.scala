package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService

import scala.concurrent.{ExecutionContext, Future}

private final class PreServiceAdapter[+S <: CloseableService](
  underlying: S,
  action: () => Future[Unit]
)(
  implicit ec: ExecutionContext
) extends ServiceWrapper[S]
    with FunctionNameHelper
    with CloseableService {

  override protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T] =
    action().flatMap(_ => fun(underlying))

  override def close(): Unit =
    underlying.close()
}
