package rift.bridge

import java.net.{InetSocketAddress, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import scala.jdk.CollectionConverters.*

import rift.dsl.{RequestMatch, ResponseBuilder}

import io.github.achirdlabs.rift.{
  Intercept as JIntercept,
  InterceptRuleBuilder as JInterceptRuleBuilder
}

/** Blocking, throwing (`RiftError`) handle on the engine's TLS-MITM intercept proxy — mirrors
  * `rift.zio.InterceptHandle` (DESIGN.md §5.3) 1:1 but blocking. At most one per engine: a second
  * `RiftConnector.intercept` is an engine-side error, surfaced (not hidden). `close()` tears the
  * proxy down; the trust material (`caPem`/`sslContext`/`exportTruststore`) is what a SUT's client
  * uses to trust the minted leaf certs.
  */
final class InterceptConnector private[bridge] (underlying: JIntercept) extends AutoCloseable:

  def proxyUri: URI = FacadeBoundary.run(underlying.uri())

  def address: InetSocketAddress = FacadeBoundary.run(underlying.address())

  /** Start a rule for `host`: `.when(match)` then a terminal `serve/forward/redirectTo`. */
  def rule(host: String): InterceptRuleBuilder =
    FacadeBoundary.run(InterceptRuleBuilder(underlying.rule().host(host)))

  /** Start an all-hosts rule — the facade's catch-all form, with `host` left unset so the rule
    * matches every intercepted host (facade `InterceptRuleBuilder.host`: `null = catch-all`). For a
    * SUT proxied JVM-wide whose upstream host isn't known (or worth enumerating) at authoring time.
    */
  def rule(): InterceptRuleBuilder =
    FacadeBoundary.run(InterceptRuleBuilder(underlying.rule()))

  def rules: Vector[InterceptRule] =
    FacadeBoundary.run(underlying.rules().asScala.toVector.map(InterceptRule.fromJava))

  def clearRules(): Unit = FacadeBoundary.run(underlying.clearRules())

  def caPem: String = FacadeBoundary.run(underlying.trust().caPem())

  def sslContext: SSLContext = FacadeBoundary.run(underlying.trust().sslContext())

  def exportTruststore(format: TruststoreFormat, password: String, path: Path): Unit =
    FacadeBoundary.run(underlying.trust().exportTruststore(format.toJava, password, path))

  def close(): Unit = FacadeBoundary.run(underlying.close())

/** Scala mirror of rift-java's `InterceptRuleBuilder`: `host` is already set by
  * `InterceptConnector.rule`, then `.when(match)` narrows and a terminal registers the rule and
  * returns it. The underlying facade builder is stateful/fluent — each step wraps the same
  * instance.
  */
final class InterceptRuleBuilder private[bridge] (underlying: JInterceptRuleBuilder):

  def when(matching: RequestMatch): InterceptRuleBuilder =
    FacadeBoundary.run(InterceptRuleBuilder(underlying.when(FacadeEncode.requestMatch(matching))))

  /** Serve a canned response. Translates a plain `is` response (status/headers/body) to the
    * facade's `IsSpec`; a response carrying `_behaviors`/`_rift`/proxy/inject/fault is rejected
    * (`FacadeEncode.isSpec`) rather than silently degraded — use `redirectTo` for full fidelity.
    */
  def serve(response: ResponseBuilder): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(underlying.serve(FacadeEncode.isSpec(response))))

  /** Transparently forward matched traffic to `target` (a base URL / host). */
  def forward(target: String): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(underlying.forward(target)))

  /** Redirect matched traffic to a local imposter — the full-fidelity path (the imposter carries
    * arbitrary stubs/behaviors), and what the datafile-hot-swap pattern (#7) uses.
    */
  def redirectTo(imposter: ImposterConnector): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(underlying.redirectTo(imposter.jImposter)))
