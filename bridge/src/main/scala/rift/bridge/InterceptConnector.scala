package rift.bridge

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import scala.jdk.CollectionConverters.*

import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.model.Predicate

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

  /** A `ProxySelector` routing traffic through this proxy — the standard way to point a whole JVM
    * at it (`ProxySelector.setDefault`, or `HttpClient.Builder.proxy`) when the SUT's client is not
    * individually configurable.
    */
  def proxySelector: ProxySelector = FacadeBoundary.run(underlying.proxySelector())

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
  * returns it.
  *
  * `when` buffers rather than calling the facade, because the facade's `when` **assigns** its
  * predicate list instead of appending (rift-java-core 0.2.0) — replaying N matches onto it would
  * keep only the last and silently widen the rule. Every buffered match is instead flattened into
  * one `RequestMatch` and handed to the facade exactly once per terminal, so a chain of `when`
  * clauses reads as their conjunction and no clause is dropped.
  *
  * That single `when` is issued **unconditionally**, including for an empty clause list. Forks of
  * one builder still share the underlying facade builder, whose predicate field survives a
  * terminal; skipping the call for a zero-`when` rule would let it inherit whatever a sibling
  * assigned, silently narrowing a catch-all. Assigning every time makes each terminal register
  * exactly its own builder's clauses — this correctness rests on the facade assigning, so if a
  * future rift-java makes `when` append, this must mint a fresh facade builder per terminal (as
  * `rift.zio`/`rift.cats` already do) rather than reset one.
  *
  * That holds for terminals run from one thread. Assign-then-read is two operations on the shared
  * facade builder, so concurrent terminals on forks of the same builder can interleave and register
  * one rule's clauses against another's action. Fork and register from a single thread, or use
  * `rift.zio`/`rift.cats`, whose per-terminal fresh builder makes them immune.
  */
final class InterceptRuleBuilder private[bridge] (
    underlying: JInterceptRuleBuilder,
    matches: Vector[RequestMatch] = Vector.empty
):

  def when(matching: RequestMatch): InterceptRuleBuilder =
    new InterceptRuleBuilder(underlying, matches :+ matching)

  /** The facade builder carrying every buffered clause as one combined `when`. An empty clause list
    * is assigned too — see the class doc: skipping the call would let a zero-`when` rule inherit a
    * sibling fork's predicates.
    */
  private def applied: JInterceptRuleBuilder =
    val combined = new RequestMatch:
      def predicates: Vector[Predicate] = matches.flatMap(_.predicates)
    underlying.when(FacadeEncode.requestMatch(combined))

  /** Serve a canned response. Translates an `is` response — status/headers/body plus the
    * `_behaviors`/`_rift` constructs the facade's `IsSpec` can express (waits, decorate, repeat,
    * shellTransform, templating, latency/error/tcp faults) — to the facade's `IsSpec`. What
    * `IsSpec` cannot express (`copy`/`lookup`, unknown behavior keys, `_rift.script`, a
    * proxy/inject/fault response) is rejected by `FacadeEncode.isSpec` rather than silently
    * degraded — use `redirectTo` for full stub fidelity there.
    */
  def serve(response: ResponseBuilder): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.serve(FacadeEncode.isSpec(response))))

  /** Transparently forward matched traffic to `target` (a base URL / host). */
  def forward(target: String): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.forward(target)))

  /** Redirect matched traffic to a local imposter — the full-fidelity path (the imposter carries
    * arbitrary stubs/behaviors), and what the datafile-hot-swap pattern (#7) uses.
    */
  def redirectTo(imposter: ImposterConnector): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.redirectTo(imposter.jImposter)))
