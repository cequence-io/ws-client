package io.cequence.wsclient.service.ws

import io.cequence.wsclient.domain.WsRequestContext
import play.api.libs.ws.StandaloneWSRequest

/**
 * Core WS stuff for OpenAI services.
 *
 * @since March
 *   2024
 */
trait WSRequestHelper extends WSRequestHelperBase {

  protected val defaultRequestTimeout: Int = 120 * 1000 // two minutes
  protected val defaultReadoutTimeout: Int = 120 * 1000 // two minutes

  protected val requestContext: WsRequestContext = WsRequestContext(
    None,
    Nil,
    Nil
  )

  override protected def timeouts: Timeouts =
    requestContext.explTimeouts.getOrElse(
      Timeouts(
        requestTimeout = Some(defaultRequestTimeout),
        readTimeout = Some(defaultReadoutTimeout)
      )
    )

  // auth

  override protected[ws] def getWSRequestOptional(
    endPoint: Option[PEP],
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])] = Nil
  ): StandaloneWSRequest#Self = {
    val extraStringParams = requestContext.extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }

    super
      .getWSRequestOptional(
        endPoint,
        endPointParam,
        params ++ extraStringParams
      )
      .addHttpHeaders(requestContext.authHeaders: _*)
  }

  override protected[ws] def getWSRequest(
    endPoint: Option[PEP],
    endPointParam: Option[String],
    params: Seq[(String, Any)] = Nil
  ): StandaloneWSRequest#Self =
    super
      .getWSRequest(
        endPoint,
        endPointParam,
        params ++ requestContext.extraParams
      )
      .addHttpHeaders(requestContext.authHeaders: _*)
}
