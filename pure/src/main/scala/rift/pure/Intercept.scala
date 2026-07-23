package rift.pure

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.model.Port
import rift.bridge.{CaMaterial, InterceptRule, TruststoreFormat}

/** The plain-Scala surface over `rift.bridge.InterceptConnector` (DESIGN.md §5.11) —
  * `Either[RiftError, _]`-shaped, obtained from `Rift.intercept`/`Rift.interceptUnsafe`. `close()`
  * tears the proxy down. `proxyUri` is pure (mirroring `Imposter.uri`); everything that touches the
  * running proxy is blocking and wrapped in `catchRiftError`.
  */
final class Intercept private[pure] (connector: rift.bridge.InterceptConnector)
    extends AutoCloseable:

  def proxyUri: URI = connector.proxyUri

  def address: Either[RiftError, InetSocketAddress] = catchRiftError(connector.address)

  /** A `ProxySelector` routing a whole JVM's HTTP through this proxy (`ProxySelector.setDefault`,
    * or `HttpClient.Builder.proxy`).
    */
  def proxySelector: Either[RiftError, ProxySelector] = catchRiftError(connector.proxySelector)

  /** Start a rule for `host`: `.when(match)` then a terminal `serve/forward/redirectTo`. */
  def rule(host: String): InterceptRuleBuilder = new InterceptRuleBuilder(connector.rule(host))

  /** Start an all-hosts rule — matches every intercepted host, for a SUT proxied JVM-wide whose
    * upstream host isn't known at authoring time. `rule(host)` scopes to one host instead.
    */
  def rule(): InterceptRuleBuilder = new InterceptRuleBuilder(connector.rule())

  def rules: Either[RiftError, Vector[InterceptRule]] = catchRiftError(connector.rules)

  def clearRules(): Either[RiftError, Unit] = catchRiftError(connector.clearRules())

  def caPem: Either[RiftError, String] = catchRiftError(connector.caPem)

  def sslContext: Either[RiftError, SSLContext] = catchRiftError(connector.sslContext)

  /** Like `sslContext`, plus the platform's own trust anchors — for a SUT whose whole truststore is
    * replaced, which `sslContext` alone would leave unable to reach any genuinely-trusted host.
    */
  def sslContextWithSystemCAs: Either[RiftError, SSLContext] =
    catchRiftError(connector.sslContextWithSystemCAs)

  /** The generated CA's certificate and private key, for persisting a CA across runs. `None` for a
    * caller-supplied CA (the engine does not echo it back) and always `None` for an attached
    * listener, whose CA material the facade never captures.
    */
  def caMaterial: Either[RiftError, Option[CaMaterial.Pem]] =
    catchRiftError(connector.caMaterial)

  def exportTruststore(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): Either[RiftError, Unit] =
    catchRiftError(connector.exportTruststore(format, password, path))

  /** `exportTruststore` plus the platform's own trust anchors. */
  def exportTruststoreWithSystemCAs(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): Either[RiftError, Unit] =
    catchRiftError(connector.exportTruststoreWithSystemCAs(format, password, path))

  def close(): Unit = connector.close()

/** Straight delegate over `rift.bridge.InterceptRuleBuilder`: unlike the ZIO/Cats wrappers, `pure`
  * is already blocking, so there is no effect-level accumulate-then-replay step — `when` goes
  * straight to the bridge builder, which buffers the clauses and hands the facade their conjunction
  * as a single `when` at terminal time, so no earlier `when` is dropped (issue #82).
  */
final class InterceptRuleBuilder private[pure] (underlying: rift.bridge.InterceptRuleBuilder):

  def when(matching: RequestMatch): InterceptRuleBuilder =
    new InterceptRuleBuilder(underlying.when(matching))

  def serve(response: ResponseBuilder): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.serve(response))

  /** Transparently forward matched traffic to a **local imposter port**.
    *
    * The engine's forward action is `ForwardTarget { port: u16 }`, proxied to
    * `http://127.0.0.1:{port}` — a port is the whole destination, so there is no cross-host
    * forwarding to express. Composes with the port accessors: `rule(host).forward(imposter.port)`.
    */
  def forward(port: Port): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.forward(port))

  /** Forward matched traffic to the **port** named by `target` — the facade's own signature, kept
    * for parity. Prefer `forward(port: Port)`, which cannot express the part that gets discarded.
    *
    * `target` takes the facade's `host:port` form (e.g. `"real.example.com:443"`), but only the
    * port survives: the facade sends `{"forward":{"port":N}}` and the engine proxies to
    * `http://127.0.0.1:{port}`, so the host component of `target` is parsed and discarded. That is
    * deliberate upstream, not a dropped field: the engine's forward action carries no host.
    *
    * A malformed target (notably a scheme-carrying URL, `"https://real.example.com"`) is rejected
    * before any rule is registered. That rejection throws rather than returning a `Left`: an
    * unparseable target is a programming error, not an engine failure, so `catchRiftError` does not
    * map it.
    */
  def forward(target: String): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.forward(target))

  def redirectTo(imposter: Imposter): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.redirectTo(imposter.connector))
