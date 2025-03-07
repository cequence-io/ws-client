import sbt.Keys.scalaVersion

// Supported versions
val scala212 = "2.12.18"
val scala213 = "2.13.11"
val scala32 = "3.2.2"

ThisBuild / description := "Generic WebServices library currently only with Play WS impl./backend"

ThisBuild / organization := "io.cequence"
ThisBuild / scalaVersion := scala32
ThisBuild / version := "0.7.0"
ThisBuild / isSnapshot := false
ThisBuild / crossScalaVersions := List(scala212, scala213, scala32)

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

// JSON
lazy val playJsonVersion = settingKey[String]("Play JSON version to use")

inThisBuild(
  playJsonVersion := {
    scalaVersion.value match {
      case "2.12.18" => "2.8.2"
      case "2.13.11" => "2.10.0"
      case "3.2.2"   => "2.10.0-RC6" // -RC6
      case _         => "2.8.2"
    }
  }
)

// Akka
lazy val akkaStreamLibs = Def.setting {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.1"
      )
    case Some((2, 13)) =>
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.20"
      )
    case Some((3, 2)) =>
      // because of the conflicting cross-version suffixes 2.13 vs 3
      Seq(
//        "com.typesafe.akka" %% "akka-stream" % "2.6.20"
        "com.typesafe.akka" % "akka-stream_2.13" % "2.6.20" exclude ("com.typesafe", "ssl-config-core_2.13"),
        "com.typesafe" %% "ssl-config-core" % "0.6.1"
      )
    case _ =>
      throw new Exception("Unsupported scala version")
  }
}

val loggingLibs = Def.setting {
  Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "ch.qos.logback" % "logback-classic" % "1.4.14" // requires JDK11, in order to use JDK8 switch to 1.3.5
  )
}

val akkaHttpVersion = "10.5.1" //"10.5.0-M1"

// Play WS

def typesafePlayWS(version: String) = Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % version,
  "com.typesafe.play" %% "play-ws-standalone-json" % version
//  "com.typesafe.play" % "shaded-asynchttpclient" % version,
//  "io.netty" % "netty-tcnative-boringssl-static" % "2.0.69.Final"
)

def orgPlayWS(version: String) = Seq(
  "org.playframework" %% "play-ahc-ws-standalone" % version,
  "org.playframework" %% "play-ws-standalone-json" % version
)

lazy val playWsDependencies = Def.setting {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      // play json - 2.8.2
      typesafePlayWS("2.1.11")

    case Some((2, 13)) =>
      // play json - 2.10.0
      typesafePlayWS("2.2.0")

    case Some((3, 2)) =>
      // Version "2.2.0-M3" was produced by an unstable release: Scala 3.3.0-RC3 - // play json - 2.10.0-RC6
      typesafePlayWS("2.2.0-M2")

    case Some((3, 3)) =>
      // needs some work because of the akka -> pekko migration (https://pekko.apache.org/docs/pekko/current/project/migration-guides.html)
      orgPlayWS("3.0.0")

    // failover to the latest version
    case _ =>
      orgPlayWS("3.0.0")
  }
}

lazy val `ws-client-core` =
  (project in file("ws-client-core")).settings(
    name := "ws-client-core",
    libraryDependencies ++= akkaStreamLibs.value,
    libraryDependencies += "com.typesafe.play" %% "play-json" % playJsonVersion.value,
    libraryDependencies ++= loggingLibs.value,
    publish / skip := false
  )

lazy val `json-repair` =
  (project in file("json-repair")).settings(
    name := "json-repair",
    libraryDependencies += "com.typesafe.play" %% "play-json" % playJsonVersion.value,
    libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.16",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
    libraryDependencies ++= loggingLibs.value,
    publish / skip := false
  )

lazy val `ws-client-play` =
  (project in file("ws-client-play"))
    .settings(
      name := "ws-client-play",
      libraryDependencies ++= playWsDependencies.value,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`)
    .aggregate(`ws-client-core`, `json-repair`)

lazy val `ws-client-play-stream` =
  (project in file("ws-client-play-stream"))
    .settings(
      name := "ws-client-play-stream",
      libraryDependencies += "com.typesafe.akka" %% "akka-http" % akkaHttpVersion, // JSON WS Streaming
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-play`)
    .aggregate(`ws-client-core`, `ws-client-play`)
