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
  InterceptRuleBuilder as JInterceptRuleBuilder,
  InterceptRuleSet as JInterceptRuleSet
}

/** Blocking, throwing (`RiftError`) handle on the engine's TLS-MITM intercept proxy — mirrors
  * `rift.zio.InterceptHandle` (DESIGN.md §5.3) 1:1 but blocking. One intercept may be open on an
  * engine at a time: a second `RiftConnector.intercept` while this one is open is refused by the
  * facade with `IllegalStateException`, a defect rather than a `RiftError` — see
  * `RiftConnector.intercept`.
  *
  * `close()` on a handle this client started stops the listener — the engine drops its rules and CA
  * and frees the port — so a later `RiftConnector.intercept` on the same engine succeeds with a
  * fresh CA (rift-java >= 0.3.5). A closed handle refuses `rule`/`rules`/`clearRules` and the trust
  * material (`caPem`/`sslContext`/`exportTruststore`) with `IllegalStateException`; the address
  * accessors still answer, and so does `caMaterial`, with the stopped listener's CA. On an
  * **attached** handle (`interceptAttach`, or a container's pre-booted listener) `close()` only
  * clears the rules and the listener keeps running.
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
    FacadeBoundary.run(InterceptRuleBuilder(ruleSet.rule().host(host)))

  /** Start an all-hosts rule — the facade's catch-all form, with `host` left unset so the rule
    * matches every intercepted host (facade `InterceptRuleBuilder.host`: `null = catch-all`). For a
    * SUT proxied JVM-wide whose upstream host isn't known (or worth enumerating) at authoring time.
    */
  def rule(): InterceptRuleBuilder =
    FacadeBoundary.run(InterceptRuleBuilder(ruleSet.rule()))

  // `rule()` is declared on `InterceptRuleSet` since rift-java 0.3.6; calling it through that type
  // is what FacadeParitySpec's (c2) bytecode check sees as the capability it covers.
  private val ruleSet: JInterceptRuleSet = underlying

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
    *   - an **attached** listener attached without a CA — `interceptAttach`, and `intercept` on a
    *     container transport with a pre-booted listener. The facade's attach can carry a supplied
    *     CA and hand it back (rift-java 0.3.5); rift-scala does not pass one yet (#205).
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

  /** Serve a canned response — a numeric status, headers, and a text or JSON body. A header written
    * several times is sent once per value (engine ≥ 0.18.0; rift-java refuses it on an older one),
    * with case-variant spellings grouped under the first.
    *
    * That is the whole of what the engine's serve action carries, so anything else an `is` response
    * can express (every `_behaviors` and `_rift` construct, a binary body) is rejected by
    * `FacadeEncode.isSpec` rather than accepted and then dropped in transit — use `redirectTo` for
    * full stub fidelity there.
    */
  def serve(response: ResponseBuilder): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.serve(FacadeEncode.isSpec(response))))

  /** Transparently forward matched traffic to a **local imposter port**: the port-only wire
    * (`{"forward":{"port":N}}`), which the engine proxies to `http://127.0.0.1:{port}` and every
    * engine accepts. Composes with the port accessors: `rule(host).forward(imposter.port)`.
    *
    * From engine 0.20.0 every forwarded or redirected request reaches the imposter with the SUT's
    * own `Host` header, not `127.0.0.1:<port>`.
    */
  def forward(port: Port): InterceptRule = forward(FacadeEncode.forwardTarget(port))

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
    * A target with a path, query or user info, or a port outside 1-65535, is rejected with an
    * `IllegalArgumentException` naming the target before any rule is registered.
    */
  def forward(target: String): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.forward(target)))

  /** Redirect matched traffic to a local imposter — the full-fidelity path (the imposter carries
    * arbitrary stubs/behaviors), and what the datafile-hot-swap pattern (#7) uses.
    */
  def redirectTo(imposter: ImposterConnector): InterceptRule =
    FacadeBoundary.run(InterceptRule.fromJava(applied.redirectTo(imposter.jImposter)))
