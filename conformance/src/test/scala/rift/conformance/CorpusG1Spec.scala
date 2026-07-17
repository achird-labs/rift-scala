package rift.conformance

import zio.test.*

import rift.model.ImposterDefinition

/** G1 — codec round-trip (engine-free, UNCONDITIONAL).
  *
  * Every fixture in the vendored corpus must decode through `ImposterDefinition.fromJson` and
  * re-encode to something `semanticEquals` the original wire JSON. This is the anchor gate: it
  * doesn't touch the DSL or the engine at all, so it proves the wire *model* is faithful regardless
  * of what the typed DSL can or can't author (that's G2's job). All 15 fixtures MUST pass — the
  * model was fixed to round-trip this exact corpus in #54.
  */
object CorpusG1Spec extends ZIOSpecDefault:

  def spec = suite("G1 — corpus codec round-trip (issue #6, engine-free)")(
    Corpus.fixtures.map { fixture =>
      test(s"${fixture.id} round-trips through ImposterDefinition.fromJson/.toJson") {
        val original = Corpus.imposterJson(fixture)
        val decoded = ImposterDefinition.fromJson(original)
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.exists(_.toJson.semanticEquals(original)))
      }
    }*
  )
