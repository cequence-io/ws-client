// Supported versions
val scala212 = "2.12.18"
val scala213 = "2.13.11"
val scala3 = "3.2.2"

ThisBuild / description := "Generic Play WebServices library"

ThisBuild / organization := "io.cequence"
ThisBuild / scalaVersion := scala212
ThisBuild / version := "0.1"
ThisBuild / isSnapshot := false

// POM settings for Sonatype
ThisBuild / homepage := Some(
  url("https://github.com/cequence-io/openai-scala-client")
)

ThisBuild / sonatypeProfileName := "io.cequence"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/cequence-io/ws-client"),
    "scm:git@github.com:cequence-io/ws-client.git"
  )
)

ThisBuild / developers := List(
  Developer(
    "bbu",
    "Boris Burdiliak",
    "boris.burdiliak@gmail.com",
    url("https://cequence.io")
  )
)

ThisBuild / licenses += "MIT" -> url("https://opensource.org/licenses/MIT")

ThisBuild / publishMavenStyle := true

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"

ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

ThisBuild / publishTo := sonatypePublishToBundle.value

inThisBuild(
  List(
    scalacOptions += "-Ywarn-unused",
    //    scalaVersion := "2.12.15",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)

lazy val playWsVersion = settingKey[String]("Play WS version to use")

playWsVersion := {
  scalaVersion.value match {
    case "2.12.18" => "2.1.10"
    case "2.13.11" => "2.2.0-M3"
    case "3.2.2" =>
      "2.2.0-M2" // Version "2.2.0-M3" was produced by an unstable release: Scala 3.3.0-RC3
    case _ => "2.1.10"
  }
}

def akkaStreamLibs(scalaVersion: String): Seq[ModuleID] = {
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, 12)) =>
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.1"
      )
    case Some((2, 13)) =>
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.20"
      )
    case Some((3, _)) =>
      // because of the conflicting cross-version suffixes 2.13 vs 3
      Seq(
        "com.typesafe.akka" % "akka-stream_2.13" % "2.6.20" exclude ("com.typesafe", "ssl-config-core_2.13"),
        "com.typesafe" %% "ssl-config-core" % "0.6.1"
      )
    case _ =>
      throw new Exception("Unsupported scala version")
  }
}

libraryDependencies ++= akkaStreamLibs(scalaVersion.value)
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsVersion.value,
  "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion.value
)
