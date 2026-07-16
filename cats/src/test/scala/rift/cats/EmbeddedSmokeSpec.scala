package rift.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite

import rift.dsl.*
import rift.model.Port

import io.github.etacassiopeia.rift.Rift as JRift

/** A real end-to-end smoke over the embedded engine, proving the `Resource` + tagless wiring
  * against a live engine (mirrors the bridge's own `EmbeddedSmokeSpec`, DESIGN.md §5.6). Guarded on
  * `isEmbeddedAvailable`: skipped (not failed) wherever the native runtime is absent — including
  * CI, which does not put `rift-java-natives` / `--enable-native-access` on the test JVM. The
  * exhaustive engine round-trip lives in the cats conformance corpus (issue #13).
  */
class EmbeddedSmokeSpec extends CatsEffectSuite:

  test("embedded: create imposter, add a stub, verify no interactions, delete"):
    assume(JRift.isEmbeddedAvailable(), "embedded runtime not on the classpath — skipping")

    Rift.embedded[IO].use { rift =>
      for
        imp <- rift.create(imposter("smoke").port(0).record.build)
        _ = assert(Port.value(imp.port) > 0)
        _ <- imp.addStub(get("/ping").reply(ok))
        stubs <- imp.stubs
        _ = assert(stubs.nonEmpty)
        recorded <- imp.recorded
        _ = assertEquals(recorded, Vector.empty)
        _ <- imp.verifyNoInteractions
        _ <- imp.delete
      yield ()
    }
