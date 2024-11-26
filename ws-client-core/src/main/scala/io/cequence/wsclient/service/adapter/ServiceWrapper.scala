package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper

import scala.concurrent.Future

trait ServiceWrapper[+S] {

  protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T]
}

trait DelegatedCloseableServiceWrapper[
  +S <: CloseableService,
  +W <: CloseableServiceWrapper[S]
] extends ServiceWrapper[S]
    with CloseableService {

  protected def delegate: W

  protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T] = delegate.wrap(fun)

  override def close(): Unit =
    delegate.close()
}

object ServiceWrapperTypes {
  type CloseableServiceWrapper[+S] = ServiceWrapper[S] with CloseableService
}
