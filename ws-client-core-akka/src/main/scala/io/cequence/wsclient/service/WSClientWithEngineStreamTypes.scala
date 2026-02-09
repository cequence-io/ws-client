package io.cequence.wsclient.service

object WSClientWithEngineStreamTypes {

  type WSClientWithOutputStreamEngine =
    WSClientWithEngineBase[WSClientEngine with WSClientOutputStreamExtra]

  type WSClientWithInputStreamEngine =
    WSClientWithEngineInputStreamingBase[WSClientEngine with WSClientInputStreamExtra]
}
