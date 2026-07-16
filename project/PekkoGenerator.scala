import sbt.Keys._
import sbt._

/**
 * Generates the Pekko flavor of an Akka-based module by copying its Scala sources into
 * `sourceManaged` and rewriting `akka.*` package references to `org.apache.pekko.*`.
 *
 * The Akka modules are the single source of truth - never edit the generated sources
 * (`target/**/src_managed`). Files named `*EngineProvider.scala` are skipped; ServiceLoader
 * providers are handwritten per module because their engine ids and priorities differ.
 */
object PekkoGenerator {

  private val header =
    "// AUTO-GENERATED from the corresponding Akka-based module by PekkoGenerator - DO NOT EDIT\n"

  private val akkaPackageRef = """(?<![\w.])akka\.""".r

  private[this] def rewrite(
    fileName: String,
    content: String
  ): String = {
    val rewritten = content
      // package references only ("akka" followed by a dot, not preceded by a word char or dot),
      // e.g. "import akka.stream..." but not "ServiceBaseAdaptersAkka" or "akka-stream"
      .replaceAll(akkaPackageRef.regex, "org.apache.pekko.")
      // Source.fromFutureSource is deprecated in Akka 2.6 and dropped in Pekko
      .replaceAll("""\.fromFutureSource\b""", ".futureSource")
      // Akka-suffixed public type names
      .replaceAll("""\bServiceBaseAdaptersAkka\b""", "ServiceBaseAdaptersPekko")
      .replaceAll("""\bWSClientOutputStreamExtraAkka\b""", "WSClientOutputStreamExtraPekko")
      .replaceAll("""\bWSClientInputStreamExtraAkka\b""", "WSClientInputStreamExtraPekko")
      .replaceAll("""\bSourcePublishersAkka\b""", "SourcePublishersPekko")

    if (akkaPackageRef.findFirstIn(rewritten).isDefined)
      throw new MessageOnlyException(
        s"PekkoGenerator: an unrewritten 'akka.' reference survived in $fileName - inspect the source file"
      )

    header + rewritten
  }

  /**
   * Settings adding a `Compile / sourceGenerators` task that mirrors `fromProject`'s Scala
   * sources into this project as Pekko-based ones.
   */
  def generatorSettings(fromProject: Project): Seq[Setting[_]] = Seq(
    Compile / sourceGenerators += Def.task {
      val srcDirs = (fromProject / Compile / unmanagedSourceDirectories).value
      val outBase = (Compile / sourceManaged).value / "pekko"
      val log = streams.value.log

      val inputs = srcDirs.flatMap(dir => (dir ** "*.scala").get.map(file => (dir, file)))

      inputs.flatMap { case (dir, file) =>
        val relativePath = IO.relativize(dir, file).get

        if (relativePath.endsWith("EngineProvider.scala"))
          None
        else {
          val target = outBase / relativePath.replace("Akka", "Pekko")
          val newContent = rewrite(relativePath, IO.read(file))

          // write only on change so zinc does not recompile on every run
          if (!target.exists || IO.read(target) != newContent) {
            log.info(s"PekkoGenerator: generating $relativePath")
            IO.write(target, newContent)
          }
          Some(target)
        }
      }
    }.taskValue
  )
}
