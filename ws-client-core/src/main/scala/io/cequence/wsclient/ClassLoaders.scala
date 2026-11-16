package io.cequence.wsclient

/**
 * Chooses the class loader to find a library's resources, configuration and services through
 * on threads whose context class loader may not see the library (since 1.2.2).
 *
 * ws-client resolves engine providers, its configuration and the actor systems its engines own
 * through it, and a library on top of ws-client can load its own configuration the same way:
 * {{{
 * ConfigFactory.load(ClassLoaders.contextOr(classOf[MyService]), "my-library.conf")
 * }}}
 */
object ClassLoaders {

  /**
   * The thread's context class loader when it resolves `cls` to the same class, else the class
   * loader of `cls` itself (the system class loader for a bootstrap class).
   *
   * A context class loader that cannot see `cls` is unset or belongs to someone else - sbt's
   * own loader on the pool threads of an in-process `run`, a thread a foreign framework
   * created: `ServiceLoader`, Typesafe Config (`ConfigFactory.load()` reads only the context
   * class loader and throws on a thread without one) and Akka / Pekko find none of the
   * application's providers, configuration files or classes through it. Wherever the context
   * class loader does see `cls` (a flat classpath, the application threads of sbt, Play dev
   * mode and servlet containers) it is the result, so resources in a child of the loader of
   * `cls` stay visible. Deliberately one loader, not a union of both. Safe to call from any
   * thread.
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
