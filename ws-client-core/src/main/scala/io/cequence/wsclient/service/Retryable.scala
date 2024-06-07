//package io.cequence.wsclient.service
//
//object Retryable {
//
//  def unapply(
//               t: OpenAIScalaClientException
//             ): Option[OpenAIScalaClientException] = Some(t).filter(apply)
//
//  def apply(t: OpenAIScalaClientException): Boolean = t match {
//    // we retry on these
//    case _: OpenAIScalaClientTimeoutException    => true
//    case _: OpenAIScalaRateLimitException        => true
//    case _: OpenAIScalaServerErrorException      => true
//    case _: OpenAIScalaEngineOverloadedException => true
//
//    // otherwise don't retry
//    case _ => false
//  }
//}
