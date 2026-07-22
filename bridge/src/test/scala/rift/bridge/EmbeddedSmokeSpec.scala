package rift.bridge

import java.net.URI
import java.nio.file.Files

import munit.FunSuite

import rift.dsl.*
import rift.model.Port

import io.github.achirdlabs.rift.Rift as JRift

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

  test("embedded: intercept — register rules, read trust material, export a truststore"):
    assume(JRift.isEmbeddedAvailable(), "embedded runtime not on the classpath — skipping")

    val conn = RiftConnector.embedded()
    try
      val ic = conn.intercept() // generated CA, dynamic port
      try
        assert(ic.proxyUri.toString.nonEmpty)
        ic.rule("api.example.com").when(get("/health")).serve(ok.json("""{"ok":true}"""))
        ic.rule("legacy.example.com").forward("https://real.example.com")
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

        ic.clearRules()
        assertEquals(ic.rules, Vector.empty)
      finally ic.close()
    finally conn.close()

  test("embedded: startRecording — snapshot with no traffic, persist a file, close discards"):
    assume(JRift.isEmbeddedAvailable(), "embedded runtime not on the classpath — skipping")

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
    assume(JRift.isEmbeddedAvailable(), "embedded runtime not on the classpath — skipping")

    val conn = RiftConnector.embedded()
    try
      val imp = conn.create(imposter("rec-stop").port(0).build)
      val rec = imp.startRecording(URI.create("https://origin.example.test"))
      assertEquals(rec.stop(), Vector.empty) // terminal read — no proxied traffic → no stubs
      imp.delete()
    finally conn.close()
