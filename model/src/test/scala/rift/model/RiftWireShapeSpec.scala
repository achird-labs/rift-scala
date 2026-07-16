package rift.model

import rift.json.Json

/** Round-trip coverage for the nine wire-shape bugs fixed in this change: each fixture below is the
  * exact wire JSON the authoritative Rust engine, rift-java, and zio-bdd agree on — asserted
  * against `semanticEquals` on both the decode and the encode side.
  */
class RiftWireShapeSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  // ── 1. FaultConfig.tcp — bare string (always-fires) or {probability, type} ─────────────────
  // Proof: rift RiftTcpFault (crates/rift-mock-core/src/imposter/types.rs:1095-1154 @ v0.14.0);
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
  // Proof: types.rs:797-813 @ v0.14.0 (Vec<serde_json::Value>); ProxyResponse.java:30;
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
  // Proof: types.rs:1226-1246 @ v0.14.0 (`file` at :1240); RiftScriptConfig.java:13,34,42.
  test("script file source uses the 'file' wire key"):
    val json = parse("""{"engine":"rhai","file":"scripts/respond.rhai"}""")
    val src = ScriptSource.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(src, ScriptSource.File(ScriptEngine.Rhai, "scripts/respond.rhai"))
    assert(src.toJson.semanticEquals(json))

  // ── 4. RiftConfig.flowState — "backend" key + nested "redis" object ─────────────────────────
  // Proof: types.rs:911-927 @ v0.14.0; RiftFlowStateConfig.java; zio-bdd RiftProtocol.scala:36.
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
  // Proof: types.rs:835-840 @ v0.14.0; ImposterDefinition.java:37-38,141-142.
  test("cert/key are flat on the imposter, not nested under 'tls'"):
    val json = parse("""{"protocol":"https","cert":"CERT-PEM","key":"KEY-PEM"}""")
    val d = ImposterDefinition.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(d.tls, Some(TlsMaterial("CERT-PEM", "KEY-PEM")))
    assertEquals(d.toJson.get("tls"), None)
    assert(d.toJson.semanticEquals(json))

  // ── 6. RiftConfig.scriptEngine — "defaultEngine" key, not "default" ─────────────────────────
  // Proof: types.rs:1031-1041 @ v0.14.0; RiftScriptEngineConfig.java:8.
  test("scriptEngine default key is 'defaultEngine', not 'default'"):
    val json = parse("""{"defaultEngine":"rhai","timeoutMs":5000}""")
    val cfg = ScriptEngineConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(cfg.default, ScriptEngine.Rhai)
    assert(cfg.toJson.semanticEquals(json))

  // ── 7. RiftConfig.metrics — {enabled, port} object, not a bare boolean ───────────────────────
  // Proof: types.rs:971-980 @ v0.14.0; RiftMetricsConfig.java.
  test("metrics is an object with enabled/port, not a bare boolean"):
    val json = parse("""{"enabled":true,"port":9091}""")
    val cfg = MetricsConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(cfg, MetricsConfig(true, 9091))
    assert(cfg.toJson.semanticEquals(json))

  // ── 8. FaultConfig.error.body — a raw wire string, not typed JSON ────────────────────────────
  // Proof: types.rs:1208 @ v0.14.0 (`body: Option<String>`); RiftErrorFault.java:11.
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
  // as of engine 0.14.0 all four are first-class: behaviors/wait.rs:35-52 carries
  // Fixed|Range|Function|Inject, and its tests pin that each spelling round-trips to itself.
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

  // ── 11. Behaviors array form — what the engine EMITS on GET /imposters ───────────────────────
  // Proof: engine `behaviors_to_array` (imposter/types.rs:702-723 @ v0.14.0) renders `_behaviors` as an array
  // of single-key objects on read-back; rift-java `Behaviors.readArray` accepts it and always
  // serializes the object form back. This is the read path #3's `definition()` will take.
  test("behaviors: the engine's array-of-single-key-objects form decodes"):
    val json = parse("""[{"wait":100},{"decorate":"function () {}"},{"repeat":3}]""")
    val b = Behaviors.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(b.waitFor, Some(WaitBehavior.Fixed(100L)))
    assertEquals(b.decorate, Some("function () {}"))
    assertEquals(b.repeat, Some(3))

  test("behaviors: the object form still decodes identically"):
    val arr = parse("""[{"wait":100},{"repeat":3}]""")
    val obj = parse("""{"wait":100,"repeat":3}""")
    assertEquals(Behaviors.fromJson(arr), Behaviors.fromJson(obj))

  /** Encoding always uses the object form — rift-java's stated policy verbatim, so a GET -> PUT
    * normalizes spelling identically in both SDKs.
    */
  test("behaviors: an array decodes and re-encodes as the object form"):
    val b = Behaviors
      .fromJson(parse("""[{"wait":100},{"repeat":3}]"""))
      .fold(e => fail(e.toString), identity)
    assert(b.toJson.semanticEquals(parse("""{"wait":100,"repeat":3}""")))

  test("behaviors: repeated vector-valued keys in an array accumulate"):
    val json = parse(
      """[{"copy":{"from":"path","into":"$1","using":{"method":"regex","selector":"."}}},
                        |{"copy":{"from":"body","into":"$2","using":{"method":"regex","selector":"."}}}]""".stripMargin
        .replace("\n", "")
    )
    val b = Behaviors.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(b.copyEntries.size, 2)
    // and they survive the round-trip into the object form's single `copy` array
    assertEquals(b.toJson.get("copy").flatMap(_.asArray).map(_.size), Some(2))

  test("behaviors: an array entry with zero or several keys is a decode error"):
    assert(Behaviors.fromJson(parse("""[{}]""")).isLeft)
    assert(Behaviors.fromJson(parse("""[{"wait":100,"repeat":3}]""")).isLeft)

  test("behaviors: a non-object array entry is a decode error"):
    assert(Behaviors.fromJson(parse("""["wait"]""")).isLeft)

  /** A repeated scalar key has no object-form representation, so silently keeping the last would
    * lose data on the very next encode. Fail loudly instead.
    */
  test("behaviors: a repeated scalar key in an array is a decode error, not last-wins"):
    assert(Behaviors.fromJson(parse("""[{"wait":100},{"wait":200}]""")).isLeft)
    assert(Behaviors.fromJson(parse("""[{"repeat":1},{"repeat":2}]""")).isLeft)

  test("behaviors: an empty array is an empty Behaviors"):
    assertEquals(Behaviors.fromJson(parse("[]")), Right(Behaviors.empty))

  /** Forward compatibility, the array-form half. Asserting only that the *key* survived would miss
    * the failure that matters: an unknown key is not vector-valued just because the model does not
    * know it, so array-wrapping its value would hand the engine back `{"futureThing":[{...}]}` on
    * the next PUT — a document the author never wrote.
    */
  test("behaviors: an unknown key's value round-trips unchanged through the array form"):
    val b = Behaviors
      .fromJson(parse("""[{"wait":100},{"futureThing":{"x":1}}]"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(b.unknown, Vector("futureThing" -> parse("""{"x":1}""")))
    assert(
      b.toJson.semanticEquals(parse("""{"wait":100,"futureThing":{"x":1}}""")),
      s"unknown key was reshaped: ${b.toJson.render}"
    )

  test("behaviors: the two forms agree on an unknown key"):
    assertEquals(
      Behaviors.fromJson(parse("""[{"futureThing":{"x":1}}]""")),
      Behaviors.fromJson(parse("""{"futureThing":{"x":1}}"""))
    )

  test("behaviors: an unknown scalar value is not promoted to an array"):
    val b =
      Behaviors.fromJson(parse("""[{"futureThing":5}]""")).fold(e => fail(e.toString), identity)
    assert(b.toJson.semanticEquals(parse("""{"futureThing":5}""")), b.toJson.render)

  test("behaviors: a repeated unknown key is a decode error, not a silent merge"):
    assert(Behaviors.fromJson(parse("""[{"futureThing":1},{"futureThing":2}]""")).isLeft)

  test("behaviors: an array entry whose value is already an array is not double-wrapped"):
    val one = """{"from":"path","into":"$1","using":{"method":"regex","selector":"."}}"""
    val two = """{"from":"body","into":"$2","using":{"method":"regex","selector":"."}}"""
    val b = Behaviors
      .fromJson(parse(s"""[{"copy":[$one,$two]}]"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(b.copyEntries, Vector(parse(one), parse(two)))

  test("behaviors: a vector key mixes single and array entries across the array form"):
    val one = """{"from":"path","into":"$1","using":{"method":"regex","selector":"."}}"""
    val two = """{"from":"body","into":"$2","using":{"method":"regex","selector":"."}}"""
    val b = Behaviors
      .fromJson(parse(s"""[{"copy":$one},{"copy":[$two]}]"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(b.copyEntries, Vector(parse(one), parse(two)))

  test("behaviors: shellTransform's bare-string spelling works through the array form"):
    val b =
      Behaviors
        .fromJson(parse("""[{"shellTransform":"cmd"}]"""))
        .fold(e => fail(e.toString), identity)
    assertEquals(b.shellTransform, Vector("cmd"))

  test("behaviors: a non-array copy is a decode error rather than a silent drop"):
    assert(
      Behaviors.fromJson(parse("""{"copy":{"a":1}}""")).isLeft,
      "object form must not swallow it"
    )
    // an array entry spells a single copy exactly this way, so it stays legal there
    assert(Behaviors.fromJson(parse("""[{"copy":{"a":1}}]""")).isRight)

  test("behaviors: an array decode error names the offending entry index"):
    List("""[{"wait":100},{}]""", """[{"wait":1},{"wait":2}]""", """[{"a":1,"b":2}]""").foreach:
      raw =>
        Behaviors.fromJson(parse(raw)) match
          case Left(e) => assert(e.toString.contains("["), s"$raw: error did not name an entry: $e")
          case Right(b) => fail(s"$raw should not decode, got $b")

  // ── 12. ProxyResponse write-side fields ─────────────────────────────────────────────────────
  // Proof: engine types.rs:803-812 @ v0.14.0; rift-java ProxyResponse.java:27-35 (modeled keys at :38-39),
  // which emits addWaitBehavior only when true and injectHeaders only when non-empty.
  test("proxy: the four write-side fields round-trip"):
    val json = parse(
      """{"to":"http://origin","mode":"proxyOnce","addWaitBehavior":true,
                        |"injectHeaders":{"X-A":"1","X-B":"2"},"addDecorateBehavior":"function () {}",
                        |"pathRewrite":{"from":"^/api","to":"/v2"}}""".stripMargin.replace("\n", "")
    )
    val p = ProxyResponse.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(p.addWaitBehavior, true)
    assertEquals(p.injectHeaders, Vector("X-A" -> "1", "X-B" -> "2"))
    assertEquals(p.addDecorateBehavior, Some("function () {}"))
    assertEquals(p.pathRewrite, Some(PathRewrite("^/api", "/v2")))
    assert(p.toJson.semanticEquals(json), s"did not round-trip: ${p.toJson.render}")

  /** They used to survive only as opaque `extra` entries; decoding must now lift them into typed
    * fields, or the DSL could never build them.
    */
  test("proxy: the write-side fields are lifted out of `extra`"):
    val json = parse("""{"to":"http://o","mode":"proxyAlways","addWaitBehavior":true}""")
    val p = ProxyResponse.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(p.extra, Vector.empty)
    assertEquals(p.addWaitBehavior, true)

  test("proxy: addWaitBehavior is emitted only when true (rift-java parity)"):
    val off = ProxyResponse("http://o").toJson
    assertEquals(off.get("addWaitBehavior"), None)
    val on = ProxyResponse("http://o", addWaitBehavior = true).toJson
    assertEquals(on.get("addWaitBehavior"), Some(Json.Bool(true)))

  test("proxy: injectHeaders is emitted only when non-empty, preserving order"):
    assertEquals(ProxyResponse("http://o").toJson.get("injectHeaders"), None)
    val p = ProxyResponse("http://o", injectHeaders = Vector("B" -> "2", "A" -> "1"))
    assertEquals(
      p.toJson.get("injectHeaders"),
      Some(Json.Obj(Vector("B" -> Json.Str("2"), "A" -> Json.Str("1"))))
    )

  test("proxy: a non-object injectHeaders is a decode error"):
    assert(ProxyResponse.fromJson(parse("""{"to":"http://o","injectHeaders":["X"]}""")).isLeft)

  test("proxy: a pathRewrite missing 'to' is a decode error"):
    assert(
      ProxyResponse.fromJson(parse("""{"to":"http://o","pathRewrite":{"from":"^/x"}}""")).isLeft
    )

  test("proxy: unknown keys still survive on `extra`"):
    val p = ProxyResponse
      .fromJson(parse("""{"to":"http://o","futureKey":1}"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(p.extra.map(_._1), Vector("futureKey"))

  // ── 13. _rift.proxy — imposter-level proxy config ────────────────────────────────────────────
  // Proof: engine types.rs:987-1029 @ v0.14.0; rift-java RiftProxyConfig/RiftUpstreamConfig/
  // RiftConnectionPoolConfig (defaults: protocol "http", maxIdlePerHost 100, idleTimeoutSecs 90).
  test("_rift.proxy: upstream and connectionPool round-trip"):
    val json = parse(
      """{"proxy":{"upstream":{"host":"origin.internal","port":8443,"protocol":"https"},
                        |"connectionPool":{"maxIdlePerHost":50,"idleTimeoutSecs":30}}}""".stripMargin
        .replace("\n", "")
    )
    val c = RiftConfig.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(
      c.proxy,
      Some(
        ProxyConfig(
          Some(UpstreamConfig("origin.internal", 8443, "https")),
          Some(ConnectionPoolConfig(50, 30L))
        )
      )
    )
    assert(c.toJson.semanticEquals(json))

  test("_rift.proxy: protocol defaults to http and the pool defaults match rift-java"):
    val c = RiftConfig
      .fromJson(parse("""{"proxy":{"upstream":{"host":"h","port":80},"connectionPool":{}}}"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(c.proxy.flatMap(_.upstream).map(_.protocol), Some("http"))
    assertEquals(c.proxy.flatMap(_.connectionPool), Some(ConnectionPoolConfig(100, 90L)))

  test("_rift.proxy: upstream without a host or port is a decode error"):
    assert(RiftConfig.fromJson(parse("""{"proxy":{"upstream":{"port":80}}}""")).isLeft)
    assert(RiftConfig.fromJson(parse("""{"proxy":{"upstream":{"host":"h"}}}""")).isLeft)

  test("_rift.proxy: both members are optional"):
    val c = RiftConfig.fromJson(parse("""{"proxy":{}}""")).fold(e => fail(e.toString), identity)
    assertEquals(c.proxy, Some(ProxyConfig(None, None)))
    assert(c.toJson.semanticEquals(parse("""{"proxy":{}}""")))

  // ── 14. RecordedRequest `_mode` — engine 0.14.0's binary-recording marker passes through ─────
  // Proof: engine RecordedRequest (types.rs:59-75 @ v0.14.0, `_mode` at :72, issue #636) adds `_mode: "binary"`
  // when `body` is base64 (omitted for text). rift-java 0.1.2 reads recorded requests leniently
  // and does not type the key; this model mirrors that via the `raw` escape hatch, whose `toJson`
  // re-emits the engine's document verbatim.
  test("recorded request: a 0.14.0 binary recording (_mode) decodes and re-emits verbatim"):
    val json = parse(
      """{"requestFrom":"127.0.0.1:1234","method":"POST","path":"/x","query":{},
        |"headers":{},"body":"//4A","_mode":"binary","timestamp":"2026-01-01T00:00:00Z"}""".stripMargin
        .replace("\n", "")
    )
    val r = RecordedRequest.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(r.body, Some(Json.Str("//4A")))
    assert(r.toJson.semanticEquals(json), s"_mode must survive: ${r.toJson.render}")

  test("recorded request: a pre-0.14.0 recording without _mode still decodes"):
    val json = parse(
      """{"requestFrom":"127.0.0.1:1234","method":"GET","path":"/x","query":{},
        |"headers":{},"body":"hello","timestamp":"2026-01-01T00:00:00Z"}""".stripMargin
        .replace("\n", "")
    )
    val r = RecordedRequest.fromJson(json).fold(e => fail(e.toString), identity)
    assertEquals(r.toJson.get("_mode"), None)
