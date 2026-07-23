package rift.zio

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import zio.*

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.bridge.{InterceptConnector, InterceptRule, TruststoreFormat}

/** The ZIO surface over `rift.bridge.InterceptConnector` (DESIGN.md §5.3). Obtained from
  * `Rift.intercept` as a scoped resource — the proxy is torn down on scope release. `proxyUri` is
  * pure (mirroring `ImposterHandle.uri`); everything that touches the running proxy is an effect.
  */
trait InterceptHandle:
  def proxyUri: URI
  def address: IO[RiftError, InetSocketAddress]

  /** A `ProxySelector` routing a whole JVM's HTTP through this proxy (`ProxySelector.setDefault`,
    * or `HttpClient.Builder.proxy`).
    */
  def proxySelector: IO[RiftError, ProxySelector]
  def rule(host: String): InterceptRuleBuilder

  /** An all-hosts rule — matches every intercepted host, for a SUT proxied JVM-wide whose upstream
    * host isn't known at authoring time. `rule(host)` scopes to one host instead.
    */
  def rule(): InterceptRuleBuilder
  def rules: IO[RiftError, Chunk[InterceptRule]]
  def clearRules: IO[RiftError, Unit]
  def caPem: IO[RiftError, String]
  def sslContext: IO[RiftError, SSLContext]
  def exportTruststore(format: TruststoreFormat, password: String, path: Path): IO[RiftError, Unit]

/** `.when(match)` then a terminal `serve/forward/redirectTo`. The facade builder is stateful, so
  * the ZIO wrapper stays pure by deferring every facade call to the terminal effect: `when`
  * accumulates the matches, and the rule is materialized inside `blockingIO` when a terminal runs.
  * The accumulated matches are replayed onto the bridge builder, which buffers them and hands the
  * facade their conjunction as a single `when` — so chaining behaves exactly like the blocking
  * bridge builder and no earlier `when` is dropped (issue #82).
  */
trait InterceptRuleBuilder:
  def when(matching: RequestMatch): InterceptRuleBuilder
  def serve(response: ResponseBuilder): IO[RiftError, InterceptRule]

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
  def forward(target: String): IO[RiftError, InterceptRule]
  def redirectTo(imposter: ImposterHandle): IO[RiftError, InterceptRule]

private[zio] final case class InterceptHandleLive(connector: InterceptConnector)
    extends InterceptHandle:
  def proxyUri: URI = connector.proxyUri
  def address: IO[RiftError, InetSocketAddress] = blockingIO(connector.address)
  def proxySelector: IO[RiftError, ProxySelector] = blockingIO(connector.proxySelector)
  def rule(host: String): InterceptRuleBuilder = InterceptRuleBuilderLive(connector, Some(host))
  def rule(): InterceptRuleBuilder = InterceptRuleBuilderLive(connector, None)
  def rules: IO[RiftError, Chunk[InterceptRule]] =
    blockingIO(Chunk.fromIterable(connector.rules))
  def clearRules: IO[RiftError, Unit] = blockingIO(connector.clearRules())
  def caPem: IO[RiftError, String] = blockingIO(connector.caPem)
  def sslContext: IO[RiftError, SSLContext] = blockingIO(connector.sslContext)
  def exportTruststore(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): IO[RiftError, Unit] =
    blockingIO(connector.exportTruststore(format, password, path))

private[zio] final case class InterceptRuleBuilderLive(
    connector: InterceptConnector,
    host: Option[String],
    matches: Vector[RequestMatch] = Vector.empty
) extends InterceptRuleBuilder:

  def when(matching: RequestMatch): InterceptRuleBuilder = copy(matches = matches :+ matching)

  private def built: rift.bridge.InterceptRuleBuilder =
    // `h => connector.rule(h)` rather than the eta-expanded `connector.rule`: the two `rule`
    // overloads make a bare method reference ambiguous.
    matches.foldLeft(host.fold(connector.rule())(h => connector.rule(h)))((builder, matching) =>
      builder.when(matching)
    )

  def serve(response: ResponseBuilder): IO[RiftError, InterceptRule] =
    blockingIO(built.serve(response))

  def forward(target: String): IO[RiftError, InterceptRule] =
    blockingIO(built.forward(target))

  def redirectTo(imposter: ImposterHandle): IO[RiftError, InterceptRule] =
    imposter match
      case live: ImposterHandleLive => blockingIO(built.redirectTo(live.connector))
      case _ =>
        ZIO.fail(
          RiftError.InvalidDefinition(
            "redirectTo requires a rift.zio ImposterHandle from this engine",
            None
          )
        )
