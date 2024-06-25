package io.cequence.wsclient.domain

import play.api.libs.json.JsonNaming.SnakeCase

trait EnumValue {

  def value: String = ""

  override def toString: String =
    if (value.nonEmpty) value else getClass.getSimpleName.stripSuffix("$")
}

abstract class NamedEnumValue(override val value: String = "") extends EnumValue

trait SnakeCaseEnumValue extends EnumValue {

  override def value: String = SnakeCase(super.value)
}
