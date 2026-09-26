package io.cequence.wsclient.stream

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, Flow, TimeUnit}

private[wsclient] object BoundedBodyReader {

  /**
   * Reads at most `maxBytes` of `body` as UTF-8 and completes after `timeoutMs` at the latest,
   * with whatever arrived by then. Never fails - an upstream error yields the bytes read so
   * far. The upstream is cancelled as soon as enough bytes arrived or the deadline passed, so
   * neither a huge nor a never-ending body is buffered or waited for.
   */
  def utf8(
    body: Flow.Publisher[ByteBuffer],
    maxBytes: Int,
    timeoutMs: Long
  ): CompletableFuture[String] = {
    val bytes = new ByteArrayOutputStream()
    val result = new CompletableFuture[String]()
    @volatile var subscription: Flow.Subscription = null

    def finish(): Unit = {
      Option(subscription).foreach(_.cancel())
      result.complete(new String(bytes.toByteArray, StandardCharsets.UTF_8))
      ()
    }

    body.subscribe(new Flow.Subscriber[ByteBuffer] {
      override def onSubscribe(s: Flow.Subscription): Unit = {
        subscription = s
        s.request(Long.MaxValue)
      }

      override def onNext(buffer: ByteBuffer): Unit = {
        bytes.synchronized {
          val n = math.min(buffer.remaining(), maxBytes - bytes.size())
          if (n > 0) {
            val chunk = new Array[Byte](n)
            buffer.get(chunk)
            bytes.write(chunk)
          }
        }
        if (bytes.size() >= maxBytes) finish()
      }

      override def onError(t: Throwable): Unit = finish()

      override def onComplete(): Unit = finish()
    })

    CompletableFuture
      .delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
      .execute(new Runnable { override def run(): Unit = finish() })

    result
  }
}
