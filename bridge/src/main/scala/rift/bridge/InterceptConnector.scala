package rift.bridge

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.model.{Port, Predicate}

import io.github.achirdlabs.rift.{
  Intercept as JIntercept,
  InterceptRuleBuilder as JInterceptRuleBuilder
}

/** Blocking, throwing (`RiftError`) handle on the engine's TLS-MITM intercept proxy — mirrors
  * `rift.zio.InterceptHandle` (DESIGN.md §5.3) 1:1 but blocking. At most one per engine, for the
  * engine's whole lifetime: a second `RiftConnector.intercept` is refused by the facade with
  * `IllegalStateException`, a defect rather than a `RiftError`, and `close()` does not lift the
  * refusal — see `RiftConnector.intercept`.
  *
  * `close()` clears every rule this handle registered — `rules` reads back empty — which is what
  * `EmbeddedSmokeSpec` observes to prove the effect surfaces' finalizers do real work rather than
  * merely running. It does **not** stop the listener: the facade's `Intercept.close()` is exactly
  * `clearRules()` and the transport exposes no stop at all, so the proxy stays bound for the
  * engine's lifetime and the handle stays usable — `caPem` still answers and a further `rule(...)`
  * still registers. Closing the engine is what frees the port.
  *
  * The trust material (`caPem`/`sslContext`/`exportTruststore`) is what a SUT's client uses to
  * trust the minted leaf certs.
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

  /** Like `sslContext`, plus the platform's own trust anchors.
    *
    * `sslContext` trusts the intercept CA and nothing else, so a SUT given it can reach the proxy
    * but no genuinely-trusted host — fine when the SUT's client is configured per-call, wrong when
    * its whole truststore is replaced. This variant covers the second case.
    */
  def sslContextWithSystemCAs: SSLContext =
    FacadeBoundary.run(underlying.trust().sslContextWithSystemCAs())

  def exportTruststore(format: TruststoreFormat, password: String, path: Path): Unit =
    FacadeBoundary.run(underlying.trust().exportTruststore(format.toJava, password, path))

  /** `exportTruststore` plus the platform's own trust anchors — the file form of
    * `sslContextWithSystemCAs`, for a SUT that takes a truststore path rather than an `SSLContext`.
    */
  def exportTruststoreWithSystemCAs(format: TruststoreFormat, password: String, path: Path): Unit =
    FacadeBoundary.run(
      underlying.trust().exportTruststoreWithSystemCAs(format.toJava, password, path)
    )

  /** The generated CA's certificate **and private key**, for persisting a CA across runs.
    *
    * The pair is exactly what `CaMaterial.Pem` takes, so a readback feeds straight into the next
    * `InterceptConfig`. `None` in two cases, both structural rather than transient:
    *   - a caller-supplied CA, which the engine does not echo back; and
    *   - an **attached** listener — `interceptAttach`, and `intercept` on a container transport
    *     with a pre-booted listener. The facade only captures CA material from a start response,
    *     and its attach constructor leaves the field null, so this is permanently empty there.
    */
  def caMaterial: Option[CaMaterial.Pem] =
    FacadeBoundary.run(
      underlying.caMaterial().toScala.map(m => CaMaterial.Pem(m.certPem(), m.keyPem()))
    )

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

  /** Transparently forward matched traffic to a **local imposter port**.
    *
    * This is the honest signature: the engine's forward action is `ForwardTarget { port: u16 }` and
    * it proxies to `http://127.0.0.1:{port}`, so a port is the whole destination — there is no
    * cross-host forwarding to express. Composes with the port accessors:
    * `rule(host).forward(imposter.port)`.
    *
    * `redirectTo` is the richer alternative when the destination imposter is in hand.
    */
  def forward(port: Port): InterceptRule = forward(FacadeEncode.forwardTarget(port))

  /** Forward matched traffic to the **port** named by `target` — the facade's own signature, kept
    * for parity. Prefer `forward(port: Port)`, which cannot express the part that gets discarded.
    *
    * `target` takes the facade's `host:port` form (e.g. `"real.example.com:443"`), but only the
    * port survives: the facade sends `{"forward":{"port":N}}` and the rule's own host — the one
    * given to `rule(host)` — is what the engine matches on. The host component of `target` is
    * parsed and discarded, so it documents intent and nothing more. That is deliberate upstream,
    * not a dropped field: the engine's forward action is `ForwardTarget { port: u16 }`
    * (`crates/rift-http-proxy/src/intercept_rules.rs`, engine v0.16.0) and it proxies to a URL it
    * builds as `http://127.0.0.1:{port}` (`intercept.rs`) — there is no host to carry.
    *
    * The port is taken from the substring after the last `':'` and parsed as an int, so a
    * scheme-carrying URL (`"https://real.example.com"`) is rejected with an
    * `IllegalArgumentException` naming the target, thrown while evaluating the argument — before
    * any rule is registered.
    */
  def forward(target: String): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.forward(target)))

  /** Redirect matched traffic to a local imposter — the full-fidelity path (the imposter carries
    * arbitrary stubs/behaviors), and what the datafile-hot-swap pattern (#7) uses.
    */
  def redirectTo(imposter: ImposterConnector): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.redirectTo(imposter.jImposter)))
