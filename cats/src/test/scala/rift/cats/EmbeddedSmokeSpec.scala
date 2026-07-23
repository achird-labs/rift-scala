package rift.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite

import rift.dsl.*
import rift.model.Port

/** A real end-to-end smoke over the embedded engine, proving the `Resource` + tagless wiring
  * against a live engine (mirrors the bridge's own `EmbeddedSmokeSpec`, DESIGN.md §5.6). Guarded on
  * `isEmbeddedAvailable`: runs wherever the embedded runtime is present — including the JDK 22 CI
  * job, for which build.sbt wires `rift-java-embedded` + natives and `--enable-native-access` under
  * `buildJavaSpec >= 22` (#99) — and skips (not fails) elsewhere, e.g. the JDK 21 job. The
  * exhaustive engine round-trip lives in the cats conformance corpus (issue #13).
  */
class EmbeddedSmokeSpec extends CatsEffectSuite:

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

  test("embedded: create imposter, add a stub, verify no interactions, delete"):
    requireEmbedded(Rift.isEmbeddedAvailable)

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

  test("embedded: intercept rule round-trip and imposter recording session"):
    requireEmbedded(Rift.isEmbeddedAvailable)

    Rift.embedded[IO].use { rift =>
      for
        _ <- rift.intercept().use { ic =>
          for
            _ <- ic.rule("api.example.com").when(get("/health")).serve(ok.json("""{"ok":true}"""))
            // All-hosts rule (#80): forces the `host.fold(connector.rule())(...)` seed down its
            // None branch — the one path the engine-free builder tests cannot reach.
            catchAll <- ic.rule().when(get("/anywhere")).serve(ok.json("""{"any":true}"""))
            _ = assertEquals(catchAll.host, None)
            rules <- ic.rules
            _ = assert(rules.nonEmpty)
            pem <- ic.caPem
            _ = assert(pem.contains("BEGIN"))
          yield ()
        }
        imp <- rift.create(imposter("smoke-recording").port(0).build)
        _ <- imp.startRecording(java.net.URI.create("https://origin.example.test")).use { rec =>
          for
            snapshot <- rec.snapshot
            _ = assertEquals(snapshot, Vector.empty)
          yield ()
        }
        _ <- imp.delete
      yield ()
    }
