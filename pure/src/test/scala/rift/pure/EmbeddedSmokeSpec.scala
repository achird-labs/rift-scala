package rift.pure

import scala.util.Using

import munit.FunSuite

import rift.RiftError
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
        // Re-read from the ENGINE, not the local `serve(...)` return value: #80 was a bug on the
        // read path (engine → facade → us), so the assertion above would stay green through a
        // regression of the half that actually broke.
        assertEquals(ic.rules.map(_.exists(_.host.isEmpty)), Right(true), ic.rules.toString)
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

  // #121: every other assertion in this file is `.isRight` or a happy-path `Right(...)`, which
  // leaves the module's whole distinguishing feature — the `Either`-shaped wiring — unexercised on
  // the branch that matters. A genuine engine failure must arrive as a typed `Left`, not as a
  // thrown defect and not as a spurious `Right`.
  test("embedded: a real engine failure surfaces as Left(RiftError), not a throw"):
    requireEmbedded(Rift.isEmbeddedAvailable)

    Using.resource(Rift.embeddedUnsafe()) { rift =>
      val imp = rift
        .create(imposter("smoke-left").port(0).build)
        .fold(e => fail(s"setup failed: $e"), identity)

      assertEquals(imp.delete(), Right(()))
      // Deleting the same imposter twice is an engine-side error on the second call.
      imp.delete() match
        case Left(RiftError.EngineError(_, msg)) => assert(msg.contains("not found"), msg)
        case other => fail(s"expected Left(EngineError(_, not found)), got $other")
    }

  // The one-intercept-per-engine guard is the facade's, and it raises `IllegalStateException` —
  // not a `RiftException` — so `catchRiftError` does not map it and it escapes as a defect rather
  // than a `Left`. Pinning that here keeps `pure`'s "engine failures are values" contract honest
  // about its own boundary: a wiring mistake is not an engine failure. (The bridge spec pins the
  // same behaviour, and #121 corrected the scaladoc that claimed a `RiftError`.)
  test("embedded: a second intercept() throws rather than returning a Left"):
    requireEmbedded(Rift.isEmbeddedAvailable)

    Using.resource(Rift.embeddedUnsafe()) { rift =>
      Using.resource(rift.interceptUnsafe()) { _ =>
        val thrown = intercept[IllegalStateException](rift.intercept())
        assert(thrown.getMessage.contains("already started"), thrown.getMessage)
      }
    }
