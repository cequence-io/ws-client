package io.cequence.wsclient.service

import akka.stream.scaladsl.Framing.FramingException
import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.domain.{
  CequenceWSException,
  CequenceWSFrameTooLongException,
  CequenceWSHttpStatusException,
  CequenceWSTimeoutException,
  CequenceWSUnknownHostException
}
import io.cequence.wsclient.service.ws.ThrowableUtil.hasCause
import org.slf4j.Logger

import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.TimeoutException

/**
 * The failure mapping of the `Source`-typed streamed calls, shared by the Play stream and the
 * pekko-http engines: logs the failure (see `log`) and rethrows it in the Cequence exception
 * taxonomy, labelled with `label` (service + endpoint).
 */
private[wsclient] object StreamFailures {

  /**
   * An oversized frame (`Framing.delimiter` with `allowTruncation`, which fails only for that)
   * as the typed [[io.cequence.wsclient.domain.CequenceWSFrameTooLongException]] - the message
   * `handle` gives a framing failure.
   */
  def frameTooLong(
    label: String,
    maxFrameLength: Int
  ): PartialFunction[Throwable, Throwable] = { case e: FramingException =>
    new CequenceWSFrameTooLongException(
      s"$label: Stream framing problem occurred. ${e.getMessage}.",
      maxFrameLength
    )
  }

  /**
   * Logs a stream failure at ERROR - except a non-2xx status (since 1.2.0 at DEBUG): that one
   * is the caller's to handle (the download helpers turn a 404 into `None`), as the
   * non-streamed calls' status failures are, and it reaches the caller as the exception either
   * way.
   */
  def log(
    logger: Logger,
    message: String,
    failure: Throwable
  ): Unit =
    failure match {
      case _: CequenceWSHttpStatusException => logger.debug(message)
      case _                                => logger.error(message)
    }

  def handle[T](
    label: String,
    logger: Logger
  ): PartialFunction[Throwable, T] = {
    // already mapped (e.g. a non-2xx stream status) - pass through, don't re-wrap
    case e: CequenceWSException =>
      log(logger, e.getMessage, e)
      throw e
    case e: JsonParseException =>
      val message = s"$label: Response is not a JSON. ${e.getMessage}."
      logger.error(message)
      throw new CequenceWSException(message)
    case e: FramingException =>
      val message = s"$label: Stream framing problem occurred. ${e.getMessage}."
      logger.error(message)
      throw new CequenceWSException(message)
    // backends wrap transport failures (pekko-http: a StreamTcpException CAUSED BY the
    // UnknownHostException), so classify on the whole cause chain - as the engines' unary
    // defaultRecoverErrors do
    case e if hasCause(e, classOf[TimeoutException]) =>
      val message = s"$label: Time out. ${e.getMessage}."
      logger.error(message)
      throw new CequenceWSTimeoutException(message, e)
    case e
        if hasCause(e, classOf[UnknownHostException]) ||
          hasCause(e, classOf[UnresolvedAddressException]) =>
      val message = s"$label: Host name cannot be resolved. ${e.getMessage}."
      logger.error(message)
      throw new CequenceWSUnknownHostException(message, e)
    case e: Throwable =>
      val message = s"$label: Fatal problem! ${e.getMessage}."
      logger.error(message)
      throw new CequenceWSException(message, e)
  }
}
