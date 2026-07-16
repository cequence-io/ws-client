package io.cequence.wsclient.service.ws

private[wsclient] object ThrowableUtil {

  /**
   * True iff `e` or any exception in its cause chain (bounded to 10 hops to guard against
   * cycles) is an instance of `clazz`. Backends wrap transport failures differently (sttp in
   * `SttpClientException`, the JDK client in `CompletionException`/`ConnectException`), so
   * error recovery has to inspect the whole chain.
   */
  def hasCause(
    e: Throwable,
    clazz: Class[_ <: Throwable]
  ): Boolean =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10).exists(clazz.isInstance)
}
