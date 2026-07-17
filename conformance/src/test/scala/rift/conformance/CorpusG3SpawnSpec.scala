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
  * Guarded on `RIFT_G3_SPAWN=1` rather than an `isEmbeddedAvailable`-style probe: launching the
  * engine needs its binary downloadable/executable, which is a property of the environment, not of
  * the classpath. The JDK 21 CI job sets it (that job owns the remote lane); a bare local run
  * leaves it unset and this spec skips (never fails), its only job there being to COMPILE.
  */
object CorpusG3SpawnSpec extends ZIOSpecDefault:

  private val enabled: Boolean = sys.env.get("RIFT_G3_SPAWN").contains("1")

  def spec = suite("G3 — corpus replay over the spawn engine (issue #56, guarded)")(
    test("replay every hasVerify fixture over a spawned engine process") {
      if !enabled then
        ZIO.logWarning(
          "G3 spawn skipped: RIFT_G3_SPAWN != 1 (set on the CI job that launches the engine binary)"
        ) *> ZIO.succeed(assertCompletes)
      else CorpusG3Replay.replayAll("spawn").provide(Rift.spawn())
    }
  )
