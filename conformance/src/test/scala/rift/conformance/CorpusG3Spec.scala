package rift.conformance

import zio.*
import zio.test.*

import rift.zio.Rift

import io.github.achirdlabs.rift.Rift as JRift

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
      // Availability is probed BEFORE any layer is forced (the repo-standard guard). `RIFT_G3_REQUIRE`
      // decides what an *unavailable* engine means: skip for local dev, but a RED build on the CI job
      // that required the embedded lane (issue #63 — a silent green skip would mean it never ran).
      G3Require.decideEmbedded(JRift.isEmbeddedAvailable(), G3Require.required) match
        case G3Require.Decision.Run => CorpusG3Replay.replayAll("embedded").provide(Rift.embedded)
        case G3Require.Decision.Skip =>
          ZIO.logWarning("G3 skipped: no embedded engine on this JVM") *> ZIO.succeed(
            assertCompletes
          )
        // Do NOT weaken this arm to a skip/succeed without re-running the `RIFT_G3_REQUIRE=embedded`
        // red-proof — it is the only thing turning a required-but-absent lane into a red build (#63;
        // "the build goes red" cannot itself be a passing in-suite test — see G3RequireSpec).
        case G3Require.Decision.Fail(reason) => ZIO.die(new AssertionError(reason))
    }
  )
