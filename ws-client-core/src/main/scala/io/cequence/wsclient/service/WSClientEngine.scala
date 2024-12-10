package io.cequence.wsclient.service

import io.cequence.wsclient.domain.{CequenceWSException, WsRequestContext}
import play.api.libs.json.{JsObject, JsValue}

// TODO: maybe rename to WSClientBackend?
trait WSClientEngine extends WSClient {

  protected val coreUrl: String

  protected[service] def requestContext: WsRequestContext = WsRequestContext()

  override type PEP = String
  override type PT = String

  protected[service] def createURL(
    endpoint: Option[String],
    value: Option[String] = None
  ): String = {
    val endpointString = endpoint.getOrElse("")
    val valueString = value.map("/" + _).getOrElse("")
    val slash =
      if (coreUrl.endsWith("/") || (endpointString.isEmpty && valueString.isEmpty)) "" else "/"

    coreUrl + slash + endpointString + valueString
  }

  protected def toOptionalParams(
    params: Seq[(String, Any)]
  ): Seq[(String, Some[Any])] =
    params.map { case (a, b) => (a, Some(b)) }

  protected[service] def toJsBodyObject(
    bodyParams: Seq[(String, Option[JsValue])]
  ): JsObject = {
    val fields = bodyParams.collect { case (fieldName, Some(jsValue)) =>
      if (fieldName.trim.nonEmpty)
        Seq((fieldName, jsValue))
      else
        jsValue match {
          case JsObject(fields) => fields.toSeq
          case _ => throw new CequenceWSException("Empty param name.")
        }
    }.flatten

    JsObject(fields)
  }
}
