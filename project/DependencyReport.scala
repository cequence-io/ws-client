import sbt.Keys._
import sbt._

/**
 * `dependencyExport`: what a PUBLISHED module brings onto a user's classpath - its resolved
 * runtime dependencies for the current Scala version - as one file per module and Scala binary
 * version in `target/dependency-report/` (the root's). `security/dependencies.py` reads those
 * files: it renders the dependency matrix (README, docs/dependencies.md) and scans every
 * artifact for known vulnerabilities. Run it for every Scala version: `sbt +dependencyExport`.
 *
 * A line: `direct|transitive <TAB> organization:name <TAB> version <TAB> jar`. In-build
 * modules (ws-client's own) are left out; test and provided dependencies are not on the
 * runtime classpath.
 */
object DependencyReport extends AutoPlugin {

  override def trigger = allRequirements

  object autoImport {
    val dependencyExport = taskKey[Unit](
      "Writes this published module's resolved runtime dependencies to target/dependency-report"
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    dependencyExport := Def.taskDyn {
      val log = streams.value.log
      val module = moduleName.value
      val scalaBinary = scalaBinaryVersion.value

      if ((publish / skip).value)
        Def.task(log.info(s"dependencyExport: $module $scalaBinary - not published"))
      else
        Def.task {
          val own = organization.value
          // declared in this module, on the compile / runtime classpath
          val declared = libraryDependencies.value.filter { dependency =>
            dependency.configurations.forall { configurations =>
              configurations.split(';').exists { configuration =>
                val from = configuration.split("->").head.trim
                from == "compile" || from == "runtime"
              }
            }
          }
          def isDirect(resolved: ModuleID): Boolean =
            declared.exists { dependency =>
              dependency.organization == resolved.organization &&
              (resolved.name == dependency.name || resolved.name.startsWith(
                dependency.name + "_"
              ))
            }

          val lines = for {
            configuration <- update.value.configuration(Runtime).toSeq
            report <- configuration.modules
            resolved = report.module
            if resolved.organization != own && !report.evicted
            jar = report.artifacts.collectFirst {
              case (artifact, file) if artifact.extension == "jar" => file.getAbsolutePath
            }.getOrElse("")
          } yield Seq(
            if (isDirect(resolved)) "direct" else "transitive",
            s"${resolved.organization}:${resolved.name}",
            resolved.revision,
            jar
          ).mkString("\t")

          val file =
            (LocalRootProject / target).value / "dependency-report" / s"${module}_$scalaBinary.tsv"
          IO.writeLines(file, lines.distinct.sorted)
          log.info(
            s"dependencyExport: $module $scalaBinary - ${lines.size} artifacts -> $file"
          )
        }
    }.value
  )
}
