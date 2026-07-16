package io.cequence.wsclient.service

object WSClientWithEngineStreamTypes {

  type WSClientWithOutputStreamEngine =
    WSClientWithEngineBase[WSClientEngine with WSClientOutputStreamExtraAkka]

  type WSClientWithInputStreamEngine =
    WSClientWithEngineInputStreamingBase[WSClientEngine with WSClientInputStreamExtraAkka]
}
