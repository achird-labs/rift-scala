package rift.pure

import java.net.{InetSocketAddress, URI}
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

  /** Start a rule for `host`: `.when(match)` then a terminal `serve/forward/redirectTo`. */
  def rule(host: String): InterceptRuleBuilder = new InterceptRuleBuilder(connector.rule(host))

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
  * is already blocking, so there is no accumulate-then-replay step — `when` just narrows the
  * underlying facade builder directly, and a terminal registers the rule.
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
