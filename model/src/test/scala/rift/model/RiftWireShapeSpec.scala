package rift.model

import rift.json.Json

/** Round-trip coverage for the nine wire-shape bugs fixed in this change: each fixture below is the
  * exact wire JSON the authoritative Rust engine, rift-java, and zio-bdd agree on — asserted
  * against `semanticEquals` on both the decode and the encode side.
  */
class RiftWireShapeSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  // ── 1. FaultConfig.tcp — bare string (always-fires) or {probability, type} ─────────────────
  // Proof: rift RiftTcpFault (crates/rift-mock-core/src/imposter/types.rs:1070-1129);
  // RiftTcpFault.java; zio-bdd RiftProtocol.scala:140.
  test("tcp fault: bare string form (always fires) round-trips"):
    val json = parse(""""CONNECTION_RESET_BY_PEER"""")
    val fault = TcpFault.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(fault, TcpFault(TcpFaultKind.ConnectionResetByPeer, None))
    assert(fault.toJson.semanticEquals(json))

  test("tcp fault: probabilistic object form requires 'probability' and 'type'"):
    val json = parse("""{"probability":0.25,"type":"EMPTY_RESPONSE"}""")
    val fault = TcpFault.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(fault, TcpFault(TcpFaultKind.EmptyResponse, Some(0.25)))
    assert(fault.toJson.semanticEquals(json))

  test("tcp fault: object form without 'probability' is a decode error"):
    assert(TcpFault.fromJson(parse("""{"type":"EMPTY_RESPONSE"}""")).isLeft)

  // ── 2. ProxyResponse.predicateGenerators — raw predicate objects, not field-name strings ──────
  // Proof: types.rs:772-789 (Vec<serde_json::Value>); ProxyResponse.java:30;
  // docs/mountebank/proxy.md:91-92; zio-bdd RiftProtocol.scala:216.
  test("proxy predicateGenerators are raw passthrough JSON objects"):
    val json = parse(
      """{"to":"http://upstream","mode":"proxyOnce",
        |"predicateGenerators":[{"matches":{"method":true}},{"matches":{"path":true}}]}""".stripMargin
        .replace("\n", "")
    )
    val proxy = ProxyResponse.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(
      proxy.predicateGenerators,
      Vector(
        Json.obj("matches" -> Json.obj("method" -> Json.Bool(true))),
        Json.obj("matches" -> Json.obj("path" -> Json.Bool(true)))
      )
    )
    assert(proxy.toJson.semanticEquals(json))

  test("generateBy(Method, Path) produces one raw predicate object per field"):
    val proxy = ProxyResponse(
      "http://upstream",
      predicateGenerators = Vector(RequestField.Method, RequestField.Path).map(_.toGeneratorJson)
    )
    assert(
      proxy.toJson.semanticEquals(
        parse(
          """{"to":"http://upstream","mode":"proxyAlways",
            |"predicateGenerators":[{"matches":{"method":true}},{"matches":{"path":true}}]}""".stripMargin
            .replace("\n", "")
        )
      )
    )

  // ── 3. ScriptSource.File — key is "file", not "path" ────────────────────────────────────────
  // Proof: types.rs:1213-1217; RiftScriptConfig.java:13,34,42.
  test("script file source uses the 'file' wire key"):
    val json = parse("""{"engine":"rhai","file":"scripts/respond.rhai"}""")
    val src = ScriptSource.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(src, ScriptSource.File(ScriptEngine.Rhai, "scripts/respond.rhai"))
    assert(src.toJson.semanticEquals(json))

  // ── 4. RiftConfig.flowState — "backend" key + nested "redis" object ─────────────────────────
  // Proof: types.rs:888-923; RiftFlowStateConfig.java; zio-bdd RiftProtocol.scala:36.
  test("flowState backend key is 'backend', not 'kind'"):
    val json = parse("""{"backend":"inmemory","ttlSeconds":300}""")
    val cfg = FlowStateConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(cfg.backend, FlowStateBackend.InMemory)
    assert(cfg.toJson.semanticEquals(json))

  test("flowState redis backend nests url/poolSize/keyPrefix under 'redis'"):
    val json = parse(
      """{"backend":"redis","ttlSeconds":60,
        |"redis":{"url":"redis://localhost:6379","poolSize":20,"keyPrefix":"myapp:"}}""".stripMargin
        .replace("\n", "")
    )
    val cfg = FlowStateConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(
      cfg.backend,
      FlowStateBackend.Redis(RedisConfig("redis://localhost:6379", 20, "myapp:"))
    )
    assert(cfg.toJson.semanticEquals(json))

  // ── 5. TlsMaterial — cert/key are flat top-level imposter fields, no "tls" wrapper ──────────
  // Proof: types.rs:812-817; ImposterDefinition.java:37-38,141-142.
  test("cert/key are flat on the imposter, not nested under 'tls'"):
    val json = parse("""{"protocol":"https","cert":"CERT-PEM","key":"KEY-PEM"}""")
    val d = ImposterDefinition.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(d.tls, Some(TlsMaterial("CERT-PEM", "KEY-PEM")))
    assertEquals(d.toJson.get("tls"), None)
    assert(d.toJson.semanticEquals(json))

  // ── 6. RiftConfig.scriptEngine — "defaultEngine" key, not "default" ─────────────────────────
  // Proof: types.rs:1008-1017; RiftScriptEngineConfig.java:8.
  test("scriptEngine default key is 'defaultEngine', not 'default'"):
    val json = parse("""{"defaultEngine":"rhai","timeoutMs":5000}""")
    val cfg = ScriptEngineConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(cfg.default, ScriptEngine.Rhai)
    assert(cfg.toJson.semanticEquals(json))

  // ── 7. RiftConfig.metrics — {enabled, port} object, not a bare boolean ───────────────────────
  // Proof: types.rs:948-961; RiftMetricsConfig.java.
  test("metrics is an object with enabled/port, not a bare boolean"):
    val json = parse("""{"enabled":true,"port":9091}""")
    val cfg = MetricsConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(cfg, MetricsConfig(true, 9091))
    assert(cfg.toJson.semanticEquals(json))

  // ── 8. FaultConfig.error.body — a raw wire string, not typed JSON ────────────────────────────
  // Proof: types.rs:1184-1185; RiftErrorFault.java:11.
  test("error fault body is a JSON string, not a nested JSON value"):
    val json = parse("""{"probability":1.0,"status":503,"body":"{\"error\":\"flaky\"}"}""")
    val fault = ErrorFault.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(fault.body, Some("""{"error":"flaky"}"""))
    assert(fault.toJson.semanticEquals(json))

  // ── 9. ScriptEngine.JavaScript — canonical encoding is "javascript"; "js" decodes too ────────
  // Proof: RiftScriptEngineConfig.java:8 documents "rhai"/"javascript"; zio-bdd RiftProtocol.scala:
  // 178 (`case ScriptEngine.JavaScript => "javascript"`).
  test("JavaScript engine encodes as 'javascript'"):
    assert(ScriptEngine.JavaScript.toJson.semanticEquals(Json.Str("javascript")))

  test("JavaScript engine decodes from both 'js' and 'javascript'"):
    assertEquals(ScriptEngine.fromJson(Json.Str("js")), Right(ScriptEngine.JavaScript))
    assertEquals(ScriptEngine.fromJson(Json.Str("javascript")), Right(ScriptEngine.JavaScript))

  // ── 10. WaitBehavior — all four forms round-trip to their own spelling ───────────────────────
  // Proof: the rift#608 ruling (2026-07-14) — "Both forms are canonical. The engine adds an
  // `Inject { inject: String }` variant; the bare string remains the Mountebank-compatible
  // spelling", and serialization must round-trip so `GET /imposters?replayable=true` preserves the
  // author's spelling. Variant set mirrors rift-java WaitSpec.java:7-10 (Fixed|Range|Inject|Script);
  // engine behaviors/wait.rs:33-46 carries Fixed|Range|Function today.
  test("wait: fixed millis is a bare number"):
    val json = parse("100")
    val w = WaitBehavior.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(w, WaitBehavior.Fixed(100L))
    assert(w.toJson.semanticEquals(json))

  test("wait: range is {min,max} — a Rift extension with no Mountebank equivalent"):
    val json = parse("""{"min":100,"max":500}""")
    val w = WaitBehavior.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(w, WaitBehavior.Range(100L, 500L))
    assert(w.toJson.semanticEquals(json))

  test("wait: inject is {inject: <script>} — the Rift/SDK spelling"):
    val json = parse("""{"inject":"function () { return 42; }"}""")
    val w = WaitBehavior.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(w, WaitBehavior.Inject("function () { return 42; }"))
    assert(w.toJson.semanticEquals(json))

  test("wait: a bare string is the Mountebank-compatible Script spelling"):
    val json = parse(""""function () { return 42; }"""")
    val w = WaitBehavior.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(w, WaitBehavior.Script("function () { return 42; }"))
    assert(w.toJson.semanticEquals(json))

  /** The two object shapes are disjoint (`inject` vs `min`+`max`), which is what makes the untagged
    * decode unambiguous — the engine ruling relies on the same property.
    */
  test("wait: the object forms do not collide"):
    assertEquals(
      WaitBehavior.fromJson(parse("""{"inject":"f"}""")),
      Right(WaitBehavior.Inject("f"))
    )
    assertEquals(
      WaitBehavior.fromJson(parse("""{"min":1,"max":2}""")),
      Right(WaitBehavior.Range(1L, 2L))
    )

  test("wait: an object that is neither inject nor min/max is a decode error"):
    assert(WaitBehavior.fromJson(parse("""{"bogus":true}""")).isLeft)

  test("wait: {min} without {max} is a decode error, not a silent default"):
    assert(WaitBehavior.fromJson(parse("""{"min":1}""")).isLeft)
    assert(WaitBehavior.fromJson(parse("""{"max":2}""")).isLeft)

  test("wait: a non-number, non-string, non-object is a decode error"):
    assert(WaitBehavior.fromJson(parse("true")).isLeft)
    assert(WaitBehavior.fromJson(parse("null")).isLeft)

  /** The engine types waits as `u64` and rift-java's `asLong` throws on a non-integral number, so a
    * fractional or out-of-range wait must be loud here too. `BigDecimal.toLong` would silently
    * truncate `1.5` to `1` and wrap `1e30` to 5076944270305263616 — re-encoding a config the author
    * never wrote.
    */
  test("wait: a fractional or out-of-range number is a decode error, not a truncation"):
    assert(WaitBehavior.fromJson(parse("1.5")).isLeft, "1.5 must not truncate to 1")
    assert(WaitBehavior.fromJson(parse("1e30")).isLeft, "1e30 must not wrap around")
    assert(WaitBehavior.fromJson(parse("""{"min":1.5,"max":2}""")).isLeft)
    assert(WaitBehavior.fromJson(parse("""{"min":1,"max":1e30}""")).isLeft)

  /** `inject` wins over `min`/`max`, matching rift-java's `WaitSpec.read` ordering. Pinned so the
    * precedence is a decision rather than an accident of match order.
    */
  test("wait: inject takes precedence over min/max on a malformed mixed object"):
    assertEquals(
      WaitBehavior.fromJson(parse("""{"inject":"f","min":1,"max":2}""")),
      Right(WaitBehavior.Inject("f"))
    )

  /** Decode stays faithful to the wire even when the range is inverted — validation belongs to the
    * DSL constructor (`afterBetween` rejects it), not to reading back what an engine already holds.
    */
  test("wait: decode does not validate an inverted range — the DSL does"):
    assertEquals(
      WaitBehavior.fromJson(parse("""{"min":5,"max":2}""")),
      Right(WaitBehavior.Range(5L, 2L))
    )

  /** Gate: a malformed wait must name the field it failed on. `Behaviors.fromJson` supplies the
    * `wait` path via `.under("wait")` — asserting only `isLeft` would let that wrap silently
    * vanish.
    */
  test("wait: a malformed wait names the offending field"):
    List("""{"wait":{"bogus":true}}""", """{"wait":{"min":1}}""", """{"wait":1.5}""").foreach:
      raw =>
        Behaviors.fromJson(parse(raw)) match
          case Left(e) =>
            assert(e.toString.contains("wait"), s"$raw: error did not name 'wait': $e")
          case Right(b) => fail(s"$raw should not decode, got $b")

  test("wait: every form survives a Behaviors round-trip"):
    List(
      "100",
      """{"min":100,"max":500}""",
      """{"inject":"function () { return 1; }"}""",
      """"function () { return 1; }""""
    ).foreach: raw =>
      val json = parse(s"""{"wait":$raw}""")
      val b = Behaviors.fromJson(json).fold(e => fail(s"$raw: $e"), identity)
      assert(b.toJson.semanticEquals(json), s"$raw did not round-trip: ${b.toJson.render}")
