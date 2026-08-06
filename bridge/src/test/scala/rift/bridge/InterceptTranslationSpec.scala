package rift.bridge

import munit.FunSuite

import rift.RiftError
import rift.dsl.*
import rift.json.Json
import rift.model.{
  Behaviors,
  ErrorFault,
  FaultConfig,
  IsResponse,
  Port,
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
  InterceptOptions as JInterceptOptions,
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

  // ── issue #95: every facade CA source form, and what each one puts on the wire ───────────────
  test("CaMaterial.Pem sends the PEM text itself"):
    val rendered =
      InterceptConfig(ca = Some(CaMaterial.Pem("cert-pem", "key-pem"))).toOptions.toJson.toJson
    assert(rendered.contains("cert-pem"), rendered)
    assert(rendered.contains("key-pem"), rendered)

  // The load-bearing distinction: `ca(Path, Path)` is NOT a client-side file read — the facade
  // serializes the paths and the ENGINE opens them, on its own host. Reading them Scala-side would
  // silently change what connect/spawn/container do. This pins the path strings on the wire and the
  // absence of any PEM text, so a future "helpfully read the file" refactor fails here.
  test("CaMaterial.PemFiles sends paths for the engine to read, never their contents"):
    val certPath = java.nio.file.Paths.get("/engine/host/ca.pem")
    val keyPath = java.nio.file.Paths.get("/engine/host/ca.key")
    val rendered =
      InterceptConfig(ca = Some(CaMaterial.PemFiles(certPath, keyPath))).toOptions.toJson.toJson
    assert(rendered.contains("/engine/host/ca.pem"), rendered)
    assert(rendered.contains("/engine/host/ca.key"), rendered)
    assert(!rendered.contains("BEGIN"), s"PEM content leaked into a path-based config: $rendered")

  // The facade's own `ca(byte[], byte[])` is `new String(bytes, UTF_8)` delegating to the String
  // overload, so a smart constructor is warranted rather than a fourth case. Asserting the two
  // produce identical wire JSON is what makes "verified-identical" a checked claim.
  test("fromPemBytes matches the facade's own byte[] overload exactly"):
    val certBytes = "cert-pem-é".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val keyBytes = "key-pem-é".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val ours = InterceptConfig(ca =
      Some(
        CaMaterial.fromPemBytes(IArray.unsafeFromArray(certBytes), IArray.unsafeFromArray(keyBytes))
      )
    ).toOptions.toJson.toJson
    val theirs = JInterceptOptions
      .builder()
      .host("127.0.0.1")
      .port(0)
      .ca(certBytes, keyBytes)
      .build()
      .toJson
      .toJson
    assertEquals(ours, theirs)

  test("no CA material asks the engine to generate one and return the key"):
    val rendered = InterceptConfig().toOptions.toJson.toJson
    // `generateCa()` sets returnCaKey — which is what makes `caMaterial` readback non-empty.
    assert(rendered.contains("returnCaKey") || rendered.contains("generate"), rendered)

  test("the legacy CaMaterial(cert, key) apply still builds the Pem case"):
    assertEquals(CaMaterial("c", "k"), CaMaterial.Pem("c", "k"))

  // `caMaterial` hands callers a real private key, so the derived rendering would print it in full
  // in any failure message or log line.
  test("CaMaterial never renders a private key or a password"):
    val pem = CaMaterial.Pem("cert-body", "SUPER-SECRET-KEY").toString
    assert(!pem.contains("SUPER-SECRET-KEY"), pem)
    assert(pem.contains("redacted"), pem)

    val ks = java.security.KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    val fromKs = CaMaterial.fromKeyStore(ks, "SUPER-SECRET-PW".toCharArray).toString
    assert(!fromKs.contains("SUPER-SECRET-PW"), fromKs)
    assert(fromKs.contains("redacted"), fromKs)

    // Paths are not secret and are the useful part of a diagnostic.
    val files = CaMaterial
      .PemFiles(java.nio.file.Paths.get("/tmp/ca.pem"), java.nio.file.Paths.get("/tmp/ca.key"))
      .toString
    assert(files.contains("/tmp/ca.pem"), files)

  // The one reason to take a char[] password is zeroing it afterwards. The config is not read until
  // the intercept starts, so retaining the caller's array would turn a conscientious zeroing into
  // an unrecoverable-key failure much later.
  test("fromKeyStore copies the password so the caller can zero their own array"):
    val ks = java.security.KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    val caller = "changeit".toCharArray
    val material = CaMaterial.fromKeyStore(ks, caller)
    java.util.Arrays.fill(caller, '\u0000')
    assertEquals(IArray.genericWrapArray(material.password).mkString, "changeit")

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
      .isSpec(ok.header("X-Test", "hdr-val").header("X-Other", "second").json("""{"id":1}"""))
      .build
      .toJsonValue
      .toJson
    val js = Json.parse(wire).fold(e => fail(e.toString), identity)
    assert(js.render.contains("\"is\""), wire) // wrapped as an `is` response
    assert(wire.contains("200"), wire)
    assert(wire.contains("id"), wire)
    // several distinct names must all survive — only a *repeated* name is rejected
    assert(wire.contains("X-Test") && wire.contains("hdr-val"), wire)
    assert(wire.contains("X-Other") && wire.contains("second"), wire)

  test("serve translates a non-200 status and a text body"):
    val wire = FacadeEncode.isSpec(status(503).text("down")).build.toJsonValue.toJson
    assert(wire.contains("503"), wire)
    assert(wire.contains("down"), wire)

  // Issue #149 — the engine's imposter defaults an absent `Content-Type` to `application/json` for
  // a non-string body, but its serve action writes a rule's headers verbatim and infers nothing.
  // The translation injects that default so the same response object is typed the same way on both
  // paths; these pin the default's *scope* to the imposter's, not just its presence.
  private def contentTypeCount(wire: String): Int =
    "(?i)content-type".r.findAllIn(wire).length

  test("serve defaults an absent Content-Type to application/json for a JSON body"):
    val wire = FacadeEncode.isSpec(ok.json("""{"a":1}""")).build.toJsonValue.toJson
    assert(wire.contains("application/json"), wire)
    assertEquals(contentTypeCount(wire), 1, wire)

  // The default keys on "not a JSON string", not on "is a JSON object" — narrowing it to `Json.Obj`
  // would type object bodies only and leave the rest of the imposter's scope untyped again.
  test("serve defaults the Content-Type for a non-object JSON body too"):
    val wire = FacadeEncode.isSpec(ok.json("[1,2,3]")).build.toJsonValue.toJson
    assert(wire.contains("application/json"), wire)
    assertEquals(contentTypeCount(wire), 1, wire)

  test("serve never overrides or duplicates a caller's Content-Type, whatever its casing"):
    val wire = FacadeEncode
      .isSpec(ok.header("content-type", "application/vnd.api+json").json("""{"a":1}"""))
      .build
      .toJsonValue
      .toJson
    assert(wire.contains("application/vnd.api+json"), wire)
    assert(!wire.contains("application/json"), wire)
    assertEquals(contentTypeCount(wire), 1, wire)

  test("serve adds no Content-Type to a text body"):
    val wire = FacadeEncode.isSpec(ok.text("plain")).build.toJsonValue.toJson
    assertEquals(contentTypeCount(wire), 0, wire)

  // A JSON *string* body translates via `withTextBody` and has no `rendered_body` on the imposter
  // path either, so it gets no default there and must get none here.
  test("serve adds no Content-Type to a JSON string body"):
    val wire = FacadeEncode.isSpec(ok.json("\"just a string\"")).build.toJsonValue.toJson
    assertEquals(contentTypeCount(wire), 0, wire)

  test("serve adds no Content-Type to a bodyless response"):
    val wire = FacadeEncode.isSpec(noContent).build.toJsonValue.toJson
    assertEquals(contentTypeCount(wire), 0, wire)

  // AC5 — everything the response DSL accepts but the engine's intercept serve action cannot
  // deliver (issue #147). `InterceptImpl.toServeStub` builds the action from `statusCode`, `headers`
  // and `body` alone: it never reads `Response.Is.behaviors()`, `.rift()` or `IsResponse.mode()`,
  // and it keeps only the first value of a multi-valued header. Translating any of these would
  // register a rule that answers something the author never asked for, so each must reject —
  // naming the construct, since a caller has to know which line to change.
  // `using munit.Location` on both: without it a failure inside these helpers reports the helper's
  // own line, which every reject test in this file shares.
  private def rejectMessage(response: ResponseBuilder)(using munit.Location): String =
    intercept[RiftError.InvalidDefinition](FacadeEncode.isSpec(response)).msg

  private def assertRejects(response: ResponseBuilder, offender: String)(using
      munit.Location
  ): Unit =
    val msg = rejectMessage(response)
    assert(msg.contains(offender), s"expected the message to name '$offender', got: $msg")

  test("serve rejects a binary body — the engine's serve action drops the binary marker"):
    assertRejects(ok.binary(Array[Byte](1, 2, 3, 4)), "binary")

  test("serve rejects a repeated header name — the engine keeps only the first value"):
    assertRejects(
      ok.header("Set-Cookie", "cookie-a").header("Set-Cookie", "cookie-b").json("{}"),
      // the offending name specifically: a generic "a header repeats" would silently weaken the
      // names-the-offender guarantee the whole reject set is built on.
      "Set-Cookie"
    )

    // two *different* repeated names: naming only the first would leave the caller fixing one and
    // being rejected again for the other.
    val both = rejectMessage(
      ok.header("Set-Cookie", "a")
        .header("X-Trace", "1")
        .header("Set-Cookie", "b")
        .header("X-Trace", "2")
        .json("{}")
    )
    assert(both.contains("Set-Cookie"), both)
    assert(both.contains("X-Trace"), both)

  test("serve rejects every wait spelling"):
    import scala.concurrent.duration.DurationInt
    assertRejects(ok.json("{}").after(1.second), "wait")
    assertRejects(ok.json("{}").afterBetween(100.millis, 300.millis), "wait")
    assertRejects(ok.json("{}").afterInject("function () { return 5; }"), "wait")

    // the bare-string (Mountebank-compatible) spelling has no DSL builder — drive the model shape
    assertRejects(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          behaviors = Behaviors(waitFor = Some(WaitBehavior.Script("function () { return 7; }")))
        )
      ),
      "wait"
    )

  test("serve rejects decorate, repeat and shellTransform"):
    assertRejects(ok.json("{}").decorate("function (req, res) { return res; }"), "decorate")
    assertRejects(ok.json("{}").repeat(3), "repeat")
    // Straight off the response DSL since #93 — this is the builder -> model -> facade path a
    // user actually takes, not a hand-assembled model value.
    assertRejects(ok.json("{}").shellTransform("sed s/a/b/"), "shellTransform")

  test("serve rejects the _rift templated flag"):
    assertRejects(ok.json("{}").templated, "templated")

  test("serve rejects a latency fault in either wire form"):
    import scala.concurrent.duration.DurationInt
    assertRejects(ok.json("{}").withLatencyFault(0.5, 250.millis), "withLatencyFault")
    assertRejects(ok.json("{}").withLatencyFault(0.5, 100.millis to 400.millis), "withLatencyFault")

  test("serve rejects an error fault in every shape"):
    assertRejects(ok.json("{}").withErrorFault(0.5, 500, "boom"), "withErrorFault")
    assertRejects(
      ok.json("{}").withErrorFault(0.5, 503, "DOWN", headers = Map("Retry-After" -> "30")),
      "withErrorFault"
    )
    // the response DSL requires a body, so the bodiless shape comes from the model directly
    assertRejects(
      Fixed(
        Response.Is(
          IsResponse(statusCode = Some(200)),
          rift =
            Some(RiftResponseExt(fault = Some(FaultConfig(error = Some(ErrorFault(0.25, 503))))))
        )
      ),
      "withErrorFault"
    )

  // Issue #147's shape (b): this is the spelling that was accepted and then answered as a plain
  // 200, which is what made a fault-injection test certify resilience the SUT did not have.
  test("serve rejects a tcp fault in both wire forms"):
    assertRejects(ok.withTcpFault(TcpFaultKind.ConnectionResetByPeer), "withTcpFault")
    assertRejects(ok.withTcpFault(0.5, TcpFaultKind.EmptyResponse), "withTcpFault")

  // First-wins would send a caller round the loop once per construct, each time reporting a rule
  // they had already been told was unusable.
  test("serve names every dropped construct, not just the first"):
    val msg = rejectMessage(
      ok.json("{}").templated.repeat(3).withTcpFault(TcpFaultKind.ConnectionResetByPeer)
    )
    assert(msg.contains("templated"), msg)
    assert(msg.contains("repeat"), msg)
    assert(msg.contains("withTcpFault"), msg)

  // AC9 — everything IsSpec genuinely cannot express still rejects loudly, naming the offender.

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

  // The per-shape fault rejections this spec used to carry (an incomplete latency range, an error
  // fault with headers but no body, repeated fault headers) are gone with #147: they distinguished
  // translatable fault shapes from untranslatable ones, and no fault shape is translatable now.

  test("serve still rejects a non-is response (proxy/inject/fault)"):
    intercept[RiftError.InvalidDefinition] {
      FacadeEncode.isSpec(inject("function (req) { return {}; }"))
    }

  /** Wraps an already-built `Response` as a `ResponseBuilder` so a test can drive `isSpec` with a
    * model shape the fluent DSL will not construct (a bare-string `wait`, a bodiless error fault,
    * an unknown forward-compat key).
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

  // ── issue #120: the typed forward(port) overload ──────────────────────────────────────────────
  // The string form's rejection of a scheme-carrying target is pinned above (#100); the typed
  // overload cannot reach that state at all, which is the point of adding it.
  //
  // The facade's `forward(hostPort)` keeps only the port: `InterceptRuleBuilder.forward` calls
  // `InterceptImpl.parsePort(target)` and hands the resulting int to `addForwardRule` (`javap -c`,
  // rift-java-core 0.2.1). The engine's forward action is `ForwardTarget { port: u16 }`, proxied to
  // `http://127.0.0.1:{port}` — so the port is the entire payload and there is no destination host
  // to express. The typed overload renders that port; the string form stays for facade parity.
  //
  // Equivalence is asserted against the facade's OWN parser rather than a rule this spec invents.
  // If a future rift-java changed how a target is read, the bare-port rendering would fail here
  // rather than in a user's traffic.
  private val facadeParsePort: String => Int =
    val method = Class
      .forName("io.github.achirdlabs.rift.InterceptImpl")
      .getDeclaredMethod("parsePort", classOf[String])
    method.setAccessible(true)
    target => method.invoke(null, target).asInstanceOf[Int]

  private def port(value: Int): Port =
    Port.from(value).toOption.getOrElse(fail(s"not a valid port: $value"))

  test("forward(port) renders a target the facade parses back to the same port"):
    for raw <- Seq(1, 443, 4545, 65535) do
      assertEquals(facadeParsePort(FacadeEncode.forwardTarget(port(raw))), raw)

  test("forward(port) and the host:port string form deliver the identical port to the facade"):
    for raw <- Seq(1, 443, 65535) do
      assertEquals(
        facadeParsePort(FacadeEncode.forwardTarget(port(raw))),
        facadeParsePort(s"ignored.example.com:$raw")
      )

  test("forward(port) reaches the facade carrying every buffered clause"):
    val jBuilder = facadeBuilder()
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    val builder = new InterceptRuleBuilder(jBuilder).when(first).when(second)
    // The null engine NPEs inside addForwardRule — the signal the call got through translation.
    intercept[NullPointerException](builder.forward(port(4545)))
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
