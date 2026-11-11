import sbt.Keys.scalaVersion

// Supported versions
val scala212 = "2.12.18"
// 2.13.18: play-ws 2.2.18 (the Akka flavor on 2.13) depends on scala-library 2.13.18, and sbt
// >= 1.10 fails a build whose scalaVersion is below a dependency's scala-library (SIP-51)
val scala213 = "2.13.18"
val scala32 = "3.2.2"
// Scala 3.3 LTS: only for the modules whose own dependencies are built with 3.3 (Pekko, play-ws
// 3.x, fs2, ZIO). Every other module's `_3` artifact stays compiled with 3.2.2 (TASTy 28.2), so
// it serves Scala 3.2 and 3.3+ users alike - see `tastyCheck`. 3.3.8 >= the 3.3.x each of
// those dependencies is built with (play-ws 3.0.14: 3.3.8)
val scala33 = "3.3.8"

ThisBuild / description := "Generic WebServices client library for Scala with pluggable, auto-discovered HTTP engines: Play WS (Akka / Pekko), pekko-http, JDK HttpClient, sttp and zio-http"

ThisBuild / organization := "io.cequence"
ThisBuild / scalaVersion := scala213
ThisBuild / version := "1.2.0"
ThisBuild / isSnapshot := version.value.endsWith("-SNAPSHOT")
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
    // not scalafixSemanticdb.revision (4.9.3 with sbt-scalafix 0.12.1): semanticdb-scalac is
    // published per Scala patch version, and Scala 2.13.18 starts at 4.13.9
    semanticdbVersion := "4.13.9"
  )
)

// A 3.2-line module (its `_3` artifact compiled with 3.2.2) that also builds with 3.3: an sbt
// project dependency needs one Scala line on both sides, so the 3.3-line modules (Pekko, fs2,
// ZIO) depend on a 3.3 build of it. That build stays in the build - never published, or
// `ws-client-core_3` would be published twice (`tastyCheck` guards it)
lazy val publishedExceptOnScala33 = {
  val onScala33 = Def.setting(scalaVersion.value == scala33)
  Seq(
    publish / skip := onScala33.value,
    publishLocal / skip := onScala33.value,
    publishM2 / skip := onScala33.value // sbt-pgp's publishSigned honors `publish / skip`
  )
}

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
        // not 2.6.1: its akka-protobuf-v3 POM declares protobuf-java 3.10.0 (5 CVEs - unused,
        // but on every 2.12 consumer's classpath)
        "com.typesafe.akka" %% "akka-stream" % "2.6.20" exclude ("com.typesafe.play", "play-json")
      )
    case Some((2, 13)) =>
      Seq(
        // 2.6.21: the version play-ws 2.2.18 brings, so core-akka is compiled and tested against
        // the Akka its Play modules run on
        "com.typesafe.akka" %% "akka-stream" % "2.6.21" exclude ("com.typesafe.play", "play-json")
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

val akkaHttpVersion = "10.5.3" // 10.5.1 has CVE-2023-44487 (HTTP/2 server - unused here)

// Pekko (open-source successor of Akka; the pekko modules are source-generated from the akka ones)
val pekkoVersion = "1.6.0"
val pekkoHttpVersion = "1.3.0"

// play-ws 3.x (org.playframework, Pekko-based) is published for Scala 2.13 and 3.3+ only: the
// Pekko modules build their Scala 3 artifact with 3.3 (since 1.2.0)
lazy val pekkoScalaVersions = List(scala213, scala33)

// Play WS

// play-json comes from ws-client-core (playJsonVersion): Play WS's own play-json dependency is
// excluded, so core's version is the one on the classpath (play-ws 2.2.18 would otherwise pull
// play-json 2.10.x onto Scala 2.13). The rule must name the artifact with its Scala suffix - a
// bare "play-json" matches nothing
def typesafePlayWS(
  version: String,
  scalaBinary: String
) = Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % version,
  "com.typesafe.play" %% "play-ws-standalone-json" % version
).map(_.excludeAll(ExclusionRule("com.typesafe.play", s"play-json_$scalaBinary")))

lazy val playWsDependencies = Def.setting {
  val scalaBinary = scalaBinaryVersion.value

  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      // the last Play WS 2.x for Scala 2.12 (shaded AHC 2.10.5 / Netty 4.1.45)
      typesafePlayWS("2.1.11", scalaBinary)

    case Some((2, 13)) =>
      // shaded AHC 2.16.1 / Netty 4.1.138, Akka 2.6.21; class files need Java 11
      typesafePlayWS("2.2.18", scalaBinary)

    case Some((3, 2)) =>
      // 2.2.0-M3 and later need Scala 3.3 (M3 was built with 3.3.0-RC3)
      typesafePlayWS("2.2.0-M2", scalaBinary)

    case _ =>
      throw new Exception("Unsupported scala version")
  }
}

// Pekko-based Play WS (org.playframework); its play-json 3.x dep is excluded because its classes
// share FQCNs with com.typesafe.play play-json coming from ws-client-core (different groupIds - no eviction)
val playWs3Version = "3.0.14"

lazy val playWsPekkoDependencies = Def.setting {
  Seq(
    "org.playframework" %% "play-ahc-ws-standalone" % playWs3Version,
    "org.playframework" %% "play-ws-standalone-json" % playWs3Version
  ).map(
    _.excludeAll(ExclusionRule("org.playframework", s"play-json_${scalaBinaryVersion.value}"))
  )
}

// forked tests, run in target/: TestNG (the Reactive Streams TCK) writes its report files into
// the working directory
lazy val forkedTestsInTarget = Seq(
  Test / fork := true,
  Test / forkOptions := (Test / forkOptions).value.withWorkingDirectory(target.value),
  verifyKnobs
)

// the knobs of the exhaustive specs (-Dwsclient.verify.*: scope, depth, invocation budget) reach
// a forked test JVM
lazy val verifyKnobs =
  Test / javaOptions ++= sys.props.toSeq.collect {
    case (key, value) if key.startsWith("wsclient.verify.") => s"-D$key=$value"
  }

// Reactive Streams TCK (TestNG-based) for the AsyncStream <-> Flow.Publisher bridges, run
// through ScalaTest's TestNG integration (the scalatestplus line matching ScalaTest 3.2.16)
lazy val reactiveStreamsTckLibs = Seq(
  "org.reactivestreams" % "reactive-streams-tck-flow" % "1.0.4" % Test,
  "org.scalatestplus" %% "testng-7-5" % "3.2.16.0" % Test
)

lazy val `ws-client-core` =
  (project in file("ws-client-core")).settings(
    name := "ws-client-core",
    crossScalaVersions := List(scala212, scala213, scala32, scala33),
    libraryDependencies += "com.typesafe.play" %% "play-json" % playJsonVersion.value,
    libraryDependencies ++= jacksonLibs,
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    libraryDependencies ++= loggingLibs.value,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
    libraryDependencies ++= reactiveStreamsTckLibs,
    testLoggingSettings,
    // WSClientEngineRegistrySpec mutates the JVM-global 'ws-client.engine' system property;
    // fork so it cannot race the other modules' registry-based suites in the shared sbt JVM
    forkedTestsInTarget,
    publishedExceptOnScala33
  )

lazy val `ws-client-core-akka` =
  (project in file("ws-client-core-akka"))
    .settings(
      name := "ws-client-core-akka",
      libraryDependencies ++= akkaStreamLibs.value,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      libraryDependencies ++= reactiveStreamsTckLibs,
      testLoggingSettings,
      forkedTestsInTarget,
      // the opt-in implicit conversions must compile warning-free under -feature
      Test / scalacOptions ++= Seq("-feature", "-Wconf:cat=feature:e"),
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
      // the TLS spec points the JVM-global default trust store at a test certificate; fork so
      // it cannot leak into the other modules' suites in the shared sbt JVM
      Test / fork := true,
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
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      libraryDependencies ++= reactiveStreamsTckLibs,
      testLoggingSettings,
      forkedTestsInTarget,
      Test / scalacOptions ++= Seq("-feature", "-Wconf:cat=feature:e"),
      PekkoGenerator.generatorSettings(`ws-client-core-akka`),
      // core-akka's specs have no Akka-specific parts: the Pekko twin runs them generated
      PekkoGenerator.testGeneratorSettings(`ws-client-core-akka`),
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
      // the TLS spec points the JVM-global default trust store at a test certificate; fork so
      // it cannot leak into the other modules' suites in the shared sbt JVM
      Test / fork := true,
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
      crossScalaVersions := List(scala212, scala213, scala32, scala33),
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

// zio-http's compile dependency netty-pkitesting (test certificates) brings Bouncy Castle 1.84
// (CVE-2026-8763, CVE-2026-13506 - the client never uses it). Declared directly, as Jackson is,
// so consumers resolve a patched line; the three jars must share one version
val bouncyCastleVersion = "1.86"

lazy val bouncyCastleLibs = Seq(
  "org.bouncycastle" % "bcprov-jdk18on" % bouncyCastleVersion,
  "org.bouncycastle" % "bcpkix-jdk18on" % bouncyCastleVersion,
  "org.bouncycastle" % "bcutil-jdk18on" % bouncyCastleVersion
)

lazy val `ws-client-zio-http` =
  (project in file("ws-client-zio-http"))
    .settings(
      name := "ws-client-zio-http",
      crossScalaVersions := List(scala212, scala213), // zio-http's Scala 3 artifacts need 3.3+
      libraryDependencies += "dev.zio" %% "zio-http" % zioHttpVersion,
      // ZStream -> org.reactivestreams Publisher (then FlowAdapters -> java.util.concurrent.Flow)
      libraryDependencies += "dev.zio" %% "zio-interop-reactivestreams" % "2.0.2",
      libraryDependencies ++= bouncyCastleLibs,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      // the TLS spec points the JVM-global default trust store at a test certificate; fork so
      // that cannot leak into other modules' tests
      Test / fork := true,
      testLoggingSettings,
      publish / skip := false
    )
    // ws-client-zio: the engine's AsyncStreams are native ZIO leaves (since 1.2.0)
    .dependsOn(`ws-client-core`, `ws-client-zio`, `ws-client-testkit` % Test)

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

// Interop with fs2 and ZIO streams (AsyncStream compilers and conversions). Their Scala 3
// artifacts are built with 3.3: fs2 / cats-effect and ZIO publish for Scala 3.3+ only
val fs2Version = "3.12.2" // fs2.interop.flow; built with Scala 3.3.5
val catsEffectVersion = "3.6.3" // IO / IORuntime for Fs2Runner.io (fs2 3.12.2 needs 3.6.x)
val zioVersion = "2.1.26" // the version zio-http 3.11.6 (ws-client-zio-http) is built on

lazy val `ws-client-fs2` =
  (project in file("ws-client-fs2"))
    .settings(
      name := "ws-client-fs2",
      crossScalaVersions := List(scala212, scala213, scala33),
      libraryDependencies += "co.fs2" %% "fs2-core" % fs2Version,
      libraryDependencies += "org.typelevel" %% "cats-effect" % catsEffectVersion,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-testkit` % Test)

lazy val `ws-client-zio` =
  (project in file("ws-client-zio"))
    .settings(
      name := "ws-client-zio",
      crossScalaVersions := List(scala212, scala213, scala33),
      libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion,
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      testLoggingSettings,
      publish / skip := false
    )
    .dependsOn(`ws-client-core`, `ws-client-testkit` % Test)

// Verification of the stream layer beyond the per-module specs - never published. Scala 2.13
// only: the one version on which the neutral interpreter and the Akka, fs2 and ZIO compilers
// meet.
//  - small-scope (bounded-exhaustive) differential specs: ordinary tests
//  - Lincheck model checks of the lock-free bridges: the `modelCheck` command (a forked main,
//    about 7 minutes - not part of `test`)
val lincheckVersion = "3.7"

lazy val `ws-client-verification` =
  (project in file("ws-client-verification"))
    .settings(
      name := "ws-client-verification",
      crossScalaVersions := List(scala213),
      libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      libraryDependencies += "org.jetbrains.lincheck" % "lincheck" % lincheckVersion % Test,
      testLoggingSettings,
      Test / fork := true,
      Test / javaOptions += "-Xmx2g",
      verifyKnobs,
      // Lincheck attaches its agent to the running JVM
      Test / run / fork := true,
      Test / run / javaOptions ++= Seq(
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading"
      ),
      Test / run / outputStrategy := Some(StdoutOutput),
      publish / skip := true
    )
    .dependsOn(`ws-client-core-akka`, `ws-client-fs2`, `ws-client-zio`)

addCommandAlias(
  "modelCheck",
  "ws-client-verification/Test/runMain io.cequence.wsclient.verification.ModelChecks"
)

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
    `ws-client-fs2`,
    `ws-client-zio`,
    `ws-client-testkit`,
    `ws-client-verification`,
    `json-repair`
  )
