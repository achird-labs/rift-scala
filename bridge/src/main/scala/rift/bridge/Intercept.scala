package rift.bridge

import rift.RiftError
import rift.json.Json

import io.github.achirdlabs.rift.{
  InterceptOptions as JInterceptOptions,
  InterceptRule as JInterceptRule,
  RuleKind as JRuleKind,
  TruststoreFormat as JTruststoreFormat
}

/** How an intercept rule routes a matched request — mirrors rift-java's `RuleKind`. */
enum RuleKind:
  case Serve, Forward, Redirect

  private[bridge] def toJava: JRuleKind = this match
    case RuleKind.Serve => JRuleKind.SERVE
    case RuleKind.Forward => JRuleKind.FORWARD
    case RuleKind.Redirect => JRuleKind.REDIRECT

object RuleKind:
  /** `if/else` rather than a `match`: the compiler proves a 3-constant Java-enum `match` exhaustive
    * and rejects a defensive wildcard as unreachable, but a runtime jar newer than the compile-time
    * one could still hand us an unmodeled constant — surface that as a modeled `CommunicationError`
    * (and cover `null`) rather than a `MatchError` defect.
    */
  private[bridge] def fromJava(k: JRuleKind): RuleKind =
    if k == JRuleKind.SERVE then Serve
    else if k == JRuleKind.FORWARD then Forward
    else if k == JRuleKind.REDIRECT then Redirect
    else throw RiftError.CommunicationError(s"unknown intercept RuleKind from engine: $k", None)

/** Truststore container format for `InterceptConnector.exportTruststore` — mirrors rift-java's
  * `TruststoreFormat`.
  */
enum TruststoreFormat:
  case Pkcs12, Jks

  private[bridge] def toJava: JTruststoreFormat = this match
    case TruststoreFormat.Pkcs12 => JTruststoreFormat.PKCS12
    case TruststoreFormat.Jks => JTruststoreFormat.JKS

/** The CA certificate + private key (PEM) an intercept proxy uses to mint per-host leaf certs. A
  * committed pair (rather than the default generated CA) lets a SUT trust one stable root across
  * runs — the fixed-CA case the ledger sample (#7) needs.
  */
final case class CaMaterial(certPem: String, keyPem: String)

/** Scala-idiomatic mirror of rift-java's `InterceptOptions` — where the TLS-MITM intercept proxy
  * listens and which CA it uses. `ca = None` ⇒ the engine generates an ephemeral CA
  * (`generateCa()`); `Some(CaMaterial)` ⇒ the committed PEM pair.
  */
final case class InterceptConfig(
    host: String = "127.0.0.1",
    port: Int = 0,
    ca: Option[CaMaterial] = None
):
  private[bridge] def toOptions: JInterceptOptions =
    val builder = JInterceptOptions.builder()
    builder.host(host)
    builder.port(port)
    ca match
      case Some(CaMaterial(certPem, keyPem)) => builder.ca(certPem, keyPem)
      case None => builder.generateCa()
    builder.build()

/** A registered intercept rule as the engine reports it (`Intercept.rules()`): the target `host`,
  * the routing `kind`, and the `raw` wire JSON (predicates + action) the engine stored. Decoded via
  * the D2 raw-JSON seam rather than a field-by-field translation.
  */
final case class InterceptRule(host: String, kind: RuleKind, raw: Json)

object InterceptRule:
  private[bridge] def fromJava(jr: JInterceptRule): InterceptRule =
    InterceptRule(jr.host(), RuleKind.fromJava(jr.kind()), FacadeDecode.json(jr.raw()))
