import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousArtifacts
import com.typesafe.tools.mima.plugin.MimaPlugin
import sbt.Keys._
import sbt._

import java.io.{DataInputStream, InputStream}
import java.util.zip.ZipFile
import scala.collection.JavaConverters._

/**
 * Release guards for every project:
 *
 *   - MiMa against the last release: `mimaPreviousArtifacts` is the 1.1.5 artifact of the same
 *     module and Scala binary version, where 1.1.5 published one (new modules, new Scala axes
 *     and unpublished projects have none).
 *   - `tastyCheck`: the TASTy version of a Scala 3 jar. The modules whose `_3` artifact was
 *     published by 1.1.5 (the "3.2 line": core, core-akka, json-repair, jdk,
 *     play-akka(-stream)) keep being compiled with Scala 3.2 (TASTy 28.2), so they serve Scala
 *     3.2 and 3.3+ users; only the modules whose own dependencies need Scala 3.3 build their
 *     `_3` artifact with 3.3 (TASTy 28.3). A 3.2-line module that also builds with 3.3 (an
 *     in-build dependency of the 3.3-line modules) must never publish that build -
 *     `ws-client-core_3` would be published twice. `tastyCheck` fails on a publishable Scala 3
 *     jar of the wrong line, and `tastyCheckStaged [dir]` checks every `_3` jar of a staged
 *     release (by default `target/sonatype-staging/<version>`).
 */
object ReleaseChecks extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  val previousVersion = "1.1.5"

  /** The Scala binary versions each module was published for in [[previousVersion]]. */
  val publishedIn115: Map[String, Set[String]] = Map(
    "ws-client-core" -> Set("2.12", "2.13", "3"),
    "ws-client-core-akka" -> Set("2.12", "2.13", "3"),
    "json-repair" -> Set("2.12", "2.13", "3"),
    "ws-client-play-akka" -> Set("2.12", "2.13", "3"),
    "ws-client-play-akka-stream" -> Set("2.12", "2.13", "3"),
    "ws-client-jdk" -> Set("2.12", "2.13", "3"),
    "ws-client-sttp" -> Set("2.12", "2.13"),
    "ws-client-zio-http" -> Set("2.12", "2.13"),
    "ws-client-core-pekko" -> Set("2.13"),
    "ws-client-play-pekko" -> Set("2.13"),
    "ws-client-play-pekko-stream" -> Set("2.13"),
    "ws-client-pekko-http" -> Set("2.13")
  )

  /** Modules whose published `_3` artifact must be compiled with Scala 3.2 (TASTy 28.2). */
  val scala32Line: Set[String] =
    publishedIn115.collect { case (module, versions) if versions("3") => module }.toSet

  /** TASTy minor version a published `_3` jar of `module` must carry. */
  def expectedTastyMinor(module: String): Int =
    if (scala32Line(module)) 2 else 3

  object autoImport {
    val tastyCheck = taskKey[Unit](
      "Fails if this project's publishable Scala 3 jar carries the wrong TASTy version"
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    // a setting cannot read `publish / skip` (a task): an unpublished project is one this map
    // does not list, and a 3.2-line module's Scala 3.3 build is the in-build-only one
    mimaPreviousArtifacts := {
      val module = moduleName.value
      val published = publishedIn115.getOrElse(module, Set.empty[String])
      val inBuildOnly =
        scala32Line(module) && scalaBinaryVersion.value == "3" &&
          !scalaVersion.value.startsWith("3.2.")

      if (published(scalaBinaryVersion.value) && !inBuildOnly)
        Set(organization.value %% module % previousVersion)
      else
        Set.empty
    },
    tastyCheck := Def.taskDyn {
      val log = streams.value.log
      val module = moduleName.value
      val skipped = (publish / skip).value

      if (scalaBinaryVersion.value != "3" || skipped)
        Def.task {
          log.info(
            s"tastyCheck: $module ${scalaVersion.value} - not a published Scala 3 build"
          )
        }
      else
        Def.task {
          checkJar((Compile / packageBin).value, module, expectedTastyMinor(module), log)
        }
    }.value,
    commands += tastyCheckStaged
  )

  private def checkJar(
    jar: File,
    module: String,
    expectedMinor: Int,
    log: Logger
  ): Unit = {
    val versions = tastyVersions(jar)

    if (versions.isEmpty)
      log.info(s"tastyCheck: $module - no .tasty files in ${jar.getName}")
    else if (versions == Set((28, expectedMinor, 0)))
      log.info(s"tastyCheck: $module - TASTy 28.$expectedMinor (${jar.getName})")
    else
      throw new MessageOnlyException(
        s"tastyCheck: $module must be compiled with Scala 3.$expectedMinor (TASTy 28.$expectedMinor)" +
          s" but ${jar.getName} carries ${versions.map { case (ma, mi, ex) => s"$ma.$mi.$ex" }.mkString(", ")}"
      )
  }

  // usage: tastyCheckStaged [dir] - every `_3` jar below dir (excluding sources / javadoc jars)
  private def tastyCheckStaged: Command =
    Command.args("tastyCheckStaged", "[dir]") {
      (
        state,
        args
      ) =>
        val extracted = Project.extract(state)
        val base = extracted.get(ThisBuild / baseDirectory)
        val version = extracted.get(ThisBuild / Keys.version)
        val dir =
          args.headOption.map(file).getOrElse(base / "target" / "sonatype-staging" / version)
        val log = state.log

        if (!dir.isDirectory)
          throw new MessageOnlyException(s"tastyCheckStaged: no directory $dir")

        // Maven layout (`<module>_3/<version>/<module>_3-<version>.jar`, a staged release) or
        // ivy layout (`<module>_3/<version>/jars/<module>_3.jar`, publishLocal)
        def moduleOf(jar: File): Option[String] =
          Iterator
            .iterate(jar.getParentFile)(_.getParentFile)
            .takeWhile(d => d != null && d != dir)
            .map(_.getName)
            .find(_.endsWith("_3"))
            .map(_.stripSuffix("_3"))

        val jars = (dir ** "*.jar").get.filterNot { jar =>
          val name = jar.getName
          name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")
        }.flatMap(jar => moduleOf(jar).map(jar -> _))

        if (jars.isEmpty)
          throw new MessageOnlyException(s"tastyCheckStaged: no _3 jars below $dir")

        jars.sortBy(_._1.getPath).foreach { case (jar, module) =>
          checkJar(jar, module, expectedTastyMinor(module), log)
        }
        state
    }

  /** The distinct (major, minor, experimental) TASTy versions of the .tasty files in `jar`. */
  def tastyVersions(jar: File): Set[(Int, Int, Int)] = {
    val zip = new ZipFile(jar)
    try
      zip.entries.asScala
        .filter(_.getName.endsWith(".tasty"))
        .map(entry => readHeader(zip.getInputStream(entry)))
        .toSet
    finally zip.close()
  }

  // TASTy header: the magic 5C A1 AB 1F, then the NATs majorVersion, minorVersion and
  // experimentalVersion (a NAT is big-endian base 128, its LAST byte flagged with 0x80)
  private def readHeader(stream: InputStream): (Int, Int, Int) = {
    val in = new DataInputStream(stream)
    try {
      val magic = in.readInt()
      if (magic != 0x5ca1ab1f)
        throw new MessageOnlyException(f"not a TASTy file (magic 0x$magic%08x)")

      def nat(): Int = {
        var x = 0
        var b = 0
        do {
          b = in.readUnsignedByte()
          x = (x << 7) | (b & 0x7f)
        } while ((b & 0x80) == 0)
        x
      }

      val major = nat()
      val minor = nat()
      val experimental = nat()
      (major, minor, experimental)
    } finally in.close()
  }
}
