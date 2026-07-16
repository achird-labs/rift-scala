package rift.bridge

import munit.FunSuite

import rift.dsl.*
import rift.model.Port

import io.github.etacassiopeia.rift.Rift as JRift

/** AC8 — a real end-to-end smoke over the embedded engine, proving the connector wiring against a
  * live engine. Guarded on `isEmbeddedAvailable`: skipped (not failed) wherever the native runtime
  * is absent — including CI, which does not put `rift-java-natives` / `--enable-native-access` on
  * the test JVM. The exhaustive engine round-trip lives in the conformance corpus (#6/#13).
  */
class EmbeddedSmokeSpec extends FunSuite:

  test("embedded: create imposter, add a stub, verify no interactions, delete"):
    assume(JRift.isEmbeddedAvailable(), "embedded runtime not on the classpath — skipping")

    val conn = RiftConnector.embedded()
    try
      val definition = imposter("smoke").port(0).record.build
      val imp = conn.create(definition)
      assert(Port.value(imp.port) > 0)

      imp.addStub(get("/ping").reply(ok).build)
      assert(imp.stubs.nonEmpty)

      assertEquals(imp.recorded(), Vector.empty)
      imp.verifyNoInteractions()

      imp.delete()
    finally conn.close()
