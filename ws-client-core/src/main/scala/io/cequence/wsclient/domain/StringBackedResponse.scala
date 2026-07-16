package io.cequence.wsclient.domain

import play.api.libs.json.{JsValue, Json}

/**
 * A [[Response]] backed by an already-materialized body string - reusable by any backend that
 * does not need streaming access to the raw response (JDK HttpClient, sttp, pekko-http, ...).
 */
case class StringBackedResponse(
  string: String,
  serviceNameForLogging: String = "",
  endpointForLogging: Option[String] = None
) extends Response {

  override lazy val json: JsValue =
    try
      Json.parse(string)
    catch {
      case e: Exception =>
        throw new CequenceWSException(
          s"$serviceNameForLogging${endpointForLogging.map("." + _).getOrElse("")}: Response is not a JSON: ${e.getMessage}. Response: ${string
              .take(500)}"
        )
    }
}

/**
 * A plain [[RichResponse]] carrier - reusable by non-Play backends.
 */
case class SimpleRichResponse(
  response: Option[Response],
  status: StatusData,
  headers: Map[String, Seq[String]]
) extends RichResponse
