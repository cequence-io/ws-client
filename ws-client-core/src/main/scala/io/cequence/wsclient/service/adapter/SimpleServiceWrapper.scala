package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper

import io.cequence.wsclient.stream.AsyncCall

import scala.concurrent.Future

object SimpleServiceWrapper {

  def apply[S <: CloseableService](
    service: S
  ): CloseableServiceWrapper[S] =
    new SimpleServiceWrapper(service)

  private final class SimpleServiceWrapper[S <: CloseableService](
    service: S
  ) extends ServiceWrapper[S]
      with CloseableService {

    override protected[adapter] def wrap[T](
      fun: S => Future[T]
    ): Future[T] =
      fun(service)

    override protected[adapter] def wrapCall[T](
      fun: S => AsyncCall[T]
    ): AsyncCall[T] =
      AsyncCall.defer(fun(service))

    override def close(): Unit =
      service.close()
  }
}
