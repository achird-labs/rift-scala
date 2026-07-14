package rift.model

import rift.json.Json

class ModelCodecSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)
  private def decode(s: String): ImposterDefinition =
    ImposterDefinition.fromJson(parse(s)).fold(e => fail(e.toString), identity)

  // ── AC5: explicit ports respected verbatim ────────────────────────────────
  test("an explicit port survives decode -> encode verbatim"):
    val d = decode("""{"port":4545,"protocol":"http"}""")
    assertEquals(d.port.map(Port.value), Some(4545))
    assertEquals(d.toJson.get("port"), Some(Json.Num(BigDecimal(4545))))

  test("an absent port stays absent on the wire (engine assigns)"):
    val d = decode("""{"protocol":"http"}""")
    assertEquals(d.port, None)
    assertEquals(d.toJson.get("port"), None)

  test("Port rejects out-of-range values and accepts the boundaries"):
    assert(Port.from(0).isLeft)
    assert(Port.from(65536).isLeft)
    assert(Port.from(-1).isLeft)
    assertEquals(Port.from(1).map(Port.value), Right(1))
    assertEquals(Port.from(65535).map(Port.value), Right(65535))

  test("decoding an out-of-range port is an error, not a silent clamp"):
    assert(ImposterDefinition.fromJson(parse("""{"port":99999}""")).isLeft)

  // ── AC2: unknown keys survive via `extra` ─────────────────────────────────
  test("unknown imposter keys are preserved through a round-trip"):
    val src = """{"protocol":"http","futureKnob":{"a":[1]},"anotherOne":true}"""
    val d = decode(src)
    assertEquals(d.extra.map(_._1).toSet, Set("futureKnob", "anotherOne"))
    assert(d.toJson.semanticEquals(parse(src)), "unknown keys must survive re-encoding")

  test("unknown stub and response keys are preserved"):
    val src =
      """{"protocol":"http","stubs":[{"predicates":[],"responses":[{"is":{"statusCode":200},
        |"futureResponseKey":1}],"futureStubKey":"x"}]}""".stripMargin.replace("\n", "")
    assert(decode(src).toJson.semanticEquals(parse(src)))

  test("a modeled key appearing in `extra` is a construction error"):
    // `port` is modeled, so it must never be smuggled through `extra` (rift-java 0.1.1 policy)
    val bad = ImposterDefinition(extra = Vector("port" -> Json.Num(BigDecimal(1))))
    intercept[IllegalArgumentException](bad.toJson)

  test("`extra` rejects duplicate keys"):
    intercept[IllegalArgumentException]:
      ImposterDefinition(extra = Vector("a" -> Json.Null, "a" -> Json.Null)).toJson

  // ── wire-shape fidelity ───────────────────────────────────────────────────
  test("allowCORS keeps its exact wire casing"):
    val d = decode("""{"protocol":"http","allowCORS":true}""")
    assertEquals(d.allowCors, true)
    assertEquals(d.toJson.get("allowCORS"), Some(Json.Bool(true)))

  test("default-valued fields are omitted from the wire"):
    val encoded = ImposterDefinition().toJson
    assertEquals(encoded.get("recordRequests"), None)
    assertEquals(encoded.get("allowCORS"), None)
    assertEquals(encoded.get("stubs"), None)

  test("a bare scenarioName decodes without state transitions"):
    // the fixtures use scenarioName purely as a label
    val d = decode("""{"protocol":"http","stubs":[{"scenarioName":"S","predicates":[],
      |"responses":[{"is":{"statusCode":200}}]}]}""".stripMargin.replace("\n", ""))
    assertEquals(d.stubs.head.scenario, Some(ScenarioRef("S", None, None)))

  test("the full scenario triplet round-trips"):
    val src = """{"protocol":"http","stubs":[{"scenarioName":"checkout",
      |"requiredScenarioState":"Started","newScenarioState":"paid","predicates":[],
      |"responses":[{"is":{"statusCode":200}}]}]}""".stripMargin.replace("\n", "")
    val d = decode(src)
    assertEquals(
      d.stubs.head.scenario,
      Some(ScenarioRef("checkout", Some("Started"), Some("paid")))
    )
    assert(d.toJson.semanticEquals(parse(src)))

  test("custom HTTP methods survive as Method.Custom"):
    val src =
      """{"predicates":[{"equals":{"method":"PURGE"}}],"responses":[{"is":{"statusCode":200}}]}"""
    val stub = Stub.fromJson(parse(src)).fold(e => fail(e.toString), identity)
    assert(stub.toJson.semanticEquals(parse(src)))

  test("predicate params sit alongside the operator key, not inside it"):
    // { "jsonpath": {...}, "exists": {...} } — the shape used by task-management-api.json
    val src = """{"jsonpath":{"selector":"$.name"},"exists":{"body":true}}"""
    val p = Predicate.fromJson(parse(src)).fold(e => fail(e.toString), identity)
    assertEquals(p.params.selector, Some(PredicateSelector.JsonPath("$.name")))
    assert(p.toJson.semanticEquals(parse(src)))

  test("_behaviors wait round-trips as both a fixed number and an inject"):
    val fixed = """{"is":{"statusCode":200},"_behaviors":{"wait":100}}"""
    val inject =
      """{"is":{"statusCode":200},"_behaviors":{"wait":{"inject":"function(){return 1;}"}}}"""
    List(fixed, inject).foreach: src =>
      val r = Response.fromJson(parse(src)).fold(e => fail(e.toString), identity)
      assert(r.toJson.semanticEquals(parse(src)), s"did not round-trip: $src")

  test("an unknown behavior key survives as Behaviors.Unknown"):
    val src = """{"is":{"statusCode":200},"_behaviors":{"someFutureBehavior":{"x":1}}}"""
    val r = Response.fromJson(parse(src)).fold(e => fail(e.toString), identity)
    assert(r.toJson.semanticEquals(parse(src)))

  test("decode errors identify the offending path rather than throwing"):
    val e = ImposterDefinition.fromJson(parse("""{"protocol":"gopher"}""")).left.toOption.get
    assert(e.toString.contains("protocol"), s"error should mention the field, got: $e")
