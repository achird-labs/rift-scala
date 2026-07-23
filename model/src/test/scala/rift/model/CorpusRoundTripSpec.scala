package rift.model

import rift.json.Json
import scala.io.Source
import scala.util.Using

/** Round-trips every `sdk-conformance-v0.16.0` corpus fixture through `ImposterDefinition`
  * (`fromJson` -> `toJson` -> `semanticEquals`). Each fixture is a single imposter. This is the
  * model-layer DSL<->engine parity gate (issue #54; RFC-003 §9.2) — the corpus is engine-canonical,
  * and a fixture the model can't round-trip is a real parity gap. Fixtures vendored from the engine
  * release; see `corpus/PROVENANCE.md`.
  */
class CorpusRoundTripSpec extends munit.FunSuite:

  private val fixtures = List(
    "01-basic-rest",
    "02-predicates",
    "03-behaviors",
    "04-fault-injection",
    "05-scripting-engines",
    "06-stateful-retry",
    "07-proxy-record",
    "10-correlated-isolation",
    "11-headers-and-templates",
    "13-js-config-decorate",
    "14-verify-annotations",
    "15-predicate-modifiers",
    "16-behaviors-advanced",
    "17-faults-and-binary",
    "20-mimeo-solo-compat"
  )

  private def load(name: String): Json =
    val text = Using.resource(Source.fromResource(s"corpus/$name.json"))(_.mkString)
    Json.parse(text).fold(e => fail(s"$name is not valid JSON: $e"), identity)

  fixtures.foreach: name =>
    test(s"corpus $name round-trips decode -> encode under semanticEquals"):
      val raw = load(name)
      val decoded = ImposterDefinition.fromJson(raw) match
        case Right(d) => d
        case Left(e) => fail(s"$name failed to decode: $e\nraw: ${raw.renderPretty}")
      val encoded = decoded.toJson
      assert(
        encoded.semanticEquals(raw),
        s"""$name did not round-trip.
           |--- expected (fixture) ---
           |${raw.renderPretty}
           |--- actual (re-encoded) ---
           |${encoded.renderPretty}""".stripMargin
      )
