package io.cequence.wsclient.service

import io.cequence.wsclient.domain.WsRequestContext

// TODO: maybe rename to WSClientBackend?
trait WSClientEngine extends WSClient {

  protected val coreUrl: String

  protected val requestContext: WsRequestContext = WsRequestContext()

  override type PEP = String
  override type PT = String
}
