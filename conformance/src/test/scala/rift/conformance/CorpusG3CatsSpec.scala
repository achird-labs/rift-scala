package rift.conformance

import java.net.http.HttpResponse

import _root_.cats.effect.{IO, Resource}
import munit.CatsEffectSuite

import rift.cats.Rift
import rift.model.Port

import io.github.achirdlabs.rift.Rift as JRift

import CorpusReplay.VerifyStep

/** G3 on the cats surface (issue #13) — the same corpus replay `CorpusG3Spec` (#6) runs over
  * `rift.zio.Rift`, replayed here over `rift.cats.Rift[IO]`. Shares `_verify` parsing and the HTTP
  * fire mechanics with the zio spec via `CorpusReplay`; only the effect wrapping (cats-effect `IO`
  * instead of `ZIO`) and engine wiring (`Resource` instead of `ZLayer`) differ.
  *
  * Guarded exactly like `rift.cats.EmbeddedSmokeSpec`: `JRift.isEmbeddedAvailable()` is checked
  * BEFORE any engine `Resource` is acquired, so a bare CI JVM (no `rift-java-natives` /
  * `--enable-native-access`) skips cleanly and this spec's only job on such a JVM is to COMPILE —
  * UNLESS `RIFT_G3_REQUIRE=embedded` (issue #63), which turns an unavailable engine on the job that
  * requires the embedded lane into a `fail` (red build) rather than a silent `assume`-skip.
  */
class CorpusG3CatsSpec extends CatsEffectSuite:

  private def fire(port: Int, step: VerifyStep): IO[HttpResponse[String]] =
    IO.blocking(CorpusReplay.fireBlocking(port, step))

  private def assertStep(step: VerifyStep, response: HttpResponse[String]): Unit =
    assertEquals(response.statusCode(), step.expectStatus)
    step.expectBodyContains.foreach { sub =>
      assert(
        response.body().contains(sub),
        s"expected body to contain '$sub', got: ${response.body()}"
      )
    }
    step.expectBodyEquals.foreach(expected => assertEquals(response.body(), expected))

  /** Sequential `IO[Unit]` composition without pulling in `cats.syntax.traverse` — a plain fold
    * keeps this file's imports to just `cats.effect`, matching the module's "cats-effect only"
    * convention (DESIGN.md §5.6) even in test sources.
    */
  private def runAll(ios: Vector[IO[Unit]]): IO[Unit] =
    ios.foldLeft(IO.unit)((acc, next) => acc.flatMap(_ => next))

  // `Fixture` is qualified below via `_root_`: `munit.FunSuite` declares its own `Fixture[T]` (test
  // setup/teardown), which shadows this package's `rift.conformance.Fixture` corpus-manifest case
  // class inside a munit suite body — and a plain `rift.conformance.Fixture` would instead resolve
  // `rift` to the `rift: Rift[IO]` parameter below, so the reference must be fully rooted.
  private def replayFixture(
      rift: Rift[IO],
      fixture: _root_.rift.conformance.Fixture
  ): IO[Unit] =
    // Bracket the imposter so a fire failure can't leak it: the release deletes it even when the
    // replay fails, not only on the happy path (mirrors CorpusG3Spec's acquireReleaseWith). The
    // release is `delete` itself, not a swallowed `attempt` — cats-effect composes a release failure
    // with the body outcome, so a teardown error surfaces rather than vanishing.
    Resource
      .make(rift.createFromJson(Corpus.imposterRaw(fixture)))(_.delete)
      .use { imp =>
        val steps = CorpusReplay.verifySteps(Corpus.imposterJson(fixture))
        val port = Port.value(imp.port)
        runAll(steps.map(step => fire(port, step).map(assertStep(step, _))))
      }

  private def replayOrSkip(rift: Rift[IO], fixture: _root_.rift.conformance.Fixture): IO[Unit] =
    val ungated = fixture.requires -- CorpusReplay.supportedCapabilities
    if ungated.nonEmpty then IO.unit // beyond the embedded lane's supported capability set — skip
    else replayFixture(rift, fixture)

  test("replay every hasVerify fixture the embedded lane supports (cats surface)"):
    G3Require.decideEmbedded(JRift.isEmbeddedAvailable(), G3Require.required) match
      case G3Require.Decision.Skip =>
        assume(false, "embedded runtime not on the classpath — skipping")
        IO.unit
      // Fail loudly (matrix-drift backstop, #63). Don't weaken to a skip without re-proving red.
      case G3Require.Decision.Fail(reason) => fail(reason)
      case G3Require.Decision.Run =>
        Rift.embedded[IO].use { rift =>
          runAll(Corpus.fixtures.filter(_.hasVerify).map(replayOrSkip(rift, _)))
        }
