package rift.zio

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import zio.*

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.model.Port
import rift.bridge.{CaMaterial, InterceptConnector, InterceptRule, TruststoreFormat}

/** The ZIO surface over `rift.bridge.InterceptConnector` (DESIGN.md §5.3). Obtained from
  * `Rift.intercept` as a scoped resource. Scope release stops a proxy this client started (its
  * rules and CA go with it) and frees the engine for a new `intercept`; on an attached or
  * container-booted listener it only clears the rules. Only one intercept may be open on an engine
  * at a time, so acquiring a second while the first is open fails with an `IllegalStateException`
  * **defect**, not a typed `RiftError`. See `rift.bridge.RiftConnector.intercept`.
  *
  * `proxyUri` is pure (mirroring `ImposterHandle.uri`); everything that touches the running proxy
  * is an effect.
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

  /** Like `sslContext`, plus the platform's own trust anchors — for a SUT whose whole truststore is
    * replaced, which `sslContext` alone would leave unable to reach any genuinely-trusted host.
    */
  def sslContextWithSystemCAs: IO[RiftError, SSLContext]
  def exportTruststore(format: TruststoreFormat, password: String, path: Path): IO[RiftError, Unit]

  /** `exportTruststore` plus the platform's own trust anchors. */
  def exportTruststoreWithSystemCAs(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): IO[RiftError, Unit]

  /** The generated CA's certificate and private key, for persisting a CA across runs. `None` for a
    * caller-supplied CA (the engine does not echo it back) and always `None` for an attached
    * listener, whose CA material the facade never captures.
    */
  def caMaterial: IO[RiftError, Option[CaMaterial.Pem]]

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

  /** Transparently forward matched traffic to a **local imposter port**: the port-only wire
    * (`{"forward":{"port":N}}`), which the engine proxies to `http://127.0.0.1:{port}` and every
    * engine accepts. Composes with the port accessors: `rule(host).forward(imposter.port)`.
    *
    * From engine 0.20.0 every forwarded or redirected request reaches the imposter with the SUT's
    * own `Host` header, not `127.0.0.1:<port>`.
    */
  def forward(port: Port): IO[RiftError, InterceptRule]

  /** Forward matched traffic to `target` — a port, `host:port`, or `http(s)://host:port` (the
    * facade's own signature, rift-java 0.3.6).
    *
    * A loopback target over http (`"9443"`, `"localhost:9443"`, `"http://127.0.0.1:9443"`) keeps
    * the port-only wire and works on any engine. A named host, or `https`, is forwarded **to that
    * host** and needs engine >= 0.20.0: an older engine refuses the rule with
    * `RiftError.InvalidDefinition` before anything is sent — unless the version check is off
    * (`VersionCheck.Off`) or only warns, in which case an older engine ignores the host and
    * forwards to its own machine.
    *
    * A target with a path, query or user info, or a port outside 1-65535, is rejected before any
    * rule is registered. That rejection is a **defect**, not a `RiftError`: an unparseable target
    * is a programming error, so it never reaches the typed error channel.
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
  def sslContextWithSystemCAs: IO[RiftError, SSLContext] =
    blockingIO(connector.sslContextWithSystemCAs)
  def caMaterial: IO[RiftError, Option[CaMaterial.Pem]] = blockingIO(connector.caMaterial)
  def exportTruststoreWithSystemCAs(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): IO[RiftError, Unit] =
    blockingIO(connector.exportTruststoreWithSystemCAs(format, password, path))
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

  def forward(port: Port): IO[RiftError, InterceptRule] =
    blockingIO(built.forward(port))

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
