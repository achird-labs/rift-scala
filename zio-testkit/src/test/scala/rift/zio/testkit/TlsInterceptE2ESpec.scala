package rift.zio.testkit

import java.lang.System as JSystem
import java.net.{ProxySelector, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import javax.net.ssl.SSLContext

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import rift.dsl.*
import rift.zio.InterceptHandle
// Renamed: `ZIOSpec` inherits a member called `aspects`, which outranks even an explicit import of
// this object (inherited definitions bind tighter than imports), so the bare name would resolve to
// zio-test's `Chunk` of default aspects instead. See the note on `rift.zio.testkit.aspects`.
import rift.zio.testkit.aspects as riftAspects

/** End-to-end gate for `intercept.tlsIntercept`: a stock `java.net.http.HttpClient` — given no
  * proxy and no `SSLContext` of its own — is routed through the in-process TLS-MITM proxy purely by
  * the JVM-global state the fixture installs, and that state is handed back untouched afterwards.
  *
  * This is the tier-2 path from the README's trust table; the unit specs pin the save/restore logic
  * itself, and only this spec proves the real `System` / `ProxySelector` / `SSLContext` wiring.
  *
  * The host is a `.invalid` name that can never resolve, so a passing request is proof the proxy
  * served it rather than the network.
  */
object TlsInterceptE2ESpec extends ZIOSpecDefault:

  private val Url = "https://datafile.rift.invalid/v1/config"
  private val Body = """{"served-by":"rift"}"""

  /** Every test scopes its own fixture rather than sharing one: the release path is half of what is
    * under test here, and an engine allows only one successful intercept for its lifetime.
    */
  private def withFixture[A](config: InterceptTestConfig)(
      use: InterceptHandle => Task[A]
  ): Task[A] =
    ZIO.scoped {
      for
        env <- intercept.tlsIntercept(config).build
        result <- use(env.get[InterceptHandle])
      yield result
    }

  private def fetch(url: String): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val client = HttpClient.newHttpClient()
      val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      (response.statusCode(), response.body())
    }

  def spec = suite("TlsInterceptE2ESpec")(
    test("routes a stock HttpClient through the proxy via the default ProxySelector") {
      withFixture(
        InterceptTestConfig(proxyProps = false, proxySelector = true, sslContext = true)
      ) { handle =>
        for
          _ <- handle.rule().serve(ok.header("Content-Type", "application/json").json(Body))
          result <- fetch(Url)
          (status, body) = result
        yield assertTrue(status == 200, body.contains("served-by"))
      }
    },
    test("routes a stock HttpClient through the proxy via the https.proxy* properties") {
      withFixture(InterceptTestConfig(proxyProps = true, sslContext = true)) { handle =>
        for
          _ <- handle.rule().serve(ok.json(Body))
          result <- fetch(Url)
          (status, body) = result
        yield assertTrue(status == 200, body.contains("served-by"))
      }
    },
    test("serves a rejected-credentials response through the fixture") {
      // The failure scenario these fixtures exist for: the SUT's real client talks to what it
      // believes is its upstream and gets a 401 back. TCP-level faults are deliberately not
      // exercised here — an intercept `serve` rule only carries an `is` response, and the engine
      // rejects a `fault` one outright (`use redirectTo(imposter)`), so injecting one is a
      // property of imposters rather than of this fixture.
      withFixture(
        InterceptTestConfig(proxyProps = false, proxySelector = true, sslContext = true)
      ) { handle =>
        for
          _ <- handle.rule().serve(status(401).json("""{"error":"unauthorized"}"""))
          result <- fetch(Url)
          (code, body) = result
        yield assertTrue(code == 401, body.contains("unauthorized"))
      }
    },
    test("restores every global it touched when the scope closes") {
      // Seeded with a pre-existing value rather than starting from absent: restoring "nothing" is
      // the easy half, and the property is otherwise unset in this JVM, so without the sentinel
      // this would only ever prove restore-to-absent.
      val sentinel = "proxy.example.invalid"
      ZIO.acquireReleaseWith(ZIO.succeed(JSystem.setProperty("https.proxyHost", sentinel)))(_ =>
        ZIO.succeed(JSystem.clearProperty("https.proxyHost"))
      ) { _ =>
        for
          priorSelector <- ZIO.succeed(ProxySelector.getDefault)
          priorContext <- ZIO.succeed(SSLContext.getDefault)
          insideHost <- withFixture(
            InterceptTestConfig(proxyProps = true, proxySelector = true, sslContext = true)
          )(_ => ZIO.succeed(Option(JSystem.getProperty("https.proxyHost"))))
          afterSelector <- ZIO.succeed(ProxySelector.getDefault)
          afterContext <- ZIO.succeed(SSLContext.getDefault)
          afterHost <- ZIO.succeed(Option(JSystem.getProperty("https.proxyHost")))
        yield assertTrue(
          // The fixture really did displace the sentinel while it was open …
          insideHost.exists(_ != sentinel),
          // … and handed all three globals back exactly as it found them.
          afterHost.contains(sentinel),
          afterSelector eq priorSelector,
          afterContext eq priorContext
        )
      }
    }
    // Global JVM state and a fixed set of engine ports: these must not race each other, nor the
    // other specs sharing this forked JVM.
  ) @@ sequential @@ withLiveClock @@ timeout(3.minutes) @@ riftAspects.embeddedOnly
