package rift.cats

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import _root_.cats.effect.Async

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.model.Port
import rift.bridge.{CaMaterial, InterceptConnector, InterceptRule, TruststoreFormat}

/** The Cats Effect surface over `rift.bridge.InterceptConnector` (DESIGN.md §5.6). Obtained from
  * `Rift.intercept` as a `Resource[F, InterceptHandle[F]]`. Release clears the rules this handle
  * registered; it does **not** stop the proxy, which is bound until the owning engine closes, and
  * only one *successful* `intercept` is allowed per engine — so acquiring a second one on a shared
  * engine fails with an `IllegalStateException` **defect**, not a typed error. See
  * `rift.bridge.RiftConnector.intercept`.
  *
  * `proxyUri` is pure (mirroring `ImposterHandle.uri`); everything that touches the running proxy
  * is an effect.
  */
trait InterceptHandle[F[_]]:
  def proxyUri: URI
  def address: F[InetSocketAddress]

  /** A `ProxySelector` routing a whole JVM's HTTP through this proxy (`ProxySelector.setDefault`,
    * or `HttpClient.Builder.proxy`).
    */
  def proxySelector: F[ProxySelector]
  def rule(host: String): InterceptRuleBuilder[F]

  /** An all-hosts rule — matches every intercepted host, for a SUT proxied JVM-wide whose upstream
    * host isn't known at authoring time. `rule(host)` scopes to one host instead.
    */
  def rule(): InterceptRuleBuilder[F]
  def rules: F[Vector[InterceptRule]]
  def clearRules: F[Unit]
  def caPem: F[String]
  def sslContext: F[SSLContext]

  /** Like `sslContext`, plus the platform's own trust anchors — for a SUT whose whole truststore is
    * replaced, which `sslContext` alone would leave unable to reach any genuinely-trusted host.
    */
  def sslContextWithSystemCAs: F[SSLContext]
  def exportTruststore(format: TruststoreFormat, password: String, path: Path): F[Unit]

  /** `exportTruststore` plus the platform's own trust anchors. */
  def exportTruststoreWithSystemCAs(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): F[Unit]

  /** The generated CA's certificate and private key, for persisting a CA across runs. `None` for a
    * caller-supplied CA (the engine does not echo it back) and always `None` for an attached
    * listener, whose CA material the facade never captures.
    */
  def caMaterial: F[Option[CaMaterial.Pem]]

/** `.when(match)` then a terminal `serve/forward/redirectTo`. The facade builder is stateful, so
  * the wrapper stays pure by deferring every facade call to the terminal effect: `when` accumulates
  * the matches, and the rule is materialized inside `blockingF` when a terminal runs. The
  * accumulated matches are replayed onto the bridge builder, which buffers them and hands the
  * facade their conjunction as a single `when` — so chaining behaves exactly like the blocking
  * bridge builder and no earlier `when` is dropped (issue #82).
  */
trait InterceptRuleBuilder[F[_]]:
  def when(matching: RequestMatch): InterceptRuleBuilder[F]
  def serve(response: ResponseBuilder): F[InterceptRule]

  /** Transparently forward matched traffic to a **local imposter port**.
    *
    * The engine's forward action is `ForwardTarget { port: u16 }`, proxied to
    * `http://127.0.0.1:{port}` — a port is the whole destination, so there is no cross-host
    * forwarding to express. Composes with the port accessors: `rule(host).forward(imposter.port)`.
    */
  def forward(port: Port): F[InterceptRule]

  /** Forward matched traffic to the **port** named by `target` — the facade's own signature, kept
    * for parity. Prefer `forward(port: Port)`, which cannot express the part that gets discarded.
    *
    * `target` takes the facade's `host:port` form (e.g. `"real.example.com:443"`), but only the
    * port survives: the facade sends `{"forward":{"port":N}}` and the engine proxies to
    * `http://127.0.0.1:{port}`, so the host component of `target` is parsed and discarded. That is
    * deliberate upstream, not a dropped field: the engine's forward action carries no host.
    *
    * A malformed target (notably a scheme-carrying URL, `"https://real.example.com"`) is rejected
    * before any rule is registered. That rejection is a **defect**, not a `RiftError`: an
    * unparseable target is a programming error, so it never reaches the typed error channel.
    */
  def forward(target: String): F[InterceptRule]
  def redirectTo(imposter: ImposterHandle[F]): F[InterceptRule]

private[cats] final class InterceptHandleLive[F[_]: Async](connector: InterceptConnector)
    extends InterceptHandle[F]:
  def proxyUri: URI = connector.proxyUri
  def address: F[InetSocketAddress] = blockingF(connector.address)
  def proxySelector: F[ProxySelector] = blockingF(connector.proxySelector)
  def rule(host: String): InterceptRuleBuilder[F] =
    InterceptRuleBuilderLive[F](connector, Some(host))
  def rule(): InterceptRuleBuilder[F] = InterceptRuleBuilderLive[F](connector, None)
  def rules: F[Vector[InterceptRule]] = blockingF(connector.rules)
  def clearRules: F[Unit] = blockingF(connector.clearRules())
  def caPem: F[String] = blockingF(connector.caPem)
  def sslContext: F[SSLContext] = blockingF(connector.sslContext)
  def sslContextWithSystemCAs: F[SSLContext] = blockingF(connector.sslContextWithSystemCAs)
  def caMaterial: F[Option[CaMaterial.Pem]] = blockingF(connector.caMaterial)
  def exportTruststoreWithSystemCAs(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): F[Unit] =
    blockingF(connector.exportTruststoreWithSystemCAs(format, password, path))
  def exportTruststore(format: TruststoreFormat, password: String, path: Path): F[Unit] =
    blockingF(connector.exportTruststore(format, password, path))

private[cats] final case class InterceptRuleBuilderLive[F[_]: Async](
    connector: InterceptConnector,
    host: Option[String],
    matches: Vector[RequestMatch] = Vector.empty
) extends InterceptRuleBuilder[F]:

  def when(matching: RequestMatch): InterceptRuleBuilder[F] = copy(matches = matches :+ matching)

  private def built: rift.bridge.InterceptRuleBuilder =
    // `h => connector.rule(h)` rather than the eta-expanded `connector.rule`: the two `rule`
    // overloads make a bare method reference ambiguous.
    matches.foldLeft(host.fold(connector.rule())(h => connector.rule(h)))((builder, matching) =>
      builder.when(matching)
    )

  def serve(response: ResponseBuilder): F[InterceptRule] = blockingF(built.serve(response))

  def forward(port: Port): F[InterceptRule] = blockingF(built.forward(port))

  def forward(target: String): F[InterceptRule] = blockingF(built.forward(target))

  def redirectTo(imposter: ImposterHandle[F]): F[InterceptRule] =
    imposter match
      case live: ImposterHandleLive[?] => blockingF(built.redirectTo(live.connector))
      case _ =>
        Async[F].raiseError(
          RiftError.InvalidDefinition(
            "redirectTo requires a rift.cats ImposterHandle from this engine",
            None
          )
        )
