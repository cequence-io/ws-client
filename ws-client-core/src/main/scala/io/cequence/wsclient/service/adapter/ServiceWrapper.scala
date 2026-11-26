package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper

import io.cequence.wsclient.stream.AsyncCall

import scala.concurrent.Future

trait ServiceWrapper[+S] {

  protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T]

  /**
   * The same for a call (since 1.3.0): `fun` is applied to the wrapped service PER RUN of the
   * returned call, so a service behind this wrapper keeps its calls native (a ZIO engine's
   * call stays a ZIO effect, interrupted with its fiber). The default is a `Future` leaf over
   * [[wrap]] - one bridge on ZIO, and interrupting it does not abort the request - so a
   * wrapper written before 1.3.0 keeps working unchanged; the wrappers of this package
   * override it natively. The default runs [[wrap]] lazily, on the run's execution context: a
   * wrapper that reads the caller's stack (the function name `LogServiceAdapter` logs) must
   * override `wrapCall` and read it where `wrapCall` is invoked.
   */
  protected[adapter] def wrapCall[T](
    fun: S => AsyncCall[T]
  ): AsyncCall[T] =
    AsyncCall.fromFuture(implicit ec => wrap(service => fun(service).toFuture))
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

  protected[adapter] override def wrapCall[T](
    fun: S => AsyncCall[T]
  ): AsyncCall[T] = delegate.wrapCall(fun)

  override def close(): Unit =
    delegate.close()
}

object ServiceWrapperTypes {
  type CloseableServiceWrapper[+S] = ServiceWrapper[S] with CloseableService
}
