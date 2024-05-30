// Supported versions
val scala212 = "2.12.18"
val scala213 = "2.13.11"
val scala3 = "3.2.2"

ThisBuild / description := "Generic Play WebServices library"

ThisBuild / organization := "io.cequence"
ThisBuild / scalaVersion := scala212
ThisBuild / version := "0.3.0"
ThisBuild / isSnapshot := false

// POM settings for Sonatype
ThisBuild / homepage := Some(
  url("https://github.com/cequence-io/ws-client")
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
    "bburdiliak",
    "Boris Burdiliak",
    "boris.burdiliak@cequence.io",
    url("https://cequence.io")
  ),
  Developer(
    "bnd",
    "Peter Banda",
    "peter.banda@protonmail.com",
    url("https://peterbanda.net")
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

inThisBuild(
  playWsVersion := {
    scalaVersion.value match {
      case "2.12.18" => "2.1.10"
      case "2.13.11" => "2.2.0-M3"
      case "3.2.2" =>
        "2.2.0-M2" // Version "2.2.0-M3" was produced by an unstable release: Scala 3.3.0-RC3
      case _ => "2.1.10"
    }
  }
)

val akkaHttpVersion = "10.5.0-M1" // TODO: migrate to 10.5.1

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

lazy val playDependencies = Def.setting {
  Seq(
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsVersion.value,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion.value
  )
}

lazy val `ws-client-core` =
  (project in file("ws-client-core")).settings(
    name := "ws-client-core",
    libraryDependencies ++= akkaStreamLibs(scalaVersion.value),
    libraryDependencies += "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion.value,
    publish / skip := false
  )

lazy val `ws-client-play` =
  (project in file("ws-client-play"))
    .settings(
      name := "ws-client-play",
      libraryDependencies ++= akkaStreamLibs(scalaVersion.value),
      libraryDependencies ++= playDependencies.value,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`)
    .aggregate(`ws-client-core`)

lazy val `ws-client-stream` =
  (project in file("ws-client-stream"))
    .settings(
      name := "ws-client-stream",
      libraryDependencies ++= akkaStreamLibs(scalaVersion.value),
      libraryDependencies ++= playDependencies.value,
      libraryDependencies += "com.typesafe.akka" %% "akka-http" % akkaHttpVersion, // JSON WS Streaming
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-play`)
    .aggregate(`ws-client-core`, `ws-client-play`)
