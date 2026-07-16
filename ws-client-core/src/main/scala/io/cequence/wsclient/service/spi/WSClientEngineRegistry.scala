package io.cequence.wsclient.service.spi

import com.typesafe.config.ConfigFactory
import io.cequence.wsclient.domain.CequenceWSException
import io.cequence.wsclient.service.WSClientEngine
import org.slf4j.LoggerFactory

import java.util.{Collections, ServiceLoader, WeakHashMap}
import scala.collection.mutable.ListBuffer

/**
 * Discovers [[WSClientEngineProvider]]s on the classpath (via [[java.util.ServiceLoader]]) and
 * creates engines from them.
 *
 * Provider resolution order:
 *   1. explicit engine id passed to [[provider]] / [[apply]]
 *   1. the config key `ws-client.engine` looked up via `ConfigFactory.load()` - note that
 *      standard Typesafe Config semantics apply, so a system property
 *      (`-Dws-client.engine=...`) overrides `application.conf`
 *   1. auto-selection: a single discovered provider is used as is; with several, the one with
 *      the strictly highest [[WSClientEngineProvider.priority]] wins
 *
 * A tie or an empty classpath fails with a [[CequenceWSException]] naming the available engine
 * ids / backend artifacts.
 */
object WSClientEngineRegistry {

  val ConfigKey = "ws-client.engine"

  /**
   * When set to `true` (via config or `-Dws-client.strict-classpath=true`), detecting mutually
   * exclusive ws-client artifacts on the classpath (mixed Akka/Pekko flavors, or a pre-1.0
   * artifact next to its renamed successor) fails engine resolution with a
   * [[CequenceWSException]] instead of logging a warning - recommended for CI.
   */
  val StrictClasspathConfigKey = "ws-client.strict-classpath"

  private val logger = LoggerFactory.getLogger(getClass)

  // classes that DELIBERATELY share fully-qualified names across artifacts: the Akka and
  // Pekko flavors, and the pre-1.0 artifact names (ws-client-play, ws-client-play-stream).
  // Seeing one of them more than once on a classpath means jar-order-dependent shadowing -
  // implicits included - so we warn loudly (once per class loader)
  private val FlavoredClassResources = Seq(
    "io/cequence/wsclient/service/ws/PlayWSClientEngine.class",
    "io/cequence/wsclient/service/ws/stream/PlayWSStreamClientEngine.class"
  )

  // weak keys so the check cache never pins a class loader
  private val duplicatesChecked =
    Collections.synchronizedMap(new WeakHashMap[ClassLoader, java.lang.Boolean]())

  /**
   * Creates a SITE-STATELESS engine using the resolved provider - see the class doc for the
   * resolution order. One engine serves any number of sites/providers: every engine call takes
   * a [[io.cequence.wsclient.domain.SiteBinding]]. Treat engine creation as expensive (it owns
   * a connection pool and an execution environment) and reuse the instance; close it once,
   * when done with all services using it.
   */
  def apply(
    settings: TransportSettings = TransportSettings(),
    engineId: Option[String] = None
  ): WSClientEngine =
    provider(engineId).newEngine(settings)

  /**
   * An engine that can stream responses through the backend-agnostic
   * [[io.cequence.wsclient.service.WSClientOutputStreamCore]] contract
   * (`java.util.concurrent.Flow.Publisher`-typed - no akka/pekko required on the consumer
   * side). For the akka/pekko `Source`-typed variant use the flavored
   * `StreamedEngineRegistry.outputStreamed` from a `ws-client-core-akka`/`-pekko` module.
   */
  def outputStreamed(
    settings: TransportSettings = TransportSettings(),
    engineId: Option[String] = None
  ): WSClientEngine with io.cequence.wsclient.service.WSClientOutputStreamCore =
    provider(
      engineId,
      Set[EngineCapability](EngineCapability.OutputStreaming)
    ).newEngine(settings) match {
      case engine: io.cequence.wsclient.service.WSClientOutputStreamCore => engine
      case engine =>
        engine.close()
        throw new CequenceWSException(
          "The resolved WS client engine advertises OutputStreaming but does not implement WSClientOutputStreamCore."
        )
    }

  /**
   * All providers found on the classpath, ordered by descending priority.
   */
  def providers: Seq[WSClientEngineProvider] = providers(defaultClassLoader)

  def providers(classLoader: ClassLoader): Seq[WSClientEngineProvider] = {
    checkDuplicatedFlavoredClasses(classLoader)

    val loaded = new ListBuffer[WSClientEngineProvider]
    val it = ServiceLoader.load(classOf[WSClientEngineProvider], classLoader).iterator()
    while (it.hasNext) loaded += it.next()
    loaded.sortBy(-_.priority).toList
  }

  /**
   * Detects mutually-exclusive ws-client artifacts coexisting on the classpath - mixing the
   * Akka and Pekko flavors, or a pre-1.0 artifact (`ws-client-play`, `ws-client-play-stream`)
   * surviving next to its renamed successor. Returns the duplicated class-resource paths with
   * the jars providing them.
   */
  private[spi] def duplicatedFlavoredClasses(
    classLoader: ClassLoader
  ): Map[String, Seq[String]] =
    FlavoredClassResources.flatMap { path =>
      val urls = new ListBuffer[String]
      val it = classLoader.getResources(path)
      while (it.hasMoreElements) urls += it.nextElement().toString
      if (urls.size > 1) Some(path -> urls.toList) else None
    }.toMap

  private def checkDuplicatedFlavoredClasses(classLoader: ClassLoader): Unit =
    if (!duplicatesChecked.containsKey(classLoader)) {
      val duplicated = duplicatedFlavoredClasses(classLoader)

      if (duplicated.isEmpty)
        duplicatesChecked.put(classLoader, java.lang.Boolean.TRUE)
      else {
        val message =
          "Mutually exclusive ws-client artifacts detected on the classpath - the Akka and Pekko flavors (and the pre-1.0 artifact names ws-client-play / ws-client-play-stream) deliberately share fully-qualified class names, so which classes (implicits included) actually load depends on jar order. Exclude the conflicting artifact(s). Duplicated classes: " +
            duplicated.map { case (path, jars) => s"$path <- ${jars.mkString(", ")}" }
              .mkString("; ")

        if (strictClasspath)
          // deliberately NOT cached: strict mode must fail on every lookup
          throw new CequenceWSException(
            s"$message (failing because $StrictClasspathConfigKey = true)"
          )
        else {
          logger.warn(
            s"$message Set $StrictClasspathConfigKey = true to fail fast instead."
          )
          duplicatesChecked.put(classLoader, java.lang.Boolean.TRUE)
        }
      }
    }

  private def strictClasspath: Boolean = {
    val config = ConfigFactory.load()
    config.hasPath(StrictClasspathConfigKey) && config.getBoolean(StrictClasspathConfigKey)
  }

  def provider(engineId: Option[String] = None): WSClientEngineProvider =
    provider(engineId, defaultClassLoader)

  def provider(
    engineId: Option[String],
    classLoader: ClassLoader
  ): WSClientEngineProvider =
    provider(engineId, Set.empty[EngineCapability], classLoader)

  /**
   * Resolves a provider among those supporting all the required capabilities.
   */
  def provider(
    engineId: Option[String],
    requiredCapabilities: Set[EngineCapability]
  ): WSClientEngineProvider =
    provider(engineId, requiredCapabilities, defaultClassLoader)

  def provider(
    engineId: Option[String],
    requiredCapabilities: Set[EngineCapability],
    classLoader: ClassLoader
  ): WSClientEngineProvider = {
    val all = providers(classLoader)
    val eligible = all.filter(p => requiredCapabilities.subsetOf(p.capabilities))

    def capabilitiesInfo =
      if (requiredCapabilities.isEmpty) ""
      else s" supporting ${requiredCapabilities.mkString(", ")}"

    def byId(id: String) = {
      val provider = all
        .find(_.engineId == id)
        .getOrElse(
          throw new CequenceWSException(
            s"No WS client engine with the id '$id' found on the classpath. Available engines: ${idsOrNone(all)}."
          )
        )

      if (!requiredCapabilities.subsetOf(provider.capabilities))
        throw new CequenceWSException(
          s"The WS client engine '$id' does not support ${requiredCapabilities
              .diff(provider.capabilities)
              .mkString(", ")}. Available engines$capabilitiesInfo: ${idsOrNone(eligible)}."
        )

      provider
    }

    engineId
      .orElse(configuredEngineId)
      .map(byId)
      .getOrElse(autoSelect(eligible, capabilitiesInfo))
  }

  private def configuredEngineId: Option[String] = {
    val config = ConfigFactory.load()
    if (config.hasPath(ConfigKey)) Some(config.getString(ConfigKey)) else None
  }

  private def autoSelect(
    all: Seq[WSClientEngineProvider],
    capabilitiesInfo: String = ""
  ): WSClientEngineProvider =
    all match {
      case Nil =>
        throw new CequenceWSException(
          s"No WS client engine$capabilitiesInfo found on the classpath. Add a backend module such as ws-client-play-akka (Akka) or ws-client-play-pekko (Pekko)."
        )

      case Seq(single) =>
        single

      case multiple =>
        // providers are sorted by descending priority
        val top = multiple.head
        val ties = multiple.filter(_.priority == top.priority)
        if (ties.size > 1)
          throw new CequenceWSException(
            s"Multiple WS client engines with the same priority found: ${idsOrNone(ties)}. " +
              s"Choose one explicitly - via an engine id, the config key '$ConfigKey', or a system property -D$ConfigKey=<id>."
          )
        top
    }

  private def idsOrNone(providers: Seq[WSClientEngineProvider]) =
    if (providers.isEmpty) "(none)" else providers.map(_.engineId).mkString(", ")

  private def defaultClassLoader =
    Option(Thread.currentThread().getContextClassLoader)
      .getOrElse(classOf[WSClientEngineProvider].getClassLoader)
}
