package io.cequence.wsclient.service

trait HasWSClientEngine[T <: WSClientEngine] {

  protected val engine: T
}
