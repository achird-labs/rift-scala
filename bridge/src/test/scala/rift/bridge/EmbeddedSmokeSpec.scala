package rift.bridge

import java.net.URI
import java.nio.file.Files

import munit.FunSuite

import rift.dsl.*
import rift.model.Port

/** AC8 — a real end-to-end smoke over the embedded engine, proving the connector wiring against a
  * live engine. Guarded on `isEmbeddedAvailable`: runs wherever the embedded runtime is present —
  * including the JDK 22 CI job, for which build.sbt wires `rift-java-embedded` + natives and
  * `--enable-native-access` under `buildJavaSpec >= 22` (#99) — and skips (not fails) elsewhere,
  * e.g. the JDK 21 job. The exhaustive engine round-trip lives in the conformance corpus (#6/#13).
  */
class EmbeddedSmokeSpec extends FunSuite:

  /** Fails closed, on the rule issue #63 already established for the G3 lanes: a job that declares
    * `RIFT_G3_REQUIRE=embedded` and then finds no engine is a RED build, not a green skip. Without
    * this, the wiring regressing (natives unresolved, the JVM flag dropped, the matrix losing its
    * JDK 22 leg) would put these tests straight back to permanently skipped — which is how they sat
    * dead for their whole life before #99, with CI green the entire time.
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
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

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

  test("embedded: intercept — register rules, read trust material, export a truststore"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val ic = conn.intercept() // generated CA, dynamic port
      try
        assert(ic.proxyUri.toString.nonEmpty)
        ic.rule("api.example.com").when(get("/health")).serve(ok.json("""{"ok":true}"""))
        ic.rule("legacy.example.com").forward("real.example.com:443")
        assert(ic.rules.sizeIs >= 2)

        // All-hosts rule (#80): the engine stores it with no `host` key, and the terminal must hand
        // back `host = None` rather than the facade's raw null.
        val catchAll = ic.rule().when(get("/anywhere")).serve(ok.json("""{"any":true}"""))
        assertEquals(catchAll.host, None)
        assert(ic.rules.exists(_.host.isEmpty), ic.rules.toString)
        assert(ic.caPem.contains("BEGIN"))
        assert(ic.sslContext != null)

        val truststore = java.nio.file.Files.createTempFile("rift-truststore", ".p12")
        try
          ic.exportTruststore(TruststoreFormat.Pkcs12, "changeit", truststore)
          assert(java.nio.file.Files.size(truststore) > 0)
        finally java.nio.file.Files.deleteIfExists(truststore)

        // Chained `.when` is a conjunction (#82). The engine ANDs a rule's predicate array —
        // `intercept_rules.rs` routes `rule.predicates` through the same `stub_matches` as stubs,
        // whose loop is documented "All predicates must match (implicit AND)" — so concatenating
        // the clauses' predicates is the correct combinator. This asserts the readback carries
        // BOTH clauses; dropping either is what #82 fixed.
        val conjunction = ic
          .rule("and.example.com")
          .when(get("/admin"))
          .when(onRequest.where(header("X-Env").is("prod")))
          .serve(status(503))
        assertEquals(conjunction.host, Some("and.example.com"))
        val registered = ic.rules.filter(_.host.contains("and.example.com"))
        assertEquals(registered.size, 1, registered.toString)
        val wire = registered.head.raw.render
        assert(wire.contains("/admin"), wire)
        assert(wire.contains("X-Env"), wire)

        ic.clearRules()
        assertEquals(ic.rules, Vector.empty)
      finally ic.close()
    finally conn.close()

  test("embedded: startRecording — snapshot with no traffic, persist a file, close discards"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val imp = conn.create(imposter("rec").port(0).build)
      val rec = imp.startRecording(URI.create("https://origin.example.test"))
      try
        assertEquals(rec.snapshot(), Vector.empty) // no proxied traffic yet

        val file = Files.createTempFile("rift-recording", ".json")
        Files.delete(file)
        try
          rec.persist(file)
          assert(Files.exists(file)) // persist wrote a loadable imposter file
        finally Files.deleteIfExists(file)
      finally rec.close() // stop-and-discard
      imp.delete()
    finally conn.close()

  test("embedded: startRecording — stop returns the captured stubs and ends the session"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val imp = conn.create(imposter("rec-stop").port(0).build)
      val rec = imp.startRecording(URI.create("https://origin.example.test"))
      assertEquals(rec.stop(), Vector.empty) // terminal read — no proxied traffic → no stubs
      imp.delete()
    finally conn.close()
