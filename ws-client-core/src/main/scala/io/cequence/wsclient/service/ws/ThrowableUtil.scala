package io.cequence.wsclient.service.ws

private[wsclient] object ThrowableUtil {

  /**
   * `e` followed by its cause chain, bounded to 10 hops to guard against cycles.
   */
  def causeChain(e: Throwable): Iterator[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10)

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
    causeChain(e).exists(clazz.isInstance)

  /**
   * A description of `e` for a failure message: the first non-blank message in its cause
   * chain, else the class names of the chain. java.net.http throws without messages - a
   * refused connection is a `ConnectException` caused by a `ClosedChannelException`, a DNS
   * failure one caused by an `UnresolvedAddressException`, none of them with a message - so a
   * bare `getMessage` reads "null" there.
   */
  def describe(e: Throwable): String =
    causeChain(e)
      .map(_.getMessage)
      .find(message => message != null && message.trim.nonEmpty)
      .getOrElse(causeChain(e).map(_.getClass.getSimpleName).mkString(" caused by "))
}
