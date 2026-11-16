package io.cequence.wsclient.service.spi

/**
 * The class loader ws-client resolves engine providers, its configuration and engine-owned
 * actor systems through (since 1.2.2).
 */
private[wsclient] object ClassLoaders {

  /**
   * The thread's context class loader when it resolves `cls` to the same class, else the class
   * loader of `cls` itself.
   *
   * A context class loader that cannot see `cls` is unset or belongs to someone else - sbt's
   * own loader on the pool threads of an in-process `run`, a thread a foreign framework
   * created: `ServiceLoader`, Typesafe Config and Akka / Pekko find none of the application's
   * providers, `reference.conf` files or classes through it. Wherever the context class loader
   * does see `cls` (a flat classpath, the application threads of sbt, Play dev mode and
   * servlet containers) it is used as up to 1.2.1, so backends in a child of ws-client's own
   * loader stay visible.
   */
  def contextOr(cls: Class[_]): ClassLoader = {
    val own = Option(cls.getClassLoader).getOrElse(ClassLoader.getSystemClassLoader)
    Option(Thread.currentThread().getContextClassLoader) match {
      case Some(context) if (context eq own) || resolves(context, cls) => context
      case _                                                           => own
    }
  }

  private def resolves(
    loader: ClassLoader,
    cls: Class[_]
  ): Boolean =
    try Class.forName(cls.getName, false, loader) eq cls
    catch {
      // a loader that cannot load the class, or that fails linking its own copy of it
      case _: ClassNotFoundException => false
      case _: LinkageError           => false
    }
}
