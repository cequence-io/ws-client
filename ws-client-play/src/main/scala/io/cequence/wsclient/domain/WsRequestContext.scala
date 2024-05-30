package io.cequence.wsclient.domain

import io.cequence.wsclient.service.ws.Timeouts

case class WsRequestContext(
  explTimeouts: Option[Timeouts] = None,

  /**
   * Auth headers (HTTP headers) to be added to each request.
   */
  authHeaders: Seq[(String, String)] = Nil,

  /**
   * Extra parameters to be added to each request.
   */
  extraParams: Seq[(String, String)] = Nil
)
