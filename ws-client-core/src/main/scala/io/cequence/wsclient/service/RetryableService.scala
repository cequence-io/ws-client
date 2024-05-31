package io.cequence.wsclient.service

trait RetryableService {
  def isRetryable(t: Throwable): Boolean
}
