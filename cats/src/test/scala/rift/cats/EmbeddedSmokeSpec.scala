package rift.cats

import _root_.cats.effect.{IO, Ref}
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
            // Re-read from the ENGINE, not the local `serve(...)` return value: #80 was a bug on
            // the read path (engine → facade → us), so asserting only on `catchAll.host` above
            // would stay green through a regression of the half that actually broke.
            _ = assert(rules.exists(_.host.isEmpty), rules.toString)
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

  // #121: `Resource` guarantees the finalizer RUNS — that is cats-effect's promise, not rift's.
  // What this module exists to prove is that rift's finalizer does real engine-side work, so the
  // check reads engine state after release: the rules the handle registered are gone.
  //
  // Deliberately NOT "the listener is torn down" — it isn't. The facade's `Intercept.close()` is
  // exactly `clearRules()`; the listener stops only with the owning engine. Cleared rules are the
  // strongest observable release actually has.
  //
  // Re-acquiring would be the nicer probe, but the facade refuses a second `intercept` once one
  // has succeeded (pinned in the bridge spec), which is also why each of these tests takes its own
  // `Rift.embedded` — one engine, one intercept. The handle can simply be yielded out of `use`
  // here because the body succeeds; the failing-body test below needs a `Ref` to capture it.
  test("embedded: releasing the intercept Resource clears the rules it registered"):
    requireEmbedded(Rift.isEmbeddedAvailable)

    Rift.embedded[IO].use { rift =>
      for
        released <- rift.intercept().use { ic =>
          for
            _ <- ic.rule("api.example.com").when(get("/health")).serve(ok)
            live <- ic.rules
            _ = assert(live.nonEmpty, "precondition: the rule should exist inside `use`")
          yield ic
        }
        afterRelease <- released.rules
        _ = assertEquals(afterRelease, Vector.empty, "release left the engine-side rules in place")
      yield ()
    }

  test("embedded: the intercept Resource releases even when the body fails, and the failure shows"):
    requireEmbedded(Rift.isEmbeddedAvailable)

    val boom = new RuntimeException("boom")
    Rift.embedded[IO].use { rift =>
      for
        // Captured before the failure so the teardown is still checkable afterwards — otherwise
        // this could only assert that the error propagated, not that release did anything.
        captured <- Ref[IO].of(Option.empty[InterceptHandle[IO]])
        outcome <- rift
          .intercept()
          .use { ic =>
            captured.set(Some(ic)) *>
              ic.rule("api.example.com").when(get("/health")).serve(ok) *>
              IO.raiseError[Unit](boom)
          }
          .attempt
        _ = assertEquals(outcome, Left(boom), "the body's failure was swallowed or replaced")
        handle <- captured.get
        ic = handle.getOrElse(fail("the resource body never ran"))
        afterRelease <- ic.rules
        _ = assertEquals(
          afterRelease,
          Vector.empty,
          "a failed body skipped the engine-side release"
        )
      yield ()
    }
