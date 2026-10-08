package rift.pure

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.model.Port
import rift.bridge.{CaMaterial, ForwardTarget, InterceptRule, TruststoreFormat}

/** The plain-Scala surface over `rift.bridge.InterceptConnector` (DESIGN.md §5.11) —
  * `Either[RiftError, _]`-shaped, obtained from `Rift.intercept`/`Rift.interceptUnsafe`.
  *
  * `close()` stops a proxy this client started (its rules and CA go with it) and frees the engine
  * for a new `intercept`; on an attached or container-booted listener it only clears the rules.
  * Only one intercept may be open on an engine at a time, so a second while the first is open
  * throws an `IllegalStateException` rather than returning a `Left`, since `catchRiftError` maps
  * only `RiftError`. See `rift.bridge.RiftConnector.intercept`.
  *
  * `proxyUri` is pure (mirroring `Imposter.uri`); everything that touches the running proxy is
  * blocking and wrapped in `catchRiftError`.
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

  /** Replaces every rule with the ones `declare` stages, in one engine call (engine >= 0.20.0): a
    * request arriving meanwhile meets the old rules or the new ones, never a partial or empty set,
    * and a staged rule can sit ahead of one already installed. Declaring nothing clears the rules.
    *
    * `declare` only stages — see [[InterceptRuleSet]]. If it throws, nothing is sent and the old
    * rules stay: a staged rule's `RiftError` becomes the `Left`, anything else propagates. An older
    * engine is a `Left(InvalidDefinition)` before `declare` runs — while the version check is
    * enforcing; with it off or warning, the engine refuses the replace after `declare` instead.
    */
  def replaceRules(declare: InterceptRuleSet => Unit): Either[RiftError, Vector[InterceptRule]] =
    catchRiftError(connector.replaceRules(set => declare(new InterceptRuleSet(set))))

  /** Replaces every rule with `rules`, in order, in one engine call — for re-installing a filtered
    * or reordered `rules` readback. Engine >= 0.20.0.
    */
  def replaceRules(rules: Vector[InterceptRule]): Either[RiftError, Vector[InterceptRule]] =
    catchRiftError(connector.replaceRules(rules))

  /** Removes every installed rule equal to `rule`, keeping the others in order; `Right(false)` when
    * none matched. Reads then replaces the rules, so a rule another client adds in between is lost.
    * Engine >= 0.20.0.
    */
  def removeRule(rule: InterceptRule): Either[RiftError, Boolean] =
    catchRiftError(connector.removeRule(rule))

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

  /** Transparently forward matched traffic to a **local imposter port**: the port-only wire
    * (`{"forward":{"port":N}}`), which the engine proxies to `http://127.0.0.1:{port}` and every
    * engine accepts. Composes with the port accessors: `rule(host).forward(imposter.port)`.
    *
    * From engine 0.20.0 every forwarded or redirected request reaches the imposter with the SUT's
    * own `Host` header, not `127.0.0.1:<port>`.
    */
  def forward(port: Port): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.forward(port))

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
    * rule is registered. That rejection throws rather than returning a `Left`: an unparseable
    * target is a programming error, not an engine failure, so `catchRiftError` does not map it.
    */
  def forward(target: String): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.forward(target))

  /** Forward matched traffic to `target`'s host and port, over http or https (engine >= 0.20.0; an
    * older engine is a `Left(InvalidDefinition)` before anything is sent, unless the version check
    * is off). A built `ForwardTarget` cannot be malformed, so this never throws on its target.
    */
  def forward(target: ForwardTarget): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.forward(target))

  def redirectTo(imposter: Imposter): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.redirectTo(imposter.connector))

/** The staging set [[Intercept.replaceRules]] hands its `declare`: a terminal stages the rule and
  * returns it, and the single engine call follows `declare`. A refused rule throws its `RiftError`,
  * which `replaceRules` returns as the `Left`. Use it only inside `declare`, from one thread, and
  * start every rule from it: a rule started from the handle itself inside `declare` is not part of
  * the swap.
  */
final class InterceptRuleSet private[pure] (set: rift.bridge.InterceptRuleSet):
  def rule(host: String): StagedRule = new StagedRule(set.rule(host))

  /** An all-hosts rule — matches every intercepted host. */
  def rule(): StagedRule = new StagedRule(set.rule())

/** A rule being staged inside `replaceRules`: `.when(match)` narrows, a terminal stages it. */
final class StagedRule private[pure] (builder: rift.bridge.InterceptRuleBuilder):
  def when(matching: RequestMatch): StagedRule = new StagedRule(builder.when(matching))
  def serve(response: ResponseBuilder): InterceptRule = builder.serve(response)
  def forward(port: Port): InterceptRule = builder.forward(port)
  def forward(target: ForwardTarget): InterceptRule = builder.forward(target)
  def forward(target: String): InterceptRule = builder.forward(target)
  def redirectTo(imposter: Imposter): InterceptRule = builder.redirectTo(imposter.connector)
