package rift.conformance

import zio.*
import zio.test.*

import rift.zio.Rift

import io.github.etacassiopeia.rift.Rift as JRift

/** G3 — replay over the **embedded** engine (engine-required, GUARDED).
  *
  * Guarded exactly like `bridge.EmbeddedSmokeSpec` / `zio-testkit`'s `LedgerPatternSampleSpec`:
  * `JRift.isEmbeddedAvailable()` is checked BEFORE any engine layer is built, so a bare JVM (no
  * `rift-java-embedded` / `--enable-native-access`, e.g. the JDK 21 CI job or a local JDK < 22)
  * skips (never fails) and this spec's only job there is to COMPILE. On a JDK 22+ job the embedded
  * jars are on the classpath (wired in `build.sbt`), so it replays every `hasVerify` fixture the
  * embedded lane can serve. The out-of-process transport is covered by [[CorpusG3SpawnSpec]].
  *
  * The `_verify` parsing + HTTP fire mechanics live in `CorpusReplay`; the ZIO effect wrapping and
  * per-fixture create/replay/skip live in `CorpusG3Replay`, shared with the spawn spec so the two
  * transports can never drift.
  */
object CorpusG3Spec extends ZIOSpecDefault:

  def spec = suite("G3 — corpus replay over the embedded engine (issue #6, guarded)")(
    test("replay every hasVerify fixture the embedded lane supports") {
      // Checked BEFORE any layer is forced — see `LedgerPatternSampleSpec`'s "WHY THIS TEST IS
      // GUARDED" for the full rationale this spec borrows verbatim.
      if !JRift.isEmbeddedAvailable() then
        ZIO.logWarning("G3 skipped: no embedded engine on this JVM") *> ZIO.succeed(assertCompletes)
      else CorpusG3Replay.replayAll("embedded").provide(Rift.embedded)
    }
  )
