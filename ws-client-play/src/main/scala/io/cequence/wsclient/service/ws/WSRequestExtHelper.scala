package io.cequence.wsclient.service.ws

import play.api.libs.ws.StandaloneWSRequest

/**
 * Core WS stuff for OpenAI services.
 *
 * @since March
 *   2024
 */
trait WSRequestExtHelper extends WSRequestHelper {

  protected val defaultRequestTimeout: Int = 120 * 1000 // two minutes
  protected val defaultReadoutTimeout: Int = 120 * 1000 // two minutes

  protected val explTimeouts: Option[Timeouts] = None

  /**
   * Auth headers (HTTP headers) to be added to each request.
   */
  protected val authHeaders: Seq[(String, String)] = Nil

  /**
   * Extra parameters to be added to each request.
   */
  protected val extraParams: Seq[(String, String)] = Nil

  override protected def timeouts: Timeouts =
    explTimeouts.getOrElse(
      Timeouts(
        requestTimeout = Some(defaultRequestTimeout),
        readTimeout = Some(defaultReadoutTimeout)
      )
    )

  // auth

  override protected def getWSRequestOptional(
    endPoint: Option[PEP],
    endPointParam: Option[String],
    params: Seq[(String, Option[Any])] = Nil
  ): StandaloneWSRequest#Self = {
    val extraStringParams = extraParams.map { case (tag, value) =>
      (tag, Some(value))
    }

    super
      .getWSRequestOptional(
        endPoint,
        endPointParam,
        params ++ extraStringParams
      )
      .addHttpHeaders(authHeaders: _*)
  }

  override protected def getWSRequest(
    endPoint: Option[PEP],
    endPointParam: Option[String],
    params: Seq[(String, Any)] = Nil
  ): StandaloneWSRequest#Self =
    super
      .getWSRequest(
        endPoint,
        endPointParam,
        params ++ extraParams
      )
      .addHttpHeaders(authHeaders: _*)
}
