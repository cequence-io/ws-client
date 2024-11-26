package io.cequence.wsclient.service.adapter

import akka.stream.Materializer
import com.typesafe.scalalogging.Logger
import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.service.adapter.ServiceWrapperTypes.CloseableServiceWrapper
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

trait ServiceBaseAdapters[S <: CloseableService] {

  private val logger = Logger(LoggerFactory.getLogger(this.getClass))

  def roundRobin(
    underlyings: S*
  ): S =
    wrapAndDelegate(new RoundRobinAdapter(underlyings))

  def randomOrder(
    underlyings: S*
  ): S =
    wrapAndDelegate(new RandomOrderAdapter(underlyings))

  def parallelTakeFirst(
    underlyings: S*
  )(
    implicit materializer: Materializer
  ): S =
    wrapAndDelegate(new ParallelTakeFirstAdapter(underlyings))

  def log(
    underlying: S,
    serviceName: String,
    log: String => Unit = logger.info(_)
  ): S =
    wrapAndDelegate(new LogServiceAdapter(underlying, serviceName, log))

  def preAction(
    underlying: S,
    action: () => Future[Unit]
  )(
    implicit ec: ExecutionContext
  ): S =
    wrapAndDelegate(new PreServiceAdapter(underlying, action))

  protected def wrapAndDelegate(
    delegate: CloseableServiceWrapper[S]
  ): S
}
