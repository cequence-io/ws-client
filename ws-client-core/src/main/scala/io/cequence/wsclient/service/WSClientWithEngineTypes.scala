package io.cequence.wsclient.service

object WSClientWithEngineTypes {

  type WSClientWithStreamEngine =
    WSClientWithEngineBase[WSClientEngine with WSClientEngineStreamExtra]

  type WSClientWithEngine = WSClientWithEngineBase[WSClientEngine]
}
