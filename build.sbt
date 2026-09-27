import sbt.Keys.scalaVersion

// Supported versions
val scala212 = "2.12.18"
val scala213 = "2.13.11"
val scala32 = "3.2.2"

ThisBuild / description := "Generic WebServices library with Play WS impl./backends for Akka and Pekko"

ThisBuild / organization := "io.cequence"
ThisBuild / scalaVersion := scala213
ThisBuild / version := "1.1.0"
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
// a Def.setting (not an inThisBuild settingKey) so it follows each project's own scalaVersion
// during cross-building with heterogeneous crossScalaVersions
lazy val playJsonVersion = Def.setting {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => "2.8.2"
    case Some((2, 13)) => "2.8.2" // 2.10.0
    case Some((3, _))  => "2.10.0-RC6"
    case _             => "2.8.2"
  }
}

// Akka
lazy val akkaStreamLibs = Def.setting {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.1" exclude ("com.typesafe.play", "play-json")
      )
    case Some((2, 13)) =>
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.20" exclude ("com.typesafe.play", "play-json")
      )
    case Some((3, 2)) =>
      // because of the conflicting cross-version suffixes 2.13 vs 3
      Seq(
//        "com.typesafe.akka" %% "akka-stream" % "2.6.20"
        "com.typesafe.akka" % "akka-stream_2.13" % "2.6.20" exclude ("com.typesafe", "ssl-config-core_2.13") exclude ("com.typesafe.play", "play-json"),
        "com.typesafe" %% "ssl-config-core" % "0.6.1"
      )
    case _ =>
      throw new Exception("Unsupported scala version")
  }
}

val loggingLibs = Def.setting {
  Seq(
    // the SLF4J API only (via scala-logging) - a library must not ship a logging BACKEND: it
    // hijacks the host's logging, and logback's no-config default is root=DEBUG, under which
    // Play's shaded AHC logs complete request headers (incl. Authorization)
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
  )
}

// logging backend for this build's own tests only, configured by the shared
// test-resources/logback-test.xml; 1.5.x is the JDK 11 compatible line
val logbackVersion = "1.5.38"

lazy val testLoggingSettings = Seq(
  libraryDependencies += "ch.qos.logback" % "logback-classic" % logbackVersion % Test,
  Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "test-resources",
  // the test-only ws-client-testkit is never published: keep its `% Test` project dependency
  // out of the published POMs
  pomPostProcess := { (node: scala.xml.Node) =>
    new scala.xml.transform.RuleTransformer(new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.NodeSeq =
        if (n.label == "dependency" && (n \ "artifactId").text.startsWith("ws-client-testkit"))
          scala.xml.NodeSeq.Empty
        else n
    }).transform(node).head
  }
)

// play-json (2.8.2 / 2.10.0-RC6) pins Jackson 2.11.4, affected by CVE-2025-52999 (deeply
// nested input -> StackOverflowError) and CVE-2020-36518. Declared directly - not as
// dependencyOverrides, which never reach the published POM - so the patched line also wins in
// consumers' resolution; Jackson 2.15+ additionally enforces StreamReadConstraints (nesting
// depth, string/number length) on every parse
val jacksonVersion = "2.22.3"

lazy val jacksonLibs = Seq(
  "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion
)

val akkaHttpVersion = "10.5.1" //"10.5.0-M1"

// Pekko (open-source successor of Akka; the pekko modules are source-generated from the akka ones)
val pekkoVersion = "1.6.0"
val pekkoHttpVersion = "1.3.0"

// play-ws 3.x (org.playframework, Pekko-based) is published for Scala 2.13 and 3.3+ only
lazy val pekkoScalaVersions = List(scala213)

// Play WS

def typesafePlayWS(version: String) = Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % version exclude ("com.typesafe.play", "play-json"),
  "com.typesafe.play" %% "play-ws-standalone-json" % version exclude ("com.typesafe.play", "play-json")
//  "com.typesafe.play" % "shaded-asynchttpclient" % version,
//  "io.netty" % "netty-tcnative-boringssl-static" % "2.0.69.Final"
)

lazy val playWsDependencies = Def.setting {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      // play json - 2.8.2
      typesafePlayWS("2.1.11")

    case Some((2, 13)) =>
      // play json - 2.10.0
      typesafePlayWS("2.1.11")

    case Some((3, 2)) =>
      // Version "2.2.0-M3" was produced by an unstable release: Scala 3.3.0-RC3 - // play json - 2.10.0-RC6
      typesafePlayWS("2.2.0-M2")

    case _ =>
      throw new Exception("Unsupported scala version")
  }
}

// Pekko-based Play WS (org.playframework); its play-json 3.x dep is excluded because its classes
// share FQCNs with com.typesafe.play play-json coming from ws-client-core (different groupIds - no eviction)
val playWs3Version = "3.0.7"

lazy val playWsPekkoDependencies = Def.setting {
  Seq(
    "org.playframework" %% "play-ahc-ws-standalone" % playWs3Version,
    "org.playframework" %% "play-ws-standalone-json" % playWs3Version
  ).map(
    _.excludeAll(ExclusionRule("org.playframework", s"play-json_${scalaBinaryVersion.value}"))
  )
}

lazy val `ws-client-core` =
  (project in file("ws-client-core")).settings(
    name := "ws-client-core",
    libraryDependencies += "com.typesafe.play" %% "play-json" % playJsonVersion.value,
    libraryDependencies ++= jacksonLibs,
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    libraryDependencies ++= loggingLibs.value,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
    testLoggingSettings,
    // WSClientEngineRegistrySpec mutates the JVM-global 'ws-client.engine' system property;
    // fork so it cannot race the other modules' registry-based suites in the shared sbt JVM
    Test / fork := true,
    publish / skip := false
  )

lazy val `ws-client-core-akka` =
  (project in file("ws-client-core-akka"))
    .settings(
      name := "ws-client-core-akka",
      libraryDependencies ++= akkaStreamLibs.value,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`)

lazy val `json-repair` =
  (project in file("json-repair")).settings(
    name := "json-repair",
    libraryDependencies += "com.typesafe.play" %% "play-json" % playJsonVersion.value,
    libraryDependencies ++= jacksonLibs,
    libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.16",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
    libraryDependencies ++= loggingLibs.value,
    testLoggingSettings,
    publish / skip := false
  )

lazy val `ws-client-play-akka` =
  (project in file("ws-client-play-akka"))
    .settings(
      name := "ws-client-play-akka", // named "ws-client-play" before 1.0.0
      libraryDependencies ++= playWsDependencies.value,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core-akka`, `ws-client-testkit` % Test)
    .aggregate(`ws-client-core`, `ws-client-core-akka`, `json-repair`)

lazy val `ws-client-play-akka-stream` =
  (project in file("ws-client-play-akka-stream"))
    .settings(
      name := "ws-client-play-akka-stream",
      libraryDependencies += "com.typesafe.akka" %% "akka-http" % akkaHttpVersion, // JSON WS Streaming
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core-akka`, `ws-client-play-akka`, `ws-client-testkit` % Test)
    .aggregate(`ws-client-core`, `ws-client-core-akka`, `ws-client-play-akka`)

// Pekko modules - sources are generated from the corresponding akka modules by PekkoGenerator;
// edit the akka modules, never target/**/src_managed

lazy val `ws-client-core-pekko` =
  (project in file("ws-client-core-pekko"))
    .settings(
      name := "ws-client-core-pekko",
      crossScalaVersions := pekkoScalaVersions,
      libraryDependencies += "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      PekkoGenerator.generatorSettings(`ws-client-core-akka`),
      publish / skip := false
    )
    .dependsOn(`ws-client-core`)

lazy val `ws-client-play-pekko` =
  (project in file("ws-client-play-pekko"))
    .settings(
      name := "ws-client-play-pekko",
      crossScalaVersions := pekkoScalaVersions,
      libraryDependencies ++= playWsPekkoDependencies.value,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      PekkoGenerator.generatorSettings(`ws-client-play-akka`),
      publish / skip := false
    )
    .dependsOn(`ws-client-core-pekko`, `ws-client-testkit` % Test)

lazy val `ws-client-play-pekko-stream` =
  (project in file("ws-client-play-pekko-stream"))
    .settings(
      name := "ws-client-play-pekko-stream",
      crossScalaVersions := pekkoScalaVersions,
      libraryDependencies += "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion, // JSON WS Streaming
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      PekkoGenerator.generatorSettings(`ws-client-play-akka-stream`),
      publish / skip := false
    )
    .dependsOn(`ws-client-core-pekko`, `ws-client-play-pekko`, `ws-client-testkit` % Test)

// Fixtures shared by the engine specs (embedded echo/SSE/proxy servers, a latched
// Flow.Subscriber) - never published, used in Test scope only. Sharing core's test classpath
// instead (`test->test`) would leak its dummy engine providers into every engine's discovery
lazy val `ws-client-testkit` =
  (project in file("ws-client-testkit"))
    .settings(
      name := "ws-client-testkit",
      publish / skip := true
    )
    .dependsOn(`ws-client-core`)

// Other backends (registered in the engine-discovery SPI alongside the Play ones)

lazy val `ws-client-jdk` =
  (project in file("ws-client-jdk"))
    .settings(
      name := "ws-client-jdk",
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-testkit` % Test)

val sttpVersion = "4.0.3"

lazy val `ws-client-sttp` =
  (project in file("ws-client-sttp"))
    .settings(
      name := "ws-client-sttp",
      crossScalaVersions := List(scala212, scala213), // sttp4's Scala 3 artifacts need 3.3+
      libraryDependencies += "com.softwaremill.sttp.client4" %% "core" % sttpVersion,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-testkit` % Test)

val zioHttpVersion = "3.11.6"

lazy val `ws-client-zio-http` =
  (project in file("ws-client-zio-http"))
    .settings(
      name := "ws-client-zio-http",
      crossScalaVersions := List(scala212, scala213), // zio-http's Scala 3 artifacts need 3.3+
      libraryDependencies += "dev.zio" %% "zio-http" % zioHttpVersion,
      // ZStream -> org.reactivestreams Publisher (then FlowAdapters -> java.util.concurrent.Flow)
      libraryDependencies += "dev.zio" %% "zio-interop-reactivestreams" % "2.0.2",
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-testkit` % Test)

lazy val `ws-client-pekko-http` =
  (project in file("ws-client-pekko-http"))
    .settings(
      name := "ws-client-pekko-http",
      crossScalaVersions := pekkoScalaVersions,
      libraryDependencies += "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core-pekko`, `ws-client-testkit` % Test)

lazy val root = (project in file("."))
  .settings(
    name := "ws-client",
    publish / skip := true,
    crossScalaVersions := Nil
  )
  .aggregate(
    `ws-client-core`,
    `ws-client-core-akka`,
    `ws-client-play-akka`,
    `ws-client-play-akka-stream`,
    `ws-client-core-pekko`,
    `ws-client-play-pekko`,
    `ws-client-play-pekko-stream`,
    `ws-client-jdk`,
    `ws-client-sttp`,
    `ws-client-pekko-http`,
    `ws-client-zio-http`,
    `ws-client-testkit`,
    `json-repair`
  )
