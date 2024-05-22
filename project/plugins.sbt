logLevel := Level.Warn

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.10.0")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// Test Coverage plugin.
// ~
// sbt-scoverage is a plugin for SBT that integrates the scoverage code coverage library.
// See more: https://github.com/scoverage/sbt-scoverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.12")
