package io.cequence.wsclient.service.spi

import com.typesafe.config.ConfigFactory
import io.cequence.wsclient.domain.{CequenceWSException, SiteBinding}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import scala.concurrent.Await
import scala.concurrent.duration._

class WSClientEngineRegistrySpec extends AnyWordSpec with Matchers {

  private val servicesResource =
    "META-INF/services/" + classOf[WSClientEngineProvider].getName

  /**
   * Delegates class loading to the test class loader but answers the provider services lookup
   * with the given content only - lets a test control exactly which providers ServiceLoader
   * sees.
   */
  private class FixedServicesClassLoader(servicesContent: String)
      extends ClassLoader(getClass.getClassLoader) {

    override def getResources(name: String): java.util.Enumeration[URL] =
      if (name == servicesResource) {
        val file = File.createTempFile("ws-client-engine-providers", ".txt")
        file.deleteOnExit()
        Files.write(file.toPath, servicesContent.getBytes(StandardCharsets.UTF_8))
        Collections.enumeration(Collections.singletonList(file.toURI.toURL))
      } else
        super.getResources(name)
  }

  "WSClientEngineRegistry" should {

    "discover all providers ordered by descending priority" in {
      val providers = WSClientEngineRegistry.providers
      providers.map(_.engineId) shouldBe Seq("dummy-b", "dummy-a")
    }

    "select a provider by explicit engine id" in {
      WSClientEngineRegistry.provider(Some("dummy-a")).engineId shouldBe "dummy-a"
    }

    "fail on an unknown engine id listing the available ones" in {
      val exception = intercept[CequenceWSException] {
        WSClientEngineRegistry.provider(Some("bogus"))
      }
      exception.getMessage should include("bogus")
      exception.getMessage should include("dummy-b")
      exception.getMessage should include("dummy-a")
    }

    "auto-select the provider with the highest priority" in {
      WSClientEngineRegistry.provider().engineId shouldBe "dummy-b"
    }

    "prefer the engine id from the config / system property over auto-selection" in {
      System.setProperty(WSClientEngineRegistry.ConfigKey, "dummy-a")
      ConfigFactory.invalidateCaches()
      try {
        WSClientEngineRegistry.provider().engineId shouldBe "dummy-a"
      } finally {
        System.clearProperty(WSClientEngineRegistry.ConfigKey)
        ConfigFactory.invalidateCaches()
      }
    }

    "create an engine via apply" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some("dummy-a"))
      engine shouldBe a[DummyEngine]
      engine.copy() shouldBe a[DummyEngine]
      engine.close()
    }

    "serve multiple sites from one engine instance" in {
      val engine = WSClientEngineRegistry(TransportSettings(), Some("dummy-a"))

      val siteA = SiteBinding("http://site-a")
      val siteB = SiteBinding("http://site-b")

      val responseA = Await.result(engine.execGETRich(siteA, "ping"), 5.seconds)
      val responseB = Await.result(engine.execGETRich(siteB, "ping"), 5.seconds)

      responseA.response.get.json shouldBe Json.obj("coreUrl" -> "http://site-a")
      responseB.response.get.json shouldBe Json.obj("coreUrl" -> "http://site-b")

      engine.close()
    }

    "fail outputStreamed when no provider supports output streaming" in {
      val exception = intercept[CequenceWSException] {
        WSClientEngineRegistry.outputStreamed(TransportSettings())
      }
      exception.getMessage should include("OutputStreaming")
    }

    "fail on an empty classpath with an actionable message" in {
      val exception = intercept[CequenceWSException] {
        WSClientEngineRegistry.provider(None, new FixedServicesClassLoader(""))
      }
      exception.getMessage should include("No WS client engine found")
      exception.getMessage should include("ws-client-play-akka")
    }

    "fail on a priority tie naming the tied engines" in {
      val tieLoader = new FixedServicesClassLoader(
        classOf[DummyTieProvider1].getName + "\n" + classOf[DummyTieProvider2].getName + "\n"
      )
      val exception = intercept[CequenceWSException] {
        WSClientEngineRegistry.provider(None, tieLoader)
      }
      exception.getMessage should include("tie-1")
      exception.getMessage should include("tie-2")
    }

    "detect duplicated flavored classes (mixed akka/pekko or old+renamed artifacts)" in {
      val duplicated = WSClientEngineRegistry.duplicatedFlavoredClasses(duplicatingLoader())

      duplicated.keySet shouldBe Set(duplicatedClassPath)
      duplicated(duplicatedClassPath) should have size 2

      // a clean classpath (this module has neither flavor) reports nothing
      WSClientEngineRegistry.duplicatedFlavoredClasses(
        getClass.getClassLoader
      ) shouldBe empty
    }

    "only warn on duplicated flavored classes by default" in {
      // providers are still resolved; the hazard is reported via a log warning
      WSClientEngineRegistry.providers(duplicatingLoader()).map(_.engineId) shouldBe Seq(
        "dummy-b",
        "dummy-a"
      )
    }

    s"fail on duplicated flavored classes when ${WSClientEngineRegistry.StrictClasspathConfigKey} is set" in {
      System.setProperty(WSClientEngineRegistry.StrictClasspathConfigKey, "true")
      ConfigFactory.invalidateCaches()
      try {
        val exception = intercept[CequenceWSException] {
          WSClientEngineRegistry.providers(duplicatingLoader())
        }
        exception.getMessage should include("Mutually exclusive ws-client artifacts")
        exception.getMessage should include(
          WSClientEngineRegistry.StrictClasspathConfigKey
        )

        // strict failures are not swallowed by the once-per-loader cache
        val loader = duplicatingLoader()
        intercept[CequenceWSException](WSClientEngineRegistry.providers(loader))
        intercept[CequenceWSException](WSClientEngineRegistry.providers(loader))
      } finally {
        System.clearProperty(WSClientEngineRegistry.StrictClasspathConfigKey)
        ConfigFactory.invalidateCaches()
      }
    }
  }

  private val duplicatedClassPath =
    "io/cequence/wsclient/service/ws/PlayWSClientEngine.class"

  // simulates e.g. ws-client-play (pre-1.0) and ws-client-play-akka on one classpath;
  // a fresh instance per use, since the registry's duplicate check caches per class loader
  private def duplicatingLoader() = new ClassLoader(getClass.getClassLoader) {
    override def getResources(name: String): java.util.Enumeration[URL] =
      if (name == duplicatedClassPath)
        Collections.enumeration(
          java.util.Arrays.asList(
            new URL("file:/fake/ws-client-play_2.13-0.8.1.jar"),
            new URL("file:/fake/ws-client-play-akka_2.13-1.0.0.jar")
          )
        )
      else
        super.getResources(name)
  }
}
