package io.cequence.wsclient.service.ws

import akka.stream.Materializer
import play.api.libs.json.{
  Format,
  JsArray,
  JsBoolean,
  JsDefined,
  JsNull,
  JsNumber,
  JsObject,
  JsReadable,
  JsString,
  JsUndefined,
  Json
}
import io.cequence.wsclient.domain.WsRequestContext
import io.cequence.wsclient.service.ws.PlayWSClientEngine
import io.cequence.wsclient.service.{CloseableService, HasWSClientEngine, WSClientEngine}

import scala.concurrent.{ExecutionContext, Future}

trait CustomWSCallService extends CloseableService {

  def execPOSTForBody[T: Format](
    body: T,
    extraHeaders: Seq[(String, String)] = Nil
  ): Future[(Int, String)]
}

private class CustomWSCallServiceImpl(
  coreUrl: String,
  authHeaders: Seq[(String, String)]
)(
  implicit val materializer: Materializer,
  val ec: ExecutionContext
) extends HasWSClientEngine[WSClientEngine]
    with CustomWSCallService {

  override protected val engine: WSClientEngine = PlayWSClientEngine(
    coreUrl,
    WsRequestContext(authHeaders = authHeaders)
  )

  override def execPOSTForBody[T: Format](
    body: T,
    extraHeaders: Seq[(String, String)]
  ): Future[(Int, String)] = {
    engine
      .execPOSTBodyRich(
        "",
        None,
        Nil,
        Json.toJson(body),
        acceptableStatusCodes = Nil,
        extraHeaders = extraHeaders
      )
      .map { response =>
        val status = response.status
        (status.code, status.message)
      }
  }

  override def close(): Unit = engine.close()
}

object CustomWSCallService {
  def apply(
    coreUrl: String,
    authHeaders: Seq[(String, String)]
  )(
    implicit materializer: Materializer,
    ec: ExecutionContext
  ): CustomWSCallService = {
    val finalURL = if (coreUrl.startsWith("http")) coreUrl else s"http://${coreUrl}"
    new CustomWSCallServiceImpl(finalURL, authHeaders)
  }
}
