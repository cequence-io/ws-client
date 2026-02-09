package io.cequence.wsclient.domain

import akka.stream.scaladsl.Source
import akka.util.ByteString

trait StreamedResponse extends Response {
  def source: Source[ByteString, _]
}
