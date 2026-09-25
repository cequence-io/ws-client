package zio.http.netty

import io.netty.util.concurrent.{DefaultThreadFactory, ThreadPerTaskExecutor}
import zio.http.ClientDriver
import zio.http.netty.client.NettyClientDriver
import zio.{Trace, ZIO, ZLayer}

/**
 * ws-client: a zio-http client driver whose Netty event loops run on DAEMON threads, so an
 * engine that is never closed cannot block JVM exit (zio-http's stock driver uses Netty's
 * default, non-daemon threads).
 *
 * zio-http offers no public hook for the event loops' threads, so this lives in zio-http's own
 * package to reach the package-private `NettyClientDriver` constructor - it depends on
 * zio-http internals. `ZioHttpWSClientEngine` falls back to the stock driver (with a warning)
 * if they ever change incompatibly.
 */
object WsClientDaemonNettyDriver {

  def layer(
    config: NettyConfig
  )(
    implicit trace: Trace
  ): ZLayer[Any, Nothing, ClientDriver] =
    ZLayer.scoped {
      for {
        eventLoopGroup <- EventLoopGroups.nio(
          config,
          new ThreadPerTaskExecutor(new DefaultThreadFactory("ws-client-zio-http", true))
        )
        channelFactory <- ChannelFactories.Client.nio
        runtime <- ZIO.runtime[Any]
      } yield NettyClientDriver(channelFactory, eventLoopGroup, new NettyRuntime(runtime))
    }
}
