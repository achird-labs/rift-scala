package rift.pure

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.Path
import javax.net.ssl.SSLContext

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.bridge.{InterceptRule, TruststoreFormat}

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

  def exportTruststore(
      format: TruststoreFormat,
      password: String,
      path: Path
  ): Either[RiftError, Unit] =
    catchRiftError(connector.exportTruststore(format, password, path))

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

  def forward(target: String): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.forward(target))

  def redirectTo(imposter: Imposter): Either[RiftError, InterceptRule] =
    catchRiftError(underlying.redirectTo(imposter.connector))
