package io.cequence.wsclient

import com.typesafe.config.Config

object ConfigImplicits {
  implicit class ConfigExt(config: Config) {
    def optionalString(configPath: String): Option[String] =
      if (config.hasPath(configPath)) Some(config.getString(configPath)) else None

    def optionalInt(configPath: String): Option[Int] =
      if (config.hasPath(configPath)) Some(config.getInt(configPath)) else None

    def optionalBoolean(configPath: String): Option[Boolean] =
      if (config.hasPath(configPath)) Some(config.getBoolean(configPath)) else None

    def optionalDouble(configPath: String): Option[Double] =
      if (config.hasPath(configPath)) Some(config.getDouble(configPath)) else None

    def optionalLong(configPath: String): Option[Long] =
      if (config.hasPath(configPath)) Some(config.getLong(configPath)) else None

    def optionalDuration(configPath: String): Option[java.time.Duration] =
      if (config.hasPath(configPath)) Some(config.getDuration(configPath)) else None

    def optionalDateTime(configPath: String): Option[java.time.LocalDateTime] =
      if (config.hasPath(configPath))
        Some(java.time.LocalDateTime.parse(config.getString(configPath)))
      else None
  }
}
