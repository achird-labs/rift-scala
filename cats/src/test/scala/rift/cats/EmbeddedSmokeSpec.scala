package rift.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite

import rift.dsl.*
import rift.model.Port

/** A real end-to-end smoke over the embedded engine, proving the `Resource` + tagless wiring
  * against a live engine (mirrors the bridge's own `EmbeddedSmokeSpec`, DESIGN.md §5.6). Guarded on
  * `isEmbeddedAvailable`: skipped (not failed) wherever the native runtime is absent — including
  * CI, which does not put `rift-java-natives` / `--enable-native-access` on the test JVM. The
  * exhaustive engine round-trip lives in the cats conformance corpus (issue #13).
  */
class EmbeddedSmokeSpec extends CatsEffectSuite:

  test("embedded: create imposter, add a stub, verify no interactions, delete"):
    assume(Rift.isEmbeddedAvailable, "embedded runtime not on the classpath — skipping")

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
    assume(Rift.isEmbeddedAvailable, "embedded runtime not on the classpath — skipping")

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
