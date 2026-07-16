package io.cequence.wsclient.domain

/**
 * Per-request, per-site data attached to every call an engine makes - purely request-scoped,
 * carried by the [[SiteBinding]] fed into each engine call and re-evaluatable on every request
 * (see `SiteBinding.requestContextFun`). Client-level settings (timeouts, proxy) live in
 * `io.cequence.wsclient.service.spi.TransportSettings` instead.
 */
case class WsRequestContext(
  /**
   * Auth headers (HTTP headers) to be added to each request.
   */
  authHeaders: Seq[(String, String)] = Nil,

  /**
   * Extra parameters to be added to each request.
   */
  extraParams: Seq[(String, String)] = Nil
)
