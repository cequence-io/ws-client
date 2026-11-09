package io.cequence.wsclient.service

object WSClientWithEngineTypes {

  type WSClientWithEngine = WSClientWithEngineBase[WSClientEngine]

  /** A service with backend-neutral streaming and downloads (since 1.2.0). */
  type WSClientWithAsyncStreamEngine =
    WSClientWithEngineAsyncStreamingBase[WSClientEngine with WSClientOutputStreamCore]
}
