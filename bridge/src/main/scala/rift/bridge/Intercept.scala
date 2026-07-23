package rift.bridge

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.KeyStore

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

/** Where the intercept proxy gets the CA it mints per-host leaf certs with. A committed CA (rather
  * than the default generated one) lets a SUT trust one stable root across runs — the fixed-CA case
  * the ledger sample (#7) needs.
  *
  * One case per facade `InterceptOptions.Builder.ca` overload, so every source form is reachable
  * and none is reinterpreted on the way through.
  */
enum CaMaterial:
  /** PEM text, passed to the engine verbatim. */
  case Pem(certPem: String, keyPem: String)

  /** Paths the **engine** opens, on the engine's own host — not read here.
    *
    * The facade serializes these as path strings (`caCertPath`/`caKeyPath`), so for the connect,
    * spawn and container transports the file must exist where the engine runs, which is not
    * necessarily this JVM's filesystem. Reading them client-side and sending `Pem` would look
    * equivalent and quietly change that.
    */
  case PemFiles(certPath: Path, keyPath: Path)

  /** A keystore the facade extracts the PEM pair from. The extraction lives in the facade; doing it
    * here would be a second implementation of someone else's format handling.
    *
    * Build this with `CaMaterial.fromKeyStore`, which copies the password so the caller can zero
    * their own array immediately — the point of a `char[]` password, and impossible if the config
    * held the caller's array and read it later, at intercept-start time.
    *
    * Note this case has reference equality, unlike its siblings: `KeyStore` does not define
    * `equals`. `InterceptConfig` inherits that, so two configs built from equal-looking keystores
    * do not compare equal.
    */
  case FromKeyStore(keyStore: KeyStore, password: IArray[Char])

  /** Redacted: `caMaterial` now hands callers a real private key, and the derived rendering would
    * print it in full in any failure message, log line or REPL echo.
    */
  override def toString: String = this match
    case Pem(certPem, keyPem) =>
      s"CaMaterial.Pem(certPem = ${certPem.length} chars, keyPem = <redacted ${keyPem.length} chars>)"
    case PemFiles(certPath, keyPath) => s"CaMaterial.PemFiles($certPath, $keyPath)"
    case FromKeyStore(keyStore, password) =>
      s"CaMaterial.FromKeyStore(${keyStore.getType}, <redacted ${password.length} chars>)"

object CaMaterial:
  /** Keeps `CaMaterial(cert, key)` building the PEM case, as it did when this was a case class. */
  def apply(certPem: String, keyPem: String): CaMaterial.Pem = CaMaterial.Pem(certPem, keyPem)

  /** Copies `password`, so the caller may zero their array as soon as this returns. The config is
    * read at intercept-start time, not here, so retaining the caller's array would mean a zeroed
    * password fails much later with an unrecoverable-key error from the facade.
    */
  def fromKeyStore(keyStore: KeyStore, password: Array[Char]): CaMaterial.FromKeyStore =
    CaMaterial.FromKeyStore(keyStore, IArray.unsafeFromArray(password.clone()))

  /** The facade's `ca(byte[], byte[])` overload is `new String(bytes, UTF_8)` delegating to the
    * `String` one — PEM bytes, never DER — so this is that conversion rather than a fourth case.
    * `InterceptTranslationSpec` pins the two against each other.
    */
  def fromPemBytes(cert: IArray[Byte], key: IArray[Byte]): CaMaterial.Pem =
    CaMaterial.Pem(
      new String(IArray.genericWrapArray(cert).toArray, StandardCharsets.UTF_8),
      new String(IArray.genericWrapArray(key).toArray, StandardCharsets.UTF_8)
    )

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
      case Some(CaMaterial.Pem(certPem, keyPem)) => builder.ca(certPem, keyPem)
      case Some(CaMaterial.PemFiles(certPath, keyPath)) => builder.ca(certPath, keyPath)
      case Some(CaMaterial.FromKeyStore(keyStore, password)) =>
        // A fresh array per call: the facade reads it and drops it, and handing over the config's
        // own copy would let the facade's caller mutate what a re-used config replays next time.
        builder.ca(keyStore, IArray.genericWrapArray(password).toArray)
      case None => builder.generateCa()
    builder.build()

/** A registered intercept rule as the engine reports it (`Intercept.rules()`): the target `host`
  * (`None` for an all-hosts rule), the routing `kind`, and the `raw` wire JSON (predicates +
  * action) the engine stored. Decoded via the D2 raw-JSON seam rather than a field-by-field
  * translation.
  */
final case class InterceptRule(host: Option[String], kind: RuleKind, raw: Json)

object InterceptRule:
  /** The facade spells "no host" two different ways depending on which path produced the rule: a
    * terminal (`addServeRule`/`addForwardRule`) passes the builder's unset host straight through as
    * `null`, while the `rules()` readback (`readRule`) substitutes `""` for an absent `host` key.
    * Both mean the same all-hosts rule, so both normalize to `None` — otherwise the same rule would
    * report differently depending on how the caller obtained it, and a `null` would escape into a
    * public field.
    */
  private[bridge] def fromJava(jr: JInterceptRule): InterceptRule =
    InterceptRule(
      Option(jr.host()).filter(_.nonEmpty),
      RuleKind.fromJava(jr.kind()),
      FacadeDecode.json(jr.raw())
    )
