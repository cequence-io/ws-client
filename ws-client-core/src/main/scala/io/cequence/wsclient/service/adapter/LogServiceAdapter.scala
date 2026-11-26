package io.cequence.wsclient.service.adapter

import io.cequence.wsclient.StackWalkerUtil
import io.cequence.wsclient.service.CloseableService
import io.cequence.wsclient.stream.AsyncCall

import java.util.Optional
import java.util.function.Predicate
import scala.concurrent.Future

private final class LogServiceAdapter[+S <: CloseableService](
  underlying: S,
  serviceName: String,
  log: String => Unit
) extends ServiceWrapper[S]
    with FunctionNameHelper
    with CloseableService {

  override protected[adapter] def wrap[T](
    fun: S => Future[T]
  ): Future[T] = {
    log(s"${serviceName} - calling '${getFunctionName()}'")
    fun(underlying)
  }

  // the name is read here, where the stack still has the caller's method (the twin `fooCall`
  // is logged as `foo`); the line is written per run
  override protected[adapter] def wrapCall[T](
    fun: S => AsyncCall[T]
  ): AsyncCall[T] = {
    val twinName = getFunctionName()
    val functionName =
      if (twinName.length > CallSuffix.length && twinName.endsWith(CallSuffix))
        twinName.dropRight(CallSuffix.length)
      else
        twinName

    AsyncCall.defer {
      log(s"${serviceName} - calling '${functionName}'")
      fun(underlying)
    }
  }

  private val CallSuffix = "Call"

  override def close(): Unit =
    underlying.close()
}

trait FunctionNameHelper {

  private val ignoreFunNames = Seq("wrap", "anonfun", "getFunctionName")

  protected def getFunctionName(): String = {
    // need to use StackWalker to get the caller function name
    val predicate =
      Optional.of[Predicate[String]]((t: String) => ignoreFunNames.forall(!t.contains(_)))

    StackWalkerUtil.functionName(2, predicate).orElse("N/A")
  }
}
