package rift.conformance

import zio.*
import zio.test.*

import rift.zio.Rift

/** G3 — replay over the **spawn** (out-of-process) engine (engine-required, GUARDED).
  *
  * The remote-transport half of the G3 gate (DESIGN §5.13 / D8): `Rift.spawn` launches the engine
  * binary — resolved by the version pinned through `rift-java` (`SpawnConfig` defaults to
  * `RiftVersion.engineVersion()`, never a literal) — as a child process and replays the same
  * `_verify.sequence` fixtures as [[CorpusG3Spec]] over real out-of-process HTTP, via the shared
  * `CorpusG3Replay` driver.
  *
  * Guarded on `RIFT_G3_REQUIRE=spawn` (issue #63) rather than an `isEmbeddedAvailable`-style probe:
  * launching the engine needs its binary downloadable/executable, which is a property of the
  * environment, not of the classpath, and a failed launch already fails the replay loudly. The JDK
  * 21 CI job sets it (that job owns the remote lane); a bare local run leaves it unset and this
  * spec skips (never fails), its only job there being to COMPILE.
  */
object CorpusG3SpawnSpec extends ZIOSpecDefault:

  def spec = suite("G3 — corpus replay over the spawn engine (issue #56, guarded)")(
    test("replay every hasVerify fixture over a spawned engine process") {
      G3Require.decideSpawn(G3Require.required) match
        case G3Require.Decision.Run => CorpusG3Replay.replayAll("spawn").provide(Rift.spawn())
        case G3Require.Decision.Skip =>
          ZIO.logWarning(
            "G3 spawn skipped: RIFT_G3_REQUIRE != spawn (set on the CI job that launches the engine binary)"
          ) *> ZIO.succeed(assertCompletes)
        // Fails closed on an unrecognized RIFT_G3_REQUIRE value — a typo must not silently leave the
        // spawn lane (this job's whole purpose) unexercised. Don't weaken without re-proving.
        case G3Require.Decision.Fail(reason) => ZIO.die(new AssertionError(reason))
    }
  )
