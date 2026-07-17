package rift.conformance

import java.net.http.HttpResponse

import zio.*
import zio.test.*

import rift.RiftError
import rift.model.Port
import rift.zio.Rift

import io.github.etacassiopeia.rift.Rift as JRift

import CorpusReplay.VerifyStep

/** G3 — replay (engine-required, GUARDED).
  *
  * Guarded exactly like `bridge.EmbeddedSmokeSpec` / `zio-testkit`'s `LedgerPatternSampleSpec`:
  * `JRift.isEmbeddedAvailable()` is checked BEFORE any engine layer is built, so a bare CI JVM (no
  * `rift-java-natives` / `--enable-native-access`) skips (never fails) and this spec's only job on
  * such a JVM is to COMPILE. When an embedded engine IS present, it creates every `hasVerify`
  * fixture the embedded lane can serve (skipping only fixtures whose `requires` names a capability
  * outside `supportedCapabilities` — the README's "capability skips only per the manifest" rule)
  * and replays each stub's `_verify.sequence` over real HTTP.
  *
  * The `_verify` parsing + HTTP fire mechanics live in `CorpusReplay`, shared with the cats-surface
  * `CorpusG3CatsSpec` (#13) — only the ZIO-specific effect wrapping and engine wiring stay here.
  */
object CorpusG3Spec extends ZIOSpecDefault:

  private def fire(port: Int, step: VerifyStep): Task[HttpResponse[String]] =
    ZIO.attemptBlocking(CorpusReplay.fireBlocking(port, step))

  private def assertStep(step: VerifyStep, response: HttpResponse[String]): TestResult =
    assertTrue(response.statusCode() == step.expectStatus) &&
      assertTrue(step.expectBodyContains.forall(response.body().contains)) &&
      assertTrue(step.expectBodyEquals.forall(_ == response.body()))

  private def replayFixture(rift: Rift, fixture: Fixture): IO[RiftError, TestResult] =
    // Bracket the imposter so a `fire` defect (`.orDie`) can't leak it: the release deletes it even
    // when the replay unwinds on a defect, not only on the happy path.
    ZIO.acquireReleaseWith(rift.createFromJson(Corpus.imposterRaw(fixture)))(_.delete.orDie) {
      imp =>
        val steps = CorpusReplay.verifySteps(Corpus.imposterJson(fixture))
        val port = Port.value(imp.port)
        ZIO
          .foreach(steps)(step => fire(port, step).orDie.map(assertStep(step, _)))
          .map(_.reduceOption(_ && _).getOrElse(assertCompletes))
    }

  private def replayOrSkip(rift: Rift, fixture: Fixture): IO[RiftError, TestResult] =
    val ungated = fixture.requires -- CorpusReplay.supportedCapabilities
    if ungated.nonEmpty then
      ZIO
        .logInfo(
          s"G3: skipping ${fixture.name} — requires ${ungated.mkString(",")}, beyond the embedded " +
            "lane's supported capability set"
        )
        .as(assertCompletes)
    else replayFixture(rift, fixture)

  def spec = suite("G3 — corpus replay over the embedded engine (issue #6, guarded)")(
    test("replay every hasVerify fixture the embedded lane supports") {
      // Checked BEFORE any layer is forced — see `LedgerPatternSampleSpec`'s "WHY THIS TEST IS
      // GUARDED" for the full rationale this spec borrows verbatim.
      if !JRift.isEmbeddedAvailable() then
        ZIO.logWarning("G3 skipped: no embedded engine on this JVM") *> ZIO.succeed(assertCompletes)
      else
        ZIO.scoped {
          for
            env <- Rift.embedded.build
            rift = env.get[Rift]
            outcomes <- ZIO.foreach(Corpus.fixtures.filter(_.hasVerify))(replayOrSkip(rift, _))
          yield outcomes.reduceOption(_ && _).getOrElse(assertCompletes)
        }
    }
  )
