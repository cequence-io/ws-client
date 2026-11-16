package io.cequence.wsclient

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.{URL, URLClassLoader}
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

// a class whose only superclass is Object, so a loader without the test classpath as its parent
// can still load a copy of it from the test classes directory
final class ClassLoadersProbe

class ClassLoadersSpec extends AnyWordSpec with Matchers {

  private val probe = classOf[ClassLoadersProbe]
  private val own = probe.getClassLoader

  private def classesDirLoader() =
    new URLClassLoader(Array(probe.getProtectionDomain.getCodeSource.getLocation), null)

  "ClassLoaders.contextOr" should {

    "return the context class loader when it is the class's own" in {
      onThread(own)(ClassLoaders.contextOr(probe)) should be theSameInstanceAs own
    }

    "return a context class loader that delegates to the class's own" in {
      val child = new URLClassLoader(Array.empty[URL], own)
      onThread(child)(ClassLoaders.contextOr(probe)) should be theSameInstanceAs child
    }

    "return the class's own loader when the context class loader cannot load the class" in {
      val foreign = new URLClassLoader(Array.empty[URL], null)
      onThread(foreign)(ClassLoaders.contextOr(probe)) should be theSameInstanceAs own
    }

    "return the class's own loader on a thread without a context class loader" in {
      onThread(null)(ClassLoaders.contextOr(probe)) should be theSameInstanceAs own
    }

    "return the class's own loader when the context class loader loads its own copy of the class" in {
      val copying = classesDirLoader()
      Class.forName(probe.getName, false, copying) should not be theSameInstanceAs(probe)
      onThread(copying)(ClassLoaders.contextOr(probe)) should be theSameInstanceAs own
    }

    "return the class's own loader when the context class loader fails linking its copy" in {
      // this spec's superclass is not on the classes directory: loading the copy throws a
      // NoClassDefFoundError (a LinkageError)
      val spec = classOf[ClassLoadersSpec]
      a[LinkageError] should be thrownBy Class.forName(spec.getName, false, classesDirLoader())
      onThread(classesDirLoader())(
        ClassLoaders.contextOr(spec)
      ) should be theSameInstanceAs own
    }

    "return the system class loader for a bootstrap class on a thread without one" in {
      onThread(null)(ClassLoaders.contextOr(classOf[String])) should be theSameInstanceAs
        ClassLoader.getSystemClassLoader
    }
  }

  private def onThread[T](contextClassLoader: ClassLoader)(body: => T): T = {
    val result = new AtomicReference[Try[T]]()
    val thread = new Thread(new Runnable { override def run(): Unit = result.set(Try(body)) })
    thread.setContextClassLoader(contextClassLoader)
    thread.start()
    thread.join(30000)
    Option(result.get).getOrElse(fail("the thread did not finish within 30 s")).get
  }
}
