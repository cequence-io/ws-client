package io.cequence.wsclient.service

import akka.stream.scaladsl.Framing.FramingException
import com.fasterxml.jackson.core.JsonParseException
import io.cequence.wsclient.domain.{
  CequenceWSException,
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
 * pekko-http engines: logs the failure at ERROR and rethrows it in the Cequence exception
 * taxonomy, labelled with `label` (service + endpoint).
 */
private[wsclient] object StreamFailures {

  def handle[T](
    label: String,
    logger: Logger
  ): PartialFunction[Throwable, T] = {
    // already mapped (e.g. a non-2xx stream status) - pass through, don't re-wrap
    case e: CequenceWSException =>
      logger.error(e.getMessage)
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
