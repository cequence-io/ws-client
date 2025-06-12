package io.cequence.wsclient

import io.cequence.wsclient.domain.CequenceWSException
import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json._
import scala.collection.mutable.{Seq => MutableSeq}
import scala.collection.immutable.{Seq => ImmutableSeq}
import scala.collection.immutable.{Map => ImmutableMap}

import java.util.Date
import java.{util => ju}

object JsonUtil {

  implicit class JsonOps(val json: JsValue) {
    def asSafe[T](
      implicit fjs: Reads[T]
    ): T =
      try {
        json.validate[T] match {
          case JsSuccess(value, _) => value
          case JsError(errors) =>
            val errorString = errors.map { case (path, pathErrors) =>
              s"JSON at path '${path}' contains the following errors: ${pathErrors.map(_.message).mkString(";")}"
            }.mkString("\n")
            throw new CequenceWSException(
              s"Unexpected JSON:\n'${Json.prettyPrint(json)}'. Cannot be parsed due to: $errorString"
            )
        }
      } catch {
        case e: Exception =>
          throw new CequenceWSException(
            s"Error thrown while processing a JSON '${Json.prettyPrint(json)}'. Cause: ${e.getMessage}"
          )
      }

    def asSafeArray[T](
      implicit fjs: Reads[T]
    ): Seq[T] =
      json.asSafe[JsArray].value.map(_.asSafe[T]).toSeq
  }

  object SecDateFormat extends Format[ju.Date] {
    override def reads(json: JsValue): JsResult[Date] = {
      json match {
        case JsString(s) =>
          try {
            val millis = s.toLong * 1000
            JsSuccess(new ju.Date(millis))
          } catch {
            case _: NumberFormatException => JsError(s"$s is not a number.")
          }

        case JsNumber(n) =>
          val millis = (n * 1000).toLong
          JsSuccess(new ju.Date(millis))

        case _ => JsError(s"String or number expected but got '$json'.")
      }
    }

    override def writes(o: Date): JsValue =
      JsNumber(Math.round(o.getTime.toDouble / 1000))
  }

  def toJson(value: Any): JsValue =
    if (value == null)
      JsNull
    else
      value match {
        case x: JsValue => x // nothing to do

        case Some(s) => toJson(s)
        case None    => JsNull

        case x: String => JsString(x)

        case x: BigDecimal => JsNumber(x)
        case x: Int        => JsNumber(BigDecimal.valueOf(x.toLong))
        case x: Integer    => JsNumber(BigDecimal.valueOf(x.toLong))
        case x: Long       => JsNumber(BigDecimal.valueOf(x))
        case x: Double     => JsNumber(BigDecimal.valueOf(x))
        case x: Float      => JsNumber(BigDecimal.valueOf(x.toDouble))
        case n: Number     => JsNumber(n.doubleValue())

        case x: Boolean => JsBoolean(x)

        case x: ju.Date => Json.toJson(x)

        case x: Array[_]          => JsArray(x.map(toJson))
        case x: collection.Seq[_] => JsArray(x.map(toJson))
        case x: ImmutableSeq[_]   => JsArray(x.map(toJson))
        case x: MutableSeq[_]     => JsArray(x.map(toJson))

        case x: collection.Map[String, _] =>
          val jsonValues = x.map { case (fieldName, value) =>
            (fieldName, toJson(value))
          }
          JsObject(jsonValues)

        case map: ImmutableMap[String, _] =>
          val fields = map.map { case (fieldName, value) => (fieldName, toJson(value)) }
          JsObject(fields)

        case _ =>
          throw new IllegalArgumentException(
            s"No JSON format found for the class ${value.getClass.getName}."
          )
      }

  def toValue(jsValue: JsValue): Option[Any] =
    jsValue match {
      case JsNull           => None
      case JsString(value)  => Some(value)
      case JsNumber(value)  => Some(value)
      case JsBoolean(value) => Some(value)
      case JsArray(value) =>
        val seq = value.toSeq.map(toValue)
        Some(seq)

      case jsObject: JsObject =>
        Some(toValueMap(jsObject))
    }

  def toValueMap(jsObject: JsObject): Map[String, Option[Any]] =
    jsObject.value.map { case (fieldName, jsValue) =>
      (fieldName, toValue(jsValue))
    }.toMap

  def toValueWithNull(
    nullValue: Any
  )(
    jsValue: JsValue
  ): Any =
    jsValue match {
      case JsNull             => nullValue
      case JsString(value)    => value
      case JsNumber(value)    => value
      case JsBoolean(value)   => value
      case JsArray(value)     => value.toSeq.map(toValueWithNull(nullValue))
      case jsObject: JsObject => jsObjectToMapWithNull(nullValue)(jsObject)
      case _ => throw new IllegalArgumentException("Unknown JSON type")
    }

  def jsObjectToMapWithNull(nullValue: Any)(jsObject: JsObject): Map[String, Any] =
    jsObject.value.mapValues(toValueWithNull(nullValue)).toMap

  object StringDoubleMapFormat extends Format[Map[String, Double]] {
    override def reads(json: JsValue): JsResult[Map[String, Double]] = {
      val resultJsons =
        json.asSafe[JsObject].fields.map { case (fieldName, jsValue) =>
          (fieldName, jsValue.as[Double])
        }
      JsSuccess(resultJsons.toMap)
    }

    override def writes(o: Map[String, Double]): JsValue = {
      val fields = o.map { case (fieldName, value) =>
        (fieldName, JsNumber(value))
      }
      JsObject(fields)
    }
  }

  object StringStringMapFormat extends Format[Map[String, String]] {
    override def reads(json: JsValue): JsResult[Map[String, String]] = {
      val resultJsons =
        json.asSafe[JsObject].fields.map { case (fieldName, jsValue) =>
          (fieldName, jsValue.as[String])
        }
      JsSuccess(resultJsons.toMap)
    }

    override def writes(o: Map[String, String]): JsValue = {
      val fields = o.map { case (fieldName, value) =>
        (fieldName, JsString(value))
      }
      JsObject(fields)
    }
  }

  object StringAnyMapFormat extends Format[Map[String, Any]] {
    override def reads(json: JsValue): JsResult[Map[String, Any]] = {
      json.validate[JsObject].map(jsObjectToMapWithNull(nullValue = ""))
    }

    override def writes(o: Map[String, Any]): JsValue = {
      val fields = o.map { case (fieldName, value) =>
        (fieldName, toJson(value))
      }
      JsObject(fields)
    }
  }

  private class EitherFormat[L, R](
    leftFormat: Format[L],
    rightFormat: Format[R]
  ) extends Format[Either[L, R]] {

    override def reads(json: JsValue): JsResult[Either[L, R]] = {
      val left = leftFormat.reads(json)
      val right = rightFormat.reads(json)

      if (left.isSuccess) {
        left.map(Left(_))
      } else if (right.isSuccess) {
        right.map(Right(_))
      } else {
        JsError(s"Unable to read Either type from JSON $json")
      }
    }

    override def writes(o: Either[L, R]): JsValue =
      o match {
        case Left(value)  => leftFormat.writes(value)
        case Right(value) => rightFormat.writes(value)
      }
  }

  def eitherFormat[L: Format, R: Format]: Format[Either[L, R]] = {
    val leftFormat = implicitly[Format[L]]
    val rightFormat = implicitly[Format[R]]

    new EitherFormat[L, R](leftFormat, rightFormat)
  }

  private def enumFormatAux[T](values: T*)(mapper: T => String): Format[T] = {
    val valueMap = values.map(v => mapper(v) -> v).toMap

    val reads: Reads[T] = Reads {
      case JsString(value) =>
        valueMap.get(value.trim) match {
          case Some(v) => JsSuccess(v)
          case None    => JsError(s"'$value' is not a valid enum value.")
        }
      case _ => JsError("String value expected")
    }

    val writes: Writes[T] = Writes((v: T) => JsString(mapper(v)))

    Format(reads, writes)
  }

  def enumFormat[T](values: T*): Format[T] =
    enumFormatAux(values: _*)(_.toString)

  def snakeEnumFormat[T](values: T*): Format[T] =
    enumFormatAux(values: _*)(v => SnakeCase(v.toString))

  def jsonBodyParams[T](
    params: (T, Option[Any])*
  ): Seq[(T, Option[JsValue])] =
    params.map { case (paramName, value) => (paramName, value.map(toJson)) }

  def jsonBodyParams[T: Format](
    spec: T
  ): Seq[(String, Option[JsValue])] = {
    val json = Json.toJson(spec)
    json.as[JsObject].value.toSeq.map { case (key, value) =>
      val optionValue = value match {
        case JsNull => None
        case _      => Some(value)
      }
      (key, optionValue)
    }
  }
}
