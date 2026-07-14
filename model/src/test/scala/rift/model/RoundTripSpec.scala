package rift.model

import rift.json.Json
import scala.io.Source
import scala.util.Using

/** AC1 — the headline acceptance criterion of issue #2: decode -> encode round-trips every example
  * imposter from the rift repo under `semanticEquals`.
  *
  * Fixtures are vendored from rift v0.13.5; see resources/examples/PROVENANCE.md.
  */
class RoundTripSpec extends munit.FunSuite:

  private val fixtures = List(
    "authentication-api.json",
    "basic-api.json",
    "error-testing.json",
    "feature-flags-api.json",
    "latency-testing.json",
    "task-management-api.json"
  )

  private def load(name: String): Json =
    val text = Using.resource(Source.fromResource(s"examples/$name"))(_.mkString)
    Json.parse(text).fold(e => fail(s"fixture $name is not valid JSON: $e"), identity)

  /** Each fixture is `{"imposters": [ ... ]}`. */
  private def imposters(doc: Json): Vector[Json] = doc.get("imposters") match
    case Some(Json.Arr(items)) => items
    case other => fail(s"expected an 'imposters' array, got: $other")

  test("every fixture parses and contains at least one imposter"):
    fixtures.foreach: name =>
      assert(imposters(load(name)).nonEmpty, s"$name has no imposters")

  fixtures.foreach: name =>
    test(s"$name round-trips decode -> encode under semanticEquals"):
      imposters(load(name)).zipWithIndex.foreach: (raw, i) =>
        val decoded = ImposterDefinition.fromJson(raw) match
          case Right(d) => d
          case Left(e) => fail(s"$name imposter[$i] failed to decode: $e\nraw: ${raw.renderPretty}")
        val encoded = decoded.toJson
        assert(
          encoded.semanticEquals(raw),
          s"""$name imposter[$i] did not round-trip.
             |--- expected (fixture) ---
             |${raw.renderPretty}
             |--- actual (re-encoded) ---
             |${encoded.renderPretty}""".stripMargin
        )

  test("round-tripping is idempotent (encode -> decode -> encode is stable)"):
    fixtures.foreach: name =>
      imposters(load(name)).foreach: raw =>
        val once = ImposterDefinition.fromJson(raw).fold(e => fail(e.toString), _.toJson)
        val twice = ImposterDefinition.fromJson(once).fold(e => fail(e.toString), _.toJson)
        assert(once.semanticEquals(twice), s"$name is not idempotent under round-trip")
