package rift.zio.testkit

import java.lang.System as JSystem
import java.net.{InetSocketAddress, ProxySelector}
import java.nio.file.{Files, Path}
import java.security.KeyStore
import javax.net.ssl.SSLContext

import scala.jdk.CollectionConverters.*

import zio.*

import rift.RiftError
import rift.bridge.{CaMaterial, InterceptConfig}
import rift.zio.{InterceptHandle, Rift}

/** Where the intercept proxy's CA comes from when a fixture builds one.
  *
  * The choice is really "which trust tier is the system under test on": a client the test
  * constructs itself can be handed `InterceptHandle.sslContext` directly and needs no CA here at
  * all, whereas a client that reads `javax.net.ssl.trustStore` at first TLS init can only trust a
  * CA the *build* already put in a truststore before the JVM forked — which is what the build
  * properties name.
  */
enum CaSource:

  /** Always load the build-generated CA named by [[intercept.DefaultCaPathProperty]]. Fails when
    * the build did not wire it, rather than silently falling back to a CA nothing trusts.
    */
  case BuildProps

  /** Let the engine mint an ephemeral CA. Nothing on the machine trusts it, so pair this with
    * `sslContext = true` (or `InterceptHandle.sslContext` on a client you build yourself).
    */
  case Generated

  /** The build's CA when the build wired one, an engine-generated one otherwise — so the same suite
    * runs both under the `sbt-rift` plugin and in a plain `sbt test`.
    *
    * The fallback half inherits [[Generated]]'s caveat: without the plugin nothing on the machine
    * trusts the resulting CA, so a suite that relies on this needs `sslContext = true` (or a client
    * built from `InterceptHandle.sslContext`) to survive the plain-`sbt test` case.
    */
  case BuildPropsOrGenerated

  /** CA material the caller already holds. */
  case Explicit(material: CaMaterial)

/** The resolved outcome of a [[CaSource]], split out so the decision is testable without a keystore
  * on disk or an engine to boot (the shape `RiftTestKit.transportFromEnv` uses).
  */
private[testkit] enum CaPlan:
  case FromBuildProps
  case EngineGenerated
  case Fixed(material: CaMaterial)

/** How [[intercept.tlsIntercept]] builds its fixture: where the proxy listens, which CA it mints
  * leaf certificates from, and which pieces of JVM-global state get pointed at it.
  *
  * The wiring flags are off by default except `proxyProps`, because each one mutates process-wide
  * state that every other test in the same JVM observes — a fixture should install the least that
  * makes the system under test route through the proxy.
  */
final case class InterceptTestConfig(
    intercept: InterceptConfig = InterceptConfig(),
    ca: CaSource = CaSource.BuildPropsOrGenerated,
    proxyProps: Boolean = true,
    includeHttpProxy: Boolean = false,
    proxySelector: Boolean = false,
    sslContext: Boolean = false
)

/** Test fixtures for the TLS-MITM intercept proxy: scoped wiring of the JVM-global state that
  * non-injectable HTTP clients read, the build-generated CA those clients' truststore already
  * trusts, and a one-call layer that assembles both around an embedded engine.
  *
  * `InterceptHandle` already exposes the raw material (`sslContext`, `proxySelector`, `caPem`,
  * `exportTruststore`), which is all a client the test *constructs* needs. What is missing, and
  * what lives here, is the case where the system under test builds its own HTTP client deep inside
  * a third-party SDK: then the only reachable seam is process-global, and the fixture has to put it
  * back afterwards. Three tiers, weakest coupling first:
  *
  *   1. '''injectable''' — hand `handle.sslContext` / `handle.proxySelector` to the client builder;
  *      nothing here is needed.
  *   1. '''runtime defaults''' — `systemProxySelector` + `systemSslContext`, for a client that
  *      reads `ProxySelector.getDefault` / `SSLContext.getDefault` when it is constructed (a stock
  *      `java.net.http.HttpClient` does).
  *   1. '''boot-time properties''' — `systemProxyProps` plus a truststore the build put on disk
  *      before the JVM forked, for a client that reads `javax.net.ssl.trustStore` once at first TLS
  *      init (Apache HttpClient, and most vendor SDKs built on it). The `sbt-rift` plugin
  *      (`RiftTlsPlugin`) generates that truststore and sets the properties this object reads.
  *
  * '''Process-global state.''' Every `system*` helper here mutates state shared by the whole JVM
  * and restores it when the `Scope` closes — including on failure and interruption. Nesting is
  * safe, since each scope puts back whatever it displaced (`SystemWiringSpec` pins that), but
  * racing is not: a suite using these must be `@@ sequential`, and a shared fixture wants
  * `provideShared`. `tlsIntercept` builds its own engine per layer, so the engine's
  * one-intercept-for-its-lifetime rule is not reachable through it — it applies only when you call
  * `Rift.intercept` twice on an engine of your own.
  */
object intercept:

  /** The system property naming the build-generated CA keystore. `RiftTlsPlugin` sets it on the
    * forked test JVM; it is public because it is the contract between the plugin and this object.
    */
  val DefaultCaPathProperty: String = "rift.ca.p12"

  /** The system property holding [[DefaultCaPathProperty]]'s password. */
  val DefaultCaPasswordProperty: String = "rift.ca.p12.password"

  /** The keystore alias `RiftTlsPlugin` generates the CA under. */
  val DefaultCaAlias: String = "rift-ca"

  /** Matches `RiftTlsPlugin`'s own default. The keystore holds a throwaway CA generated per
    * checkout under `target/`, so this is a placeholder, not a secret.
    */
  private val DefaultCaPassword: String = "changeit"

  /** Points `https.proxyHost`/`https.proxyPort` (and, when `includeHttp`, the `http.*` pair) at the
    * intercept proxy for the duration of the scope, then restores exactly what was there before —
    * an absent property is restored to absent, not to an empty string.
    *
    * This is the tier-3 seam: the JDK's own default `ProxySelector` reads these properties, as do
    * Apache HttpClient and the vendor SDKs built on it.
    */
  def systemProxyProps(
      handle: InterceptHandle,
      includeHttp: Boolean = false
  ): ZIO[Scope, RiftError, Unit] =
    handle.address.flatMap { address =>
      ZIO.foreachDiscard(proxyPropUpdates(address, includeHttp)) { (key, value) =>
        scopedProperty(key, value)
      }
    }

  /** Installs the handle's `ProxySelector` as the JVM default for the scope, restoring the previous
    * default (possibly `null`, which is a legal default meaning "no selector") on close.
    */
  def systemProxySelector(handle: InterceptHandle): ZIO[Scope, RiftError, Unit] =
    handle.proxySelector.flatMap { selector =>
      scopedGlobal[ProxySelector](ProxySelector.getDefault, ProxySelector.setDefault(_), selector)
    }

  /** Installs an `SSLContext` trusting the intercept's CA *and* the platform's own anchors as the
    * JVM default for the scope, restoring the previous default on close.
    *
    * `sslContextWithSystemCAs`, not `sslContext`: this replaces the default for everything in the
    * JVM, and the bare intercept context would leave any genuinely-trusted host unreachable.
    */
  def systemSslContext(handle: InterceptHandle): ZIO[Scope, RiftError, Unit] =
    handle.sslContextWithSystemCAs.flatMap { context =>
      scopedGlobal[SSLContext](SSLContext.getDefault, SSLContext.setDefault(_), context)
    }

  /** Loads the build-generated CA keystore named by the given system properties as [[CaMaterial]],
    * so the proxy mints leaf certificates from the same CA the forked JVM's truststore trusts.
    *
    * Delegates the PEM extraction to `CaMaterial.fromKeyStore` rather than reaching into the
    * keystore here — that conversion is the facade's job and already has one implementation.
    *
    * A missing `pathProp` fails: there is no defensible default path. A missing `passwordProp`
    * falls back to the plugin's own default, which is a fixed test-only constant rather than a
    * secret worth demanding.
    */
  def caFromBuildProps(
      pathProp: String = DefaultCaPathProperty,
      passwordProp: String = DefaultCaPasswordProperty,
      alias: String = DefaultCaAlias
  ): IO[RiftError, CaMaterial] =
    for
      path <- ZIO
        .fromOption(sys.props.get(pathProp).filter(_.nonEmpty))
        .orElseFail(
          RiftError.InvalidDefinition(
            s"system property '$pathProp' is not set -- enable RiftTlsPlugin (sbt-rift) on this " +
              s"module, or use CaSource.Generated / CaSource.Explicit",
            None
          )
        )
      supplied = sys.props.get(passwordProp).filter(_.nonEmpty)
      material <- loadCaKeyStore(
        path,
        supplied.getOrElse(DefaultCaPassword),
        alias,
        Option.when(supplied.isEmpty)(passwordProp)
      )
    yield material

  /** An embedded engine, a TLS-MITM intercept on it, and the JVM wiring `config` asks for — all
    * released in reverse order when the layer's scope closes.
    */
  val tlsIntercept: ZLayer[Any, RiftError, InterceptHandle] = tlsIntercept(InterceptTestConfig())

  def tlsIntercept(config: InterceptTestConfig): ZLayer[Any, RiftError, InterceptHandle] =
    ZLayer.scoped {
      for
        ca <- resolveCa(config.ca)
        engine <- Rift.embedded.build.map(_.get[Rift])
        handle <- engine.intercept(config.intercept.copy(ca = ca))
        _ <- ZIO.when(config.proxyProps)(systemProxyProps(handle, config.includeHttpProxy))
        _ <- ZIO.when(config.proxySelector)(systemProxySelector(handle))
        _ <- ZIO.when(config.sslContext)(systemSslContext(handle))
      yield handle
    }

  /** The proxy system properties an intercept at `address` implies. Pure and `private[testkit]` so
    * `SystemWiringSpec` can pin the key names without touching this JVM's real properties.
    *
    * `http.nonProxyHosts` is cleared, not merely left alone. The JDK's default selector applies it
    * to the `https` entry too, and its own default (`localhost|127.*|[::1]|0.0.0.0|[::0]`) covers
    * exactly the addresses a locally-hosted upstream uses — so leaving it would let such a system
    * under test bypass the proxy entirely while this fixture reported success and routed nothing.
    * It restores through the same scoped path as the rest.
    */
  private[testkit] def proxyPropUpdates(
      address: InetSocketAddress,
      includeHttp: Boolean
  ): Map[String, String] =
    val host = address.getHostString
    val port = address.getPort.toString
    val https =
      Map("https.proxyHost" -> host, "https.proxyPort" -> port, "http.nonProxyHosts" -> "")
    if includeHttp then https ++ Map("http.proxyHost" -> host, "http.proxyPort" -> port)
    else https

  /** Resolves a [[CaSource]] against whether the build actually wired a CA. Pure, so the default's
    * "build CA when present, engine CA otherwise" behaviour is testable without either.
    */
  private[testkit] def caPlan(source: CaSource, buildPropsPresent: Boolean): CaPlan = source match
    case CaSource.BuildProps => CaPlan.FromBuildProps
    case CaSource.Generated => CaPlan.EngineGenerated
    case CaSource.BuildPropsOrGenerated =>
      if buildPropsPresent then CaPlan.FromBuildProps else CaPlan.EngineGenerated
    case CaSource.Explicit(material) => CaPlan.Fixed(material)

  /** Swaps one JVM-global slot for the duration of the scope and puts the previous occupant back.
    *
    * `read` is by-name so the previous value is sampled at acquisition rather than when the effect
    * is described. The release cannot *fail* — `Scope` gives a finalizer nowhere to put a typed
    * error — but the underlying setter can still throw (a `SecurityException`, say), and that is
    * the one failure whose consequence outlives the test: the slot stays pinned to this fixture's
    * value and every later test in the JVM silently runs against it. So a throwing restore dies
    * with a message that names what was left behind, rather than as a bare `SecurityException`
    * whose connection to a fixture teardown nobody would guess.
    */
  private[testkit] def scopedGlobal[A](
      read: => A,
      write: A => Unit,
      value: A
  ): ZIO[Scope, Nothing, Unit] =
    ZIO
      .acquireRelease(ZIO.succeed {
        val prior = read
        write(value)
        prior
      })(prior =>
        ZIO
          .attempt(write(prior))
          .orDieWith(error =>
            new IllegalStateException(
              s"rift testkit could not restore JVM-global state to '$prior' -- later tests in " +
                s"this JVM may observe this fixture's intercept proxy or TLS defaults instead",
              error
            )
          )
      )
      .unit

  private def scopedProperty(key: String, value: String): ZIO[Scope, Nothing, Unit] =
    scopedGlobal[Option[String]](
      Option(JSystem.getProperty(key)),
      writeProperty(key),
      Some(value)
    )

  private def writeProperty(key: String)(value: Option[String]): Unit =
    val _ = value.fold(JSystem.clearProperty(key))(v => JSystem.setProperty(key, v))

  /** Acts on the [[caPlan]] decision. `private[testkit]` rather than `private` so the dispatch is
    * gated directly: `caPlan` alone proves only that the right branch was *chosen*, not that the
    * branch does what it says — a `Fixed` case that dropped its material would satisfy the former.
    */
  private[testkit] def resolveCa(source: CaSource): IO[RiftError, Option[CaMaterial]] =
    ZIO.succeed(sys.props.get(DefaultCaPathProperty).exists(_.nonEmpty)).flatMap { present =>
      caPlan(source, present) match
        case CaPlan.FromBuildProps => caFromBuildProps().asSome
        case CaPlan.EngineGenerated => ZIO.none
        case CaPlan.Fixed(material) => ZIO.some(material)
    }

  /** `path` stays a `String` until inside the effect: `Path.of` throws `InvalidPathException` on
    * some inputs, and evaluating it outside would make that one failure mode a defect while every
    * other way of naming an unusable keystore returns a typed `InvalidDefinition`.
    */
  private def loadCaKeyStore(
      path: String,
      password: String,
      alias: String,
      defaultedFrom: Option[String]
  ): IO[RiftError, CaMaterial] =
    ZIO
      .attemptBlocking {
        val secret = password.toCharArray
        val store = KeyStore.getInstance("PKCS12")
        val stream = Files.newInputStream(Path.of(path))
        try store.load(stream, secret)
        finally stream.close()
        if !store.containsAlias(alias) then
          throw new IllegalArgumentException(
            s"no alias '$alias' in the keystore (found: ${store.aliases.asScala.mkString(", ")})"
          )
        // `fromKeyStore` copies the password, so the array above may be dropped here.
        CaMaterial.fromKeyStore(store, secret)
      }
      .mapError { error =>
        // Naming the password fallback separates "the property was never set" from "the property
        // is set but wrong" — otherwise both surface as the same opaque keystore error.
        val provenance =
          defaultedFrom.fold("")(prop => s" (no '$prop' set, so the default password was tried)")
        val detail = Option(error.getMessage).getOrElse(error.toString)
        RiftError.InvalidDefinition(
          s"could not load CA keystore '$path'$provenance: $detail",
          Some(error)
        )
      }
