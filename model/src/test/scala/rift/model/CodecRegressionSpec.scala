package rift.model

import rift.dsl.*
import rift.json.Json

/** Regressions for round-trip defects found in review. `RoundTripSpec` missed all of these because
  * the six vendored fixtures happen to populate every field the codec was fabricating.
  */
class CodecRegressionSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  /** `predicates`/`responses` decode as optional but always encode, mirroring rift-java
    * (Stub.java:88-89 read via `optArray`, :103-104 write unconditionally). An absent key and an
    * empty array are the same thing to the engine, so this normalizes rather than loses data — and
    * it keeps the Scala and Java SDKs byte-comparable on the wire (D2).
    */
  test("a predicate-less catch-all stub decodes and normalizes like rift-java"):
    val stub = Stub
      .fromJson(parse("""{"responses":[{"is":{"statusCode":200}}]}"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(stub.predicates, Vector.empty)
    assertEquals(stub.toJson.get("predicates"), Some(Json.Arr(Vector.empty)))

  test("an explicit empty predicates array survives a round-trip"):
    val src = """{"predicates":[],"responses":[{"is":{"statusCode":200}}]}"""
    val stub = Stub.fromJson(parse(src)).fold(e => fail(e.toString), identity)
    assert(stub.toJson.semanticEquals(parse(src)))

  test("a catch-all stub inside an imposter still round-trips once normalized"):
    val src =
      """{"protocol":"http","stubs":[{"predicates":[],"responses":[{"is":{"statusCode":200}}]}]}"""
    val d = ImposterDefinition.fromJson(parse(src)).fold(e => fail(e.toString), identity)
    assert(d.toJson.semanticEquals(parse(src)))

  test("scenario states without a scenarioName are a decode error, not silent data loss"):
    val orphan = """{"predicates":[],"responses":[],"requiredScenarioState":"x"}"""
    Stub.fromJson(parse(orphan)) match
      case Left(e) => assert(e.toString.contains("scenarioName"), s"unhelpful error: $e")
      case Right(s) => fail(s"expected a decode error, got: ${s.toJson.render}")
    assert(Stub.fromJson(parse("""{"newScenarioState":"y"}""")).isLeft)

  test("imposterFromJson preserves unknown engine keys through the escape hatch"):
    val src = """{"port":4545,"protocol":"http","customEngineKey":123}"""
    val b = imposterFromJson(src).fold(e => fail(e.toString), identity)
    assert(
      b.build.toJson.semanticEquals(parse(src)),
      s"escape hatch lost data: ${b.build.toJson.render}"
    )

  test("imposterFromJson does not fabricate a name"):
    val b =
      imposterFromJson("""{"port":4545,"protocol":"http"}""").fold(e => fail(e.toString), identity)
    assertEquals(b.build.name, None)
    assertEquals(b.build.toJson.get("name"), None)

  test("imposterFromJson keeps an explicit name"):
    val b =
      imposterFromJson("""{"protocol":"http","name":"raw"}""").fold(e => fail(e.toString), identity)
    assertEquals(b.build.name, Some("raw"))

  /** rift-java decodes `protocol` as optional with an "http" default (ImposterDefinition.java:108)
    * and the engine does the same (`default_protocol`), so always emitting it is parity with the
    * reference implementation (D2) and semantically identity — not a fabrication.
    */
  test("protocol is always emitted, matching rift-java and the engine default"):
    assertEquals(ImposterDefinition().toJson.get("protocol"), Some(Json.Str("http")))
    val d =
      ImposterDefinition.fromJson(parse("""{"port":4545}""")).fold(e => fail(e.toString), identity)
    assertEquals(d.protocol, Protocol.Http)
