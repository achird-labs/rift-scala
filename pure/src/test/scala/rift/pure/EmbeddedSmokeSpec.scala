package rift.pure

import scala.util.Using

import munit.FunSuite

import rift.dsl.*
import rift.model.Port

/** A real end-to-end smoke over the embedded engine, proving the `Either`-shaped wiring against a
  * live engine (mirrors the bridge's own `EmbeddedSmokeSpec`, DESIGN.md §5.11). Guarded on
  * `isEmbeddedAvailable`: runs wherever the embedded runtime is present — including the JDK 22 CI
  * job, for which build.sbt wires `rift-java-embedded` + natives and `--enable-native-access` under
  * `buildJavaSpec >= 22` (#99) — and skips (not fails) elsewhere, e.g. the JDK 21 job. The
  * exhaustive engine round-trip lives in the conformance corpus (#6/#13), not here.
  */
class EmbeddedSmokeSpec extends FunSuite:

  /** Fails closed, on the rule issue #63 already established for the G3 lanes: a job that declares
    * `RIFT_G3_REQUIRE=embedded` and then finds no engine is a RED build, not a green skip. Without
    * this, the wiring regressing would put these tests straight back to permanently skipped — which
    * is how they sat dead for their whole life before #99, with CI green the entire time.
    */
  private def requireEmbedded(available: Boolean): Unit =
    if !available then
      if sys.env.get("RIFT_G3_REQUIRE").map(_.trim).exists(_.equalsIgnoreCase("embedded")) then
        fail(
          "RIFT_G3_REQUIRE=embedded, but no embedded engine is on this JVM — the " +
            "rift-java-embedded jars / --enable-native-access are missing (#63 backstop, #99)."
        )
      else assume(false, "embedded runtime not on the classpath — skipping")

  test("embedded: create imposter, add a stub, verify no interactions"):
    requireEmbedded(Rift.isEmbeddedAvailable)

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
    requireEmbedded(Rift.isEmbeddedAvailable)

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
