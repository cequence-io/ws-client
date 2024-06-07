package io.cequence.wsclient.domain

import play.api.libs.json.JsonNaming.SnakeCase

trait SnakeCaseEnumValue extends EnumValue {

  override def value: String = SnakeCase(super.value)

}
