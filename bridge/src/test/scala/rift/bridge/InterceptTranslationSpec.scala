package rift.bridge

import munit.FunSuite

import rift.RiftError
import rift.dsl.*
import rift.json.Json
import rift.model.{
  Behaviors,
  ErrorFault,
  FaultConfig,
  Headers,
  IsResponse,
  LatencyFault,
  Response,
  RiftResponseExt,
  ScriptEngine,
  ScriptSource,
  TcpFaultKind,
  WaitBehavior
}

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
  * translation — no live engine, so it runs on every CI job. The full engine round-trip is the
  * `isEmbeddedAvailable`-gated smoke in `EmbeddedSmokeSpec`, which since #99 runs on the JDK 22 job
  * and skips on JDK 21.
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

  // AC5 — serve carries the behaviors/faults the facade's IsSpec can express (issue #81). Assert on
  // the engine wire JSON the translated IsSpec renders, so a mistranslation shows up as a wrong
  // wire shape rather than a passing type-check.
  private def wireOf(response: ResponseBuilder): String =
    FacadeEncode.isSpec(response).build.toJsonValue.toJson

  test("serve translates every wait spelling"):
    import scala.concurrent.duration.DurationInt
    val fixed = wireOf(ok.json("{}").after(1.second))
    assert(fixed.contains("\"wait\"") && fixed.contains("1000"), fixed)

    // bind each value to its key: asserting the numbers appear anywhere would still pass if a bug
    // swapped min and max.
    val range = wireOf(ok.json("{}").afterBetween(100.millis, 300.millis))
    assert(range.contains("\"min\":100"), range)
    assert(range.contains("\"max\":300"), range)

    val injected = wireOf(ok.json("{}").afterInject("function () { return 5; }"))
    assert(injected.contains("inject"), injected)

    // the bare-string (Mountebank-compatible) spelling has no DSL builder — drive the model shape
    val scripted = wireOf(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          behaviors = Behaviors(waitFor = Some(WaitBehavior.Script("function () { return 7; }")))
        )
      )
    )
    assert(scripted.contains("\"wait\"") && scripted.contains("return 7"), scripted)

  test("serve translates decorate, repeat and shellTransform"):
    val decorated = wireOf(ok.json("{}").decorate("function (req, res) { return res; }"))
    assert(decorated.contains("decorate"), decorated)

    val repeated = wireOf(ok.json("{}").repeat(3))
    assert(repeated.contains("repeat") && repeated.contains("3"), repeated)

    // Straight off the response DSL since #93 — this is the builder -> model -> facade path a
    // user actually takes, not a hand-assembled model value.
    val shell = wireOf(ok.json("{}").shellTransform("sed s/a/b/"))
    assert(shell.contains("shellTransform") && shell.contains("sed"), shell)

    val chained = wireOf(ok.json("{}").shellTransform("first").shellTransform("second"))
    assert(chained.contains("first") && chained.contains("second"), chained)

  test("serve translates the _rift templated flag"):
    val wire = wireOf(ok.json("{}").templated)
    assert(wire.contains("templated"), wire)

  test("serve translates a latency fault in both wire forms"):
    import scala.concurrent.duration.DurationInt
    val fixed = wireOf(ok.json("{}").withLatencyFault(0.5, 250.millis))
    assert(fixed.contains("latency") && fixed.contains("250"), fixed)
    // the fixed form must not gain the range's fields (rift#56)
    assert(!fixed.contains("minMs"), fixed)

    val ranged = wireOf(ok.json("{}").withLatencyFault(0.5, 100.millis to 400.millis))
    assert(ranged.contains("minMs") && ranged.contains("maxMs"), ranged)
    assert(!ranged.contains("\"ms\""), ranged)

  test("serve translates an error fault in each expressible shape"):
    // the response DSL requires a body, so the bodiless shape comes from the model directly
    val bare = wireOf(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          rift =
            Some(RiftResponseExt(fault = Some(FaultConfig(error = Some(ErrorFault(0.25, 503))))))
        )
      )
    )
    assert(bare.contains("error") && bare.contains("503"), bare)

    val withBody = wireOf(ok.json("{}").withErrorFault(0.5, 500, "boom"))
    assert(withBody.contains("boom"), withBody)

    val withHeaders =
      wireOf(ok.json("{}").withErrorFault(0.5, 503, "DOWN", headers = Map("Retry-After" -> "30")))
    assert(withHeaders.contains("Retry-After") && withHeaders.contains("30"), withHeaders)

  test("serve translates a tcp fault in both wire forms"):
    val bare = wireOf(ok.withTcpFault(TcpFaultKind.ConnectionResetByPeer))
    assert(bare.contains("CONNECTION_RESET_BY_PEER"), bare)

    val probabilistic = wireOf(ok.withTcpFault(0.5, TcpFaultKind.EmptyResponse))
    assert(
      probabilistic.contains("EMPTY_RESPONSE") && probabilistic.contains("\"probability\":0.5"),
      probabilistic
    )

  // AC9 — everything IsSpec genuinely cannot express still rejects loudly, naming the offender.
  private def rejectMessage(response: ResponseBuilder): String =
    intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(response)).msg

  test("serve still rejects a copy behavior — the facade CopySpec has no JSON seam"):
    val msg = rejectMessage(
      ok.json("{}").copy(from = path, into = "${id}", extractWith = CopyUsing.Regex("(\\d+)"))
    )
    assert(msg.contains("copy"), msg)

  test("serve still rejects an embedded _rift script"):
    val msg = rejectMessage(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          rift = Some(RiftResponseExt(script = Some(ScriptSource.Inline(ScriptEngine.Rhai, "1"))))
        )
      )
    )
    assert(msg.contains("script"), msg)

  test("serve still rejects a lookup behavior — the facade LookupSpec has no JSON seam"):
    val msg = rejectMessage(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          behaviors = Behaviors(lookup = Vector(Json.obj("key" -> Json.Str("k"))))
        )
      )
    )
    assert(msg.contains("lookup"), msg)

  test("serve still rejects an unknown top-level or `is` key, naming the offender"):
    val topLevel = rejectMessage(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          extra = Vector("futureTopLevel" -> Json.Bool(true))
        )
      )
    )
    assert(topLevel.contains("futureTopLevel"), topLevel)

    val insideIs = rejectMessage(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200), extra = Vector("futureIsKey" -> Json.Bool(true)))
        )
      )
    )
    assert(insideIs.contains("futureIsKey"), insideIs)

  // `rawStatusCode` is a modeled field, so the unknown-key guard cannot see it, and RiftDsl.status
  // takes an Int — translating would silently answer 200 for a response that named another status.
  test("serve rejects a non-numeric statusCode rather than silently answering 200"):
    val msg = rejectMessage(
      Fixed(Response.Is(IsResponse(rawStatusCode = Some(Json.Str("404")))))
    )
    assert(msg.contains("statusCode"), msg)

  test("serve still rejects an unknown behavior key — a forward-compat behavior must not vanish"):
    val msg = rejectMessage(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          behaviors = Behaviors(unknown = Vector("futureThing" -> Json.Bool(true)))
        )
      )
    )
    assert(msg.contains("futureThing"), msg)

  test("serve rejects a latency fault that names neither a fixed delay nor a complete range"):
    val partial = Response.Is(
      IsResponse(statusCode = Some(200)),
      rift = Some(RiftResponseExt(fault = Some(FaultConfig(latency = Some(LatencyFault(1.0))))))
    )
    val msg = intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(Fixed(partial))).message
    assert(msg.contains("latency"), msg)

  test(
    "serve rejects a latency fault carrying BOTH a fixed delay and a range — facade cannot express both"
  ):
    val both = Response.Is(
      IsResponse(statusCode = Some(200)),
      rift = Some(
        RiftResponseExt(fault =
          Some(FaultConfig(latency = Some(LatencyFault(1.0, Some(50), Some(10), Some(90)))))
        )
      )
    )
    val msg = intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(Fixed(both))).message
    assert(msg.contains("latency"), msg)

  // The facade's only headers-carrying overload is `withErrorFault(p, status, body, headers)`, which
  // does `Optional.of(body)` — a null body NPEs and an empty one would turn "absent" into "empty".
  test("serve rejects an error fault with headers but no body — no expressible facade overload"):
    val headersNoBody = Response.Is(
      IsResponse(statusCode = Some(200)),
      rift = Some(
        RiftResponseExt(fault =
          Some(
            FaultConfig(error =
              Some(ErrorFault(1.0, 503, None, Headers(Vector("Retry-After" -> "30"))))
            )
          )
        )
      )
    )
    val msg =
      intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(Fixed(headersNoBody))).message
    assert(msg.contains("body"), msg)

  test("serve rejects error-fault headers that repeat a name — the facade Map is single-valued"):
    val repeated = Response.Is(
      IsResponse(statusCode = Some(200)),
      rift = Some(
        RiftResponseExt(fault =
          Some(
            FaultConfig(error =
              Some(
                ErrorFault(
                  1.0,
                  503,
                  Some("DOWN"),
                  Headers(Vector("Set-Cookie" -> "a", "Set-Cookie" -> "b"))
                )
              )
            )
          )
        )
      )
    )
    val msg = intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(Fixed(repeated))).message
    // asserts the offending name specifically: a generic "a header repeats" message would silently
    // weaken the names-the-offender guarantee the reject set is built on.
    assert(msg.contains("Set-Cookie"), msg)

  test("serve still rejects a non-is response (proxy/inject/fault)"):
    intercept[RiftError.InvalidDefinition] {
      FacadeEncode.isSpec(inject("function (req) { return {}; }"))
    }

  /** Wraps an already-built `Response` as a `ResponseBuilder` so a test can drive `isSpec` with a
    * model shape the fluent DSL will not construct (an incomplete latency fault, an error fault
    * with headers but no body, repeated fault headers).
    */
  private final case class Fixed(response: Response) extends ResponseBuilder:
    def build: Response = response

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

  // Issue #82 — the facade's `when` ASSIGNS its predicate list (verified in rift-java-core 0.2.0
  // bytecode: `putfield predicates`), so replaying N matches onto it keeps only the last. Asserting
  // on a Scala-side accumulator provably cannot catch that — the gate has to observe what actually
  // reached the facade. A real `JInterceptRuleBuilder` (public final, package-private ctor, so it
  // cannot be subclassed or stubbed) is built reflectively over a null `InterceptImpl`: the ctor
  // only stores the reference, so `when` lands normally and the terminal NPEs afterwards at
  // `addServeRule` — which is exactly the window in which the facade's predicate list is readable.
  // Moved to InterceptGate (#101) so the zio/cats replay-fold gates share one home for the
  // reflection instead of each re-deriving it.
  private def facadeBuilder(): JInterceptRuleBuilder = InterceptGate.facadeBuilder()

  private def facadePredicates(builder: JInterceptRuleBuilder): java.util.List[?] =
    InterceptGate.facadePredicates(builder)

  test("a terminal carries every chained when clause to the facade — none dropped"):
    val jBuilder = facadeBuilder()
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    val builder = new InterceptRuleBuilder(jBuilder).when(first).when(second)
    intercept[NullPointerException](builder.serve(ok))
    val sent = facadePredicates(jBuilder)
    assertEquals(sent.size, (first.predicates ++ second.predicates).size)
    // Position, not just presence: `applied` concatenates in chain order, and a reordering
    // regression would slip past a contains-only assertion.
    val rendered = sent.toString
    assert(rendered.indexOf("/admin") >= 0, rendered)
    assert(rendered.indexOf("X-Env") > rendered.indexOf("/admin"), rendered)

  // `forward` parses the port off the target before touching the (null) engine, so the target
  // must carry one for the NPE to be what escapes.
  test("chained when reaches the facade on the forward terminal too"):
    val jBuilder = facadeBuilder()
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    val builder = new InterceptRuleBuilder(jBuilder).when(first).when(second)
    intercept[NullPointerException](builder.forward("real.example.com:443"))
    assertEquals(facadePredicates(jBuilder).size, (first.predicates ++ second.predicates).size)

  // #100: the scaladoc on every `forward` promises a `host:port` target and says a scheme-carrying
  // URL is rejected. Nothing pinned that half of the claim. The facade's `parsePort` splits on the
  // last `':'` and parses the remainder as an int, so `"https://real.example.com"` parses
  // `"//real.example.com"` and throws — and it throws as an ARGUMENT to `addForwardRule`, i.e.
  // before any rule is registered. `IllegalArgumentException` (not the `NullPointerException` the
  // sibling test expects) is what distinguishes "rejected by the facade" from "reached the engine".
  test("forward rejects a scheme-carrying target before reaching the engine"):
    val builder = new InterceptRuleBuilder(facadeBuilder()).when(get("/admin"))
    val thrown = intercept[IllegalArgumentException](builder.forward("https://real.example.com"))
    // The message pins the thrower: `parsePort` wraps the NumberFormatException in an
    // IllegalArgumentException naming the target. Without this, a bare NumberFormatException
    // (a subclass) or an IAE raised anywhere else in the call would satisfy the intercept.
    assert(thrown.getMessage.contains("https://real.example.com"), thrown.getMessage)

  test("a terminal with no when leaves the facade predicates empty — catch-all preserved"):
    val jBuilder = facadeBuilder()
    intercept[NullPointerException](new InterceptRuleBuilder(jBuilder).serve(ok))
    assert(facadePredicates(jBuilder).isEmpty, facadePredicates(jBuilder).toString)

  // Forks share one mutable facade builder whose predicate field survives a terminal, so a
  // zero-`when` rule must still assign — otherwise it inherits the sibling's clauses and a
  // catch-all silently narrows. The mirror image of #82, and why `applied` has no empty branch.
  test("a catch-all terminal is not narrowed by a sibling fork that already ran a terminal"):
    val jBuilder = facadeBuilder()
    val base = new InterceptRuleBuilder(jBuilder)
    intercept[NullPointerException](base.when(get("/admin")).serve(status(503)))
    assert(facadePredicates(jBuilder).toString.contains("/admin"))
    intercept[NullPointerException](base.serve(ok))
    assert(
      facadePredicates(jBuilder).isEmpty,
      s"catch-all inherited the sibling's clauses: ${facadePredicates(jBuilder)}"
    )

  // `onRequest` contributes no predicates by design. Combining it with a real clause must keep the
  // real one rather than collapsing the rule to a match-everything catch-all.
  test("a vacuous clause does not widen a rule that also carries a restrictive one"):
    val jBuilder = facadeBuilder()
    val builder = new InterceptRuleBuilder(jBuilder).when(get("/admin")).when(onRequest)
    intercept[NullPointerException](builder.serve(ok))
    val sent = facadePredicates(jBuilder)
    assertEquals(sent.size, get("/admin").predicates.size)
    assert(sent.toString.contains("/admin"), sent.toString)

  // Unlike the serve/forward cases the facade is never entered here — `redirectTo(null)` dies on
  // the Scala side at `imposter.jImposter`. The clauses still land because Scala evaluates the
  // receiver (`applied`) before the argument, so hoisting `imposter.jImposter` into a local above
  // the call would break this test for a reason unrelated to what it guards.
  test("chained when reaches the facade on the redirectTo terminal too"):
    val jBuilder = facadeBuilder()
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    val builder = new InterceptRuleBuilder(jBuilder).when(first).when(second)
    intercept[NullPointerException](builder.redirectTo(null))
    assertEquals(facadePredicates(jBuilder).size, (first.predicates ++ second.predicates).size)

  // Asserting on size alone would pass vacuously here (both the leaked and the correct list hold
  // one predicate), so this checks *which* clause landed.
  test("a discarded fork that never reaches a terminal leaks nothing into its sibling"):
    val jBuilder = facadeBuilder()
    val base = new InterceptRuleBuilder(jBuilder).when(get("/admin"))
    base.when(onRequest.where(header("X-Env").is("prod"))) // discarded fork
    intercept[NullPointerException](base.serve(ok))
    val rendered = facadePredicates(jBuilder).toString
    assert(rendered.contains("/admin"), rendered)
    assert(!rendered.contains("X-Env"), s"discarded fork leaked into the sibling: $rendered")

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
