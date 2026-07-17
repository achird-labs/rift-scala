package rift.conformance

import scala.concurrent.duration.*

import zio.test.*

import rift.dsl.*
import rift.json.Json
import rift.model.*

/** G2 — DSL expressibility (engine-free, UNCONDITIONAL).
  *
  * For a selected subset of fixtures, re-author the imposter using `rift.dsl` and assert the built
  * `ImposterDefinition.toJson` `semanticEquals` the fixture exactly. The point isn't "cover every
  * fixture" — it's proving the DSL CAN express the pure-DSL-shaped ones exactly, and loudly
  * documenting which it can't and why (the honest parity picture; G1 above already proves every
  * fixture round-trips at the raw-JSON level regardless).
  *
  * Three buckets, keyed by fixture id (`coverageIsComplete` below pins that every corpus fixture
  * lands in exactly one):
  *
  *   - `dslExpressible` — re-authored and asserted byte-for-byte (mod key order) equal to the
  *     fixture.
  *   - `dslExpressibleModuloVerify` — same, but compared against the fixture with `_verify` blocks
  *     stripped first. `_verify` is corpus/test-harness replay metadata (`README.md`'s "the replay
  *     contract" step 2) — it is not part of any modeled wire type (`Stub.modeledKeys` doesn't
  *     include it) and no DSL builder sets it, so no DSL-built stub can ever carry it. That's not a
  *     DSL authoring gap, it's scope: the DSL authors imposter *behavior*; `_verify` only exists to
  *     drive this corpus's own replay assertions (G3). G1 already proves `_verify` itself survives
  *     the raw round-trip untouched.
  *   - `notDslExpressible` — a one-line reason each: either an engine feature gated behind a
  *     `requires` capability beyond the typed DSL's authoring surface (injection/proxy scripting),
  *     or a genuine DSL surface gap this run surfaced.
  */
object CorpusG2Spec extends ZIOSpecDefault:

  private def fixtureById(id: String): Fixture =
    Corpus.fixtures.find(_.id == id).getOrElse(sys.error(s"no such corpus fixture: $id"))

  /** Strips `_verify` keys at every nesting level — see the `dslExpressibleModuloVerify` note above
    * for why that's a legitimate normalization for this gate rather than a fudge.
    */
  private def stripVerify(json: Json): Json = json match
    case Json.Obj(fields) =>
      Json.Obj(fields.collect { case (k, v) if k != "_verify" => k -> stripVerify(v) })
    case Json.Arr(items) => Json.Arr(items.map(stripVerify))
    case other => other

  // ── 01 · Basic REST API — pure REST: equals/and/matches predicates, cycling responses. ─────────
  private def build01: ImposterDefinition =
    imposter("01 · Basic REST API")
      .port(4501)
      .record
      .defaultResponse(
        notFound
          .header("Content-Type", "application/json")
          .json("""{"error":"not found","hint":"see README for the route list"}""")
      )
      .stub(get("/health").named("Health check").reply(ok.text("OK")))
      .stub(
        get("/api/products")
          .named("List products (JSON array body)")
          .reply(
            ok.header("Content-Type", "application/json")
              .json(
                """[{"id":123,"name":"Widget","price":9.99},{"id":456,"name":"Gadget","price":19.99}]"""
              )
          )
      )
      .stub(
        onRequest
          .where(allOf(method.is("GET"), path.matches("""^/api/products/\d+$""")))
          .named("Get one product by numeric id (regex path)")
          .reply(
            ok.header("Content-Type", "application/json")
              .json("""{"id":123,"name":"Widget","price":9.99}""")
          )
      )
      .stub(
        post("/api/products")
          .named("Create product")
          .reply(
            status(201)
              .header("Content-Type", "application/json")
              .header("Location", "/api/products/999")
              .json("""{"id":999,"message":"created"}""")
          )
      )
      .stub(
        onRequest
          .where(allOf(method.is("DELETE"), path.matches("""^/api/products/\d+$""")))
          .named("Delete product (no content)")
          .reply(status(204))
      )
      .stub(
        get("/api/rotating")
          .named("Response cycling — three responses served round-robin")
          .reply(ok.json("""{"served":"first"}"""))
          .thenReply(ok.json("""{"served":"second"}"""))
          .thenReply(ok.json("""{"served":"third"}"""))
      )
      .build

  // ── 15 · Predicate modifiers — contains/endsWith/except/deepEquals/exists:false. ────────────────
  private def build15: ImposterDefinition =
    imposter("15 · Predicate modifiers (issue #254 §D1)")
      .port(4515)
      .record
      .defaultResponse(
        notFound
          .header("Content-Type", "application/json")
          .json("""{"error":"no predicate matched"}""")
      )
      .stub(
        onRequest
          .where(body.contains("needle"))
          .named("contains — body contains 'needle'")
          .reply(ok.text("contains"))
      )
      .stub(
        onRequest
          .where(path.endsWith("/end"))
          .named("endsWith — path ends with /end")
          .reply(ok.text("endsWith"))
      )
      .stub(
        onRequest
          .where(path.is("/v"))
          .except("[0-9]+")
          .named("except — strip digits before comparing path to /v")
          .reply(ok.text("except"))
      )
      .stub(
        onRequest
          // `query("a").deepEquals(...)` wraps its value under `{"query":{"a": ...}}` — structurally
          // identical to the fixture's whole-query deepEquals shape, even though the DSL's own intent
          // for this combinator is field-scoped comparison rather than whole-map comparison.
          .where(query("a").deepEquals(Json.Str("1")))
          .named("deepEquals — query must be EXACTLY {a:1} (extra keys rejected)")
          .reply(ok.text("deep"))
      )
      .stub(
        onRequest
          .where(header("X-Skip").notExists)
          .where(path.is("/noskip"))
          .named("exists:false — header X-Skip must be ABSENT")
          .reply(ok.text("absent"))
      )
      .build

  // ── 04 · Fault Injection — _rift.fault latency/error/tcp, bare and probabilistic forms. ─────────
  private def build04: ImposterDefinition =
    imposter("04 · Fault Injection (_rift.fault)")
      .port(4504)
      .record
      .stub(
        onRequest
          .where(path.is("/faults/latency"))
          .named("Latency — always add 500-2000ms")
          .reply(
            ok.header("Content-Type", "application/json")
              .json("""{"endpoint":"/faults/latency","note":"random latency 500-2000ms"}""")
              .withLatencyFault(1.0, 500.millis.to(2.seconds))
          )
      )
      .stub(
        onRequest
          .where(path.is("/faults/sometimes-slow"))
          .named("Latency — 50% chance of a fixed 1s delay")
          .reply(
            ok.header("Content-Type", "application/json")
              .json("""{"endpoint":"/faults/sometimes-slow","note":"50% chance of +1000ms"}""")
              .withLatencyFault(0.5, 1.second)
          )
      )
      .stub(
        onRequest
          .where(path.is("/faults/flaky"))
          .named("Error — 30% chance of a 503 instead of the happy body")
          .reply(
            ok.header("Content-Type", "application/json")
              .json("""{"endpoint":"/faults/flaky","note":"30% chance of 503"}""")
              .withErrorFault(
                0.3,
                503,
                """{"error":"service temporarily unavailable","code":"FLAKY"}"""
              )
          )
      )
      .stub(
        onRequest
          .where(path.is("/faults/chaos"))
          .named("Combined — 70% latency AND 20% error (chaos)")
          .reply(
            ok.header("Content-Type", "application/json")
              .json("""{"endpoint":"/faults/chaos","note":"70% latency + 20% error"}""")
              .withLatencyFault(0.7, 200.millis.to(800.millis))
              .withErrorFault(0.2, 500, """{"error":"internal chaos"}""")
          )
      )
      .stub(
        onRequest
          .where(path.is("/faults/tcp-reset"))
          .named("TCP — reset the connection (no HTTP response at all)")
          .reply(
            ok.text("you will never see this").withTcpFault(TcpFaultKind.ConnectionResetByPeer)
          )
      )
      .stub(
        onRequest
          .where(path.is("/faults/tcp-flaky"))
          .named("TCP — reset the connection 10% of the time (probabilistic object form)")
          .reply(
            ok.header("Content-Type", "application/json")
              .json(
                """{"endpoint":"/faults/tcp-flaky","note":"10% chance of a connection reset"}"""
              )
              .withTcpFault(0.1, TcpFaultKind.ConnectionResetByPeer)
          )
      )
      .stub(
        onRequest
          .where(path.is("/faults/healthy"))
          .named("Baseline — no fault, for comparison")
          .reply(
            ok.header("Content-Type", "application/json")
              .json("""{"status":"healthy","note":"no faults injected"}""")
          )
      )
      .build

  // ── 10 · Correlated isolation — _rift.flowState + per-stub `.inSpace`. ──────────────────────────
  private def build10: ImposterDefinition =
    imposter("10 · Correlated isolation (one port, partitioned by a header)")
      .port(4510)
      .record
      .flowState(inMemoryFlowState.ttl(300.seconds).flowIdFromHeader("X-Mock-Space"))
      .stub(
        onRequest
          .where(path.is("/data"))
          .inSpace(flowIdOrDie("alice"))
          .named("space=alice — only matches requests with X-Mock-Space: alice")
          .reply(ok.json("""{"owner":"alice","items":[1,2]}"""))
      )
      .stub(
        onRequest
          .where(path.is("/data"))
          .inSpace(flowIdOrDie("bob"))
          .named("space=bob — isolated from alice")
          .reply(ok.json("""{"owner":"bob","items":[9]}"""))
      )
      .stub(
        onRequest
          .where(path.is("/health"))
          .named("global (no space) — matches any caller")
          .reply(ok.text("OK"))
      )
      .build

  private def flowIdOrDie(value: String): FlowId =
    FlowId.from(value).getOrElse(sys.error(s"invalid flow id: $value"))

  // ── 11 · Headers/templates — multi-value headers, serve-time date templates, CORS/forward. ─────
  private def build11: ImposterDefinition =
    imposter("11 · Multi-value headers, date templates, defaultResponse/defaultForward, CORS")
      .port(4511)
      .allowCors
      .defaultForward("http://localhost:4501")
      .stub(
        onRequest
          .where(path.is("/cookies"))
          .named("multi-value headers — two Set-Cookie lines on the wire (#238)")
          .reply(
            ok.header("Set-Cookie", "sessionId=abc")
              .header("Set-Cookie", "theme=dark")
              .header("X-Single", "one")
              .text("two cookies set")
          )
      )
      .stub(
        onRequest
          .where(path.is("/token"))
          .named("serve-time date templates — {{DAYS+N}}/{{MONTHS+N}}/{{NOW}} (#195)")
          .reply(
            ok.header("Content-Type", "application/json")
              // The placeholder body is a JSON *string* value on the wire (escaped quotes inside),
              // not a JSON object — `.text`, not `.json`, is what reproduces that shape.
              .text("""{"issued":"{{NOW}}","expires":"{{DAYS+30}}","renewal":"{{MONTHS+12}}"}""")
          )
      )
      .build

  private val dslExpressible: Map[String, ImposterDefinition] = Map(
    "01-basic-rest" -> build01,
    "15-predicate-modifiers" -> build15,
    "04-fault-injection" -> build04
  )

  private val dslExpressibleModuloVerify: Map[String, ImposterDefinition] = Map(
    "10-correlated-isolation" -> build10,
    "11-headers-and-templates" -> build11
  )

  private val notDslExpressible: Map[String, String] = Map(
    "02-predicates" ->
      ("stub 2's explicit `\"caseSensitive\": false` has no DSL builder path — StubBuilder.caseSensitive " +
        "only ever sets true, there is no way to force an explicit false when the default is already " +
        "false — plus its per-stub `_verify` blocks (see the modulo-verify note above)."),
    "03-behaviors" -> "requires injection — wait/repeat/decorate/copy/lookup scripting beyond the typed DSL surface.",
    "05-scripting-engines" -> "requires injection — rhai/javascript scripting engines are an escape-hatch feature.",
    "06-stateful-retry" -> "requires injection — flow-state-driven scripted retries beyond the typed DSL surface.",
    "07-proxy-record" -> "requires proxy — proxy + record/replay is an imposter-level engine behavior, not stub authoring.",
    "13-js-config-decorate" -> "requires injection — Mountebank `config =>` / Rhai decorate conventions.",
    "14-verify-annotations" -> "purpose-built to exercise the `_verify` annotation surface itself, not DSL authoring.",
    "16-behaviors-advanced" -> "requires injection — advanced behaviors (issue #254 §D3) beyond the typed DSL surface.",
    "17-faults-and-binary" ->
      ("stub 1's `_rift.fault.error.headers` has no DSL builder path — IsResponseBuilder.withErrorFault " +
        "doesn't expose a headers parameter even though ErrorFault.headers is a modeled wire field " +
        "(a genuine DSL surface gap, distinct from the requires-gated fixtures above)."),
    "20-mimeo-solo-compat" -> "requires injection + proxy — Mountebank migration-tool compat forms beyond the typed DSL."
  )

  def spec = suite("G2 — DSL expressibility (issue #6, engine-free)")(
    suite("dslExpressible — exact semanticEquals")(
      dslExpressible.toVector.map { (id, built) =>
        test(s"$id is exactly expressible via rift.dsl") {
          assertTrue(built.toJson.semanticEquals(Corpus.imposterJson(fixtureById(id))))
        }
      }*
    ),
    suite("dslExpressibleModuloVerify — exact semanticEquals once `_verify` is stripped")(
      dslExpressibleModuloVerify.toVector.map { (id, built) =>
        test(s"$id is expressible via rift.dsl modulo _verify") {
          assertTrue(built.toJson.semanticEquals(stripVerify(Corpus.imposterJson(fixtureById(id)))))
        }
      }*
    ),
    test("every corpus fixture is accounted for in the expressible/not-expressible split") {
      val documented =
        dslExpressible.keySet ++ dslExpressibleModuloVerify.keySet ++ notDslExpressible.keySet
      assertTrue(Corpus.fixtures.map(_.id).toSet == documented)
    }
  )
