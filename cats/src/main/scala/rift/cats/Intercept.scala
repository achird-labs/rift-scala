package rift.cats

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import _root_.cats.effect.Async

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.bridge.{InterceptConnector, InterceptRule, TruststoreFormat}

/** The Cats Effect surface over `rift.bridge.InterceptConnector` (DESIGN.md §5.6). Obtained from
  * `Rift.intercept` as a `Resource[F, InterceptHandle[F]]` — the proxy is torn down on resource
  * release. `proxyUri` is pure (mirroring `ImposterHandle.uri`); everything that touches the
  * running proxy is an effect.
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
  def exportTruststore(format: TruststoreFormat, password: String, path: Path): F[Unit]

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

  /** Transparently forward matched traffic to the **port** named by `target`.
    *
    * `target` takes the facade's `host:port` form (e.g. `"real.example.com:443"`), but only the
    * port survives — traffic goes to the matched host on that port, and the host component of
    * `target` is parsed and discarded.
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
