package rift.conformance

import java.net.http.HttpResponse

import zio.*
import zio.test.*

import rift.RiftError
import rift.model.Port
import rift.zio.Rift

import CorpusReplay.VerifyStep

/** Shared ZIO-surface G3 replay driver, factored out of `CorpusG3Spec` (#6) so the embedded and
  * spawn transports (#56) replay the exact same `_verify.sequence` semantics rather than a
  * hand-copied second driver that could drift — the same anti-drift reasoning as `CorpusReplay`
  * itself, but for the ZIO effect wrapping that `CorpusReplay` deliberately leaves out. Each spec
  * supplies its own engine layer (`Rift.embedded` / `Rift.spawn`); everything past `env.get[Rift]`
  * is identical.
  */
object CorpusG3Replay:

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
          s"G3: skipping ${fixture.name} — requires ${ungated.mkString(",")}, beyond the live " +
            "engine's supported capability set"
        )
        .as(assertCompletes)
    else replayFixture(rift, fixture)

  /** Fixtures this run will genuinely replay (not capability-skip): `hasVerify` and every
    * `requires` capability supported. Computed from the corpus + `supportedCapabilities` alone, so
    * it is the floor `replayAll` asserts is non-empty — see there.
    */
  private val replayable: Vector[Fixture] =
    Corpus.fixtures.filter(f =>
      f.hasVerify && (f.requires -- CorpusReplay.supportedCapabilities).isEmpty
    )

  /** Create + replay every `hasVerify` fixture the transport can serve. Needs a `Rift` in the
    * environment — each spec `.provide`s its own engine layer (`Rift.embedded` / `Rift.spawn`),
    * whose scope releases imposters (and, for spawn, the engine process) when the effect completes.
    *
    * Called only when the guard has already confirmed a live engine, so it asserts a **floor**: at
    * least one fixture must actually be replayed. Without it, a corpus with no ungated `hasVerify`
    * fixtures (or a future `supportedCapabilities`/corpus drift) would let the gate pass green
    * having exercised the engine on nothing — the precise "green without running anything" failure
    * #56 exists to prevent.
    */
  def replayAll(transport: String): ZIO[Rift, RiftError, TestResult] =
    ZIO.serviceWithZIO[Rift] { rift =>
      ZIO
        .foreach(Corpus.fixtures.filter(_.hasVerify))(replayOrSkip(rift, _))
        .map(_.reduceOption(_ && _).getOrElse(assertCompletes))
        .map(assertTrue(replayable.nonEmpty) && _)
        .tap(_ =>
          ZIO.logInfo(
            s"G3 replay complete over the $transport transport: ${replayable.size} fixtures replayed"
          )
        )
    }
