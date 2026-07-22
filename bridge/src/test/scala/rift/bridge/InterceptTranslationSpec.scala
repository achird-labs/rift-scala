package rift.bridge

import munit.FunSuite

import rift.RiftError
import rift.dsl.*
import rift.json.Json

import io.github.achirdlabs.rift.{
  Imposter as JImposter,
  Intercept as JIntercept,
  InterceptRule as JInterceptRule,
  InterceptRuleBuilder as JInterceptRuleBuilder,
  InterceptTrust as JInterceptTrust,
  RuleKind as JRuleKind
}
import io.github.achirdlabs.rift.TruststoreFormat as JTruststoreFormat
import io.github.achirdlabs.rift.dsl.IsSpec as JIsSpec
import io.github.achirdlabs.rift.json.JsonValue as JJsonValue

/** CI-safe gate for the intercept surface (issue #34). Every check here is a pure Scala↔Java
  * translation — no live engine, so it runs in CI. The full engine round-trip is the
  * `assume(isEmbeddedAvailable)`-gated smoke in `EmbeddedSmokeSpec` (skipped in CI).
  */
class InterceptTranslationSpec extends FunSuite:

  // AC1 — InterceptConfig → InterceptOptions
  test("InterceptConfig.toOptions carries host/port"):
    val rendered = InterceptConfig(host = "10.0.0.1", port = 9999).toOptions.toJson.toJson
    assert(rendered.contains("10.0.0.1"), rendered)
    assert(rendered.contains("9999"), rendered)

  test("InterceptConfig.toOptions accepts committed CA material without throwing"):
    // ca = Some(CaMaterial) ⇒ builder.ca(certPem, keyPem); ca = None ⇒ generateCa()
    val withCa = InterceptConfig(ca = Some(CaMaterial("cert-pem", "key-pem"))).toOptions
    val generated = InterceptConfig().toOptions
    assert(withCa.toJson.toJson.nonEmpty)
    assert(generated.toJson.toJson.nonEmpty)

  // AC2 — enum ↔ java mappings
  test("RuleKind round-trips through the java enum"):
    RuleKind.values.foreach(k => assertEquals(RuleKind.fromJava(k.toJava), k))
    assertEquals(RuleKind.Serve.toJava, JRuleKind.SERVE)
    assertEquals(RuleKind.Forward.toJava, JRuleKind.FORWARD)
    assertEquals(RuleKind.Redirect.toJava, JRuleKind.REDIRECT)

  test("TruststoreFormat maps to the java enum"):
    assertEquals(TruststoreFormat.Pkcs12.toJava, JTruststoreFormat.PKCS12)
    assertEquals(TruststoreFormat.Jks.toJava, JTruststoreFormat.JKS)

  // AC3 — InterceptRule decode
  test("InterceptRule.fromJava decodes host, kind, and raw JSON"):
    val raw = JJsonValue.parse("""{"host":"api.example.com","serve":{}}""")
    val decoded =
      InterceptRule.fromJava(new JInterceptRule("api.example.com", JRuleKind.SERVE, raw))
    assertEquals(decoded.host, Some("api.example.com"))
    assertEquals(decoded.kind, RuleKind.Serve)
    assert(decoded.raw.render.contains("api.example.com"), decoded.raw.render)

  // An all-hosts rule reaches `fromJava` as `null` from a terminal (`addServeRule` passes the
  // builder's unset host straight through) but as `""` from the `rules()` readback (`readRule`
  // substitutes it for an absent `host` key). Both are the same rule and must decode identically —
  // and neither may leak a null into `InterceptRule.host`.
  test("InterceptRule.fromJava normalizes both all-hosts spellings to None"):
    val raw = JJsonValue.parse("""{"serve":{}}""")
    val fromTerminal = InterceptRule.fromJava(new JInterceptRule(null, JRuleKind.SERVE, raw))
    val fromReadback = InterceptRule.fromJava(new JInterceptRule("", JRuleKind.SERVE, raw))
    assertEquals(fromTerminal.host, None)
    assertEquals(fromReadback.host, None)
    assertEquals(fromTerminal, fromReadback)

  // AC4 — serve translation (plain `is` response). Assert on the engine wire JSON the translated
  // IsSpec renders (`model.Response.toJsonValue`): status, body and headers must all survive.
  test("serve translates a plain is response (status, headers, body) to an IsSpec"):
    val wire = FacadeEncode
      .isSpec(ok.header("X-Test", "hdr-val").json("""{"id":1}"""))
      .build
      .toJsonValue
      .toJson
    val js = Json.parse(wire).fold(e => fail(e.toString), identity)
    assert(js.render.contains("\"is\""), wire) // wrapped as an `is` response
    assert(wire.contains("200"), wire)
    assert(wire.contains("id"), wire)
    assert(wire.contains("X-Test") && wire.contains("hdr-val"), wire)

  test("serve translates a non-200 status and a text body"):
    val wire = FacadeEncode.isSpec(status(503).text("down")).build.toJsonValue.toJson
    assert(wire.contains("503"), wire)
    assert(wire.contains("down"), wire)

  test("serve translates a binary body (base64 survives)"):
    val bytes = Array[Byte](1, 2, 3, 4)
    val base64 = java.util.Base64.getEncoder.encodeToString(bytes)
    val wire = FacadeEncode.isSpec(ok.binary(bytes)).build.toJsonValue.toJson
    assert(wire.contains(base64), wire)

  test("serve collapses a repeated header name into one multi-valued header"):
    val wire = FacadeEncode
      .isSpec(ok.header("Set-Cookie", "cookie-a").header("Set-Cookie", "cookie-b").json("{}"))
      .build
      .toJsonValue
      .toJson
    assert(wire.contains("Set-Cookie"), wire)
    assert(wire.contains("cookie-a") && wire.contains("cookie-b"), wire)

  // AC5 — serve rejects loudly (no silent drift) for responses it cannot faithfully translate
  test("serve rejects a response carrying behaviors"):
    intercept[RiftError.InvalidDefinition] {
      FacadeEncode.isSpec(
        ok.json("""{"id":1}""").after(scala.concurrent.duration.Duration(1, "second"))
      )
    }

  test("serve rejects a response carrying a _rift extension (templated/fault/script)"):
    intercept[RiftError.InvalidDefinition] {
      FacadeEncode.isSpec(ok.json("""{"id":1}""").withErrorFault(0.5, 500, "boom"))
    }
    intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(ok.json("""{"id":1}""").templated))

  test("serve rejects a non-is response (proxy/inject/fault)"):
    intercept[RiftError.InvalidDefinition] {
      FacadeEncode.isSpec(inject("function (req) { return {}; }"))
    }

  // AC6 — all-hosts rule (issue #80). The facade keeps `host` null for a catch-all rule and
  // `.host(...)` is `Objects.requireNonNull`, so pointing the connector at a stub whose `rule()`
  // hands back a null builder makes the host step *observable*: the all-hosts path must leave that
  // builder untouched, while the host-scoped path must reach `.host(...)` and blow up on it.
  test("rule() starts an all-hosts rule — the facade's host step is never applied"):
    val stub = new RecordingIntercept
    new InterceptConnector(stub).rule()
    assertEquals(stub.ruleCalls, 1)

  test("rule(host) applies the facade's host step"):
    val stub = new RecordingIntercept
    intercept[NullPointerException](new InterceptConnector(stub).rule("api.example.com"))
    assertEquals(stub.ruleCalls, 1)

/** A facade `Intercept` that counts `rule()` calls and returns a null builder — see the all-hosts
  * tests above for why null is the point. Every other member is unreachable from those tests.
  */
private final class RecordingIntercept extends JIntercept:
  var ruleCalls: Int = 0

  def rule(): JInterceptRuleBuilder =
    ruleCalls += 1
    null

  private def nope[A]: A = throw new NotImplementedError(
    "RecordingIntercept: only rule() is exercised"
  )
  def address(): java.net.InetSocketAddress = nope
  def uri(): java.net.URI = nope
  def proxySelector(): java.net.ProxySelector = nope
  def serve(host: String, response: JIsSpec): JInterceptRule = nope
  def forward(host: String, hostPort: String): JInterceptRule = nope
  def redirectTo(host: String, imposter: JImposter): JInterceptRule = nope
  def rules(): java.util.List[JInterceptRule] = nope
  def clearRules(): Unit = nope
  def trust(): JInterceptTrust = nope
  def caMaterial(): java.util.Optional[JIntercept.CaMaterial] = nope
  def close(): Unit = ()
