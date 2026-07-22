package rift.pure

import scala.util.Using

import munit.FunSuite

import rift.dsl.*
import rift.model.Port

/** A real end-to-end smoke over the embedded engine, proving the `Either`-shaped wiring against a
  * live engine (mirrors the bridge's own `EmbeddedSmokeSpec`, DESIGN.md §5.11). Guarded on
  * `isEmbeddedAvailable`: skipped (not failed) wherever the native runtime is absent — including
  * CI, which does not put `rift-java-natives` / `--enable-native-access` on the test JVM. The
  * exhaustive engine round-trip lives in the conformance corpus (#6/#13), not here.
  */
class EmbeddedSmokeSpec extends FunSuite:

  test("embedded: create imposter, add a stub, verify no interactions"):
    assume(Rift.isEmbeddedAvailable, "embedded runtime not on the classpath — skipping")

    Using.resource(Rift.embeddedUnsafe()) { rift =>
      val created = rift.create(imposter("smoke").port(0).record.build)
      assert(created.isRight, s"expected Right, got $created")
      val imp = created.fold(throw _, identity)
      assert(Port.value(imp.port) > 0)

      val stubbed = imp.addStub(get("/ping").reply(ok))
      assert(stubbed.isRight, s"expected Right, got $stubbed")

      assertEquals(imp.recorded(), Right(Vector.empty))
      assertEquals(imp.verifyNoInteractions(), Right(()))
    }

  test("embedded: intercept rule round-trip and imposter recording session"):
    assume(Rift.isEmbeddedAvailable, "embedded runtime not on the classpath — skipping")

    Using.resource(Rift.embeddedUnsafe()) { rift =>
      Using.resource(rift.interceptUnsafe()) { ic =>
        val rule = ic.rule("api.example.com").when(get("/health")).serve(ok.json("""{"ok":true}"""))
        assert(rule.isRight, s"expected Right, got $rule")
        assertEquals(ic.rules.map(_.nonEmpty), Right(true))

        // All-hosts rule (#80) — the no-arg form, registered end-to-end against the engine.
        val catchAll = ic.rule().when(get("/anywhere")).serve(ok.json("""{"any":true}"""))
        assertEquals(catchAll.map(_.host), Right(None))
        assertEquals(ic.caPem.map(_.contains("BEGIN")), Right(true))
      }

      val created = rift.create(imposter("smoke-recording").port(0).build)
      assert(created.isRight, s"expected Right, got $created")
      val imp = created.fold(throw _, identity)

      val recording = imp.startRecording(java.net.URI.create("https://origin.example.test"))
      assert(recording.isRight, s"expected Right, got $recording")
      Using.resource(recording.fold(throw _, identity)) { rec =>
        assertEquals(rec.snapshot(), Right(Vector.empty))
      }

      assertEquals(imp.delete(), Right(()))
    }
