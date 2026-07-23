package rift.bridge

import java.net.URI
import java.nio.file.Files

import munit.FunSuite

import rift.RiftError
import rift.dsl.*
import rift.model.{Port, Times, VerifyDetail}

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

  test(
    "embedded: verifyResult — satisfied with a matched count, and an unsatisfied result " +
      "returned as a value rather than thrown"
  ):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val imp = conn.create(imposter("verify-result").port(0).record.build)
      imp.addStub(get("/ping").reply(ok).build)

      val client = java.net.http.HttpClient.newHttpClient()
      val request = java.net.http.HttpRequest.newBuilder(imp.uri.resolve("/ping")).GET().build()
      client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())

      val satisfied = imp.verifyResult(get("/ping"))
      assertEquals(satisfied.satisfied, true)
      assertEquals(satisfied.matched, 1)
      // No VerifyDetail asked for ⇒ the engine attaches neither. Pinning the empty side is what
      // makes the populated assertions below evidence that the flags travelled.
      assertEquals(satisfied.requests, Vector.empty)
      assertEquals(satisfied.closest, None)

      // Times.Exactly(2) can never be satisfied by the single request just fired — verifyResult
      // must hand that back as a value (`satisfied = false`), the exact capability the throwing
      // `verify` cannot provide.
      val unsatisfied =
        imp.verifyResult(
          get("/ping"),
          Times.Exactly(2),
          VerifyDetail.Requests,
          VerifyDetail.Closest
        )
      assertEquals(unsatisfied.satisfied, false)
      assertEquals(unsatisfied.matched, 1)
      // Load-bearing: without these, dropping `FacadeEncode.verifyDetails(details)*` from the
      // connector call would leave every test green.
      assert(unsatisfied.requests.nonEmpty, "VerifyDetail.Requests did not reach the engine")
      assert(
        unsatisfied.requests.forall(_.path == "/ping"),
        unsatisfied.requests.map(_.path).toString
      )

      imp.delete()
    finally conn.close()

  test("embedded: intercept — register rules, read trust material, export a truststore"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val ic = conn.intercept() // generated CA, dynamic port
      try
        // The parts a client actually needs to connect. `toString.nonEmpty` passed for any URI at
        // all, including one with no host or an unassigned port.
        assert(ic.proxyUri.getHost != null, ic.proxyUri.toString)
        assert(ic.proxyUri.getPort > 0, ic.proxyUri.toString)
        ic.rule("api.example.com").when(get("/health")).serve(ok.json("""{"ok":true}"""))
        ic.rule("legacy.example.com").forward("real.example.com:443")
        assert(ic.rules.sizeIs >= 2)

        // All-hosts rule (#80): the engine stores it with no `host` key, and the terminal must hand
        // back `host = None` rather than the facade's raw null.
        val catchAll = ic.rule().when(get("/anywhere")).serve(ok.json("""{"any":true}"""))
        assertEquals(catchAll.host, None)
        assert(ic.rules.exists(_.host.isEmpty), ic.rules.toString)
        assert(ic.caPem.contains("BEGIN"))
        // Initialised, not merely non-null: an uninitialised SSLContext throws here, so this is
        // what distinguishes a usable context from a placeholder.
        assert(ic.sslContext.getSocketFactory != null)

        val truststore = java.nio.file.Files.createTempFile("rift-truststore", ".p12")
        try
          ic.exportTruststore(TruststoreFormat.Pkcs12, "changeit", truststore)
          // A size check passed for any garbage bytes. Loading it proves the export is a real
          // PKCS12 keystore, readable with the password given, holding the intercept CA. The
          // stream is closed before the `finally` deletes the file — an open handle would make the
          // delete throw on Windows and mask whatever this test actually found.
          val ks = java.security.KeyStore.getInstance("PKCS12")
          scala.util.Using.resource(java.nio.file.Files.newInputStream(truststore))(
            ks.load(_, "changeit".toCharArray)
          )
          assertEquals(ks.size(), 1, "the plain truststore should hold exactly the intercept CA")
          val alias = ks.aliases().nextElement()
          assert(ks.isCertificateEntry(alias), s"$alias is not a trusted-certificate entry")
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

  // ── issue #95: trust-material parity, against a live engine ─────────────────────────────────
  // Loads a PEM cert + PKCS#8 private key into a fresh PKCS12 keystore, so `CaMaterial.FromKeyStore`
  // can be exercised without committing any key material (this repo's rule) — the pair comes from
  // the engine's own generated CA at runtime.
  private def keyStoreOf(certPem: String, keyPem: String): java.security.KeyStore =
    val cert = java.security.cert.CertificateFactory
      .getInstance("X.509")
      .generateCertificate(new java.io.ByteArrayInputStream(certPem.getBytes("UTF-8")))
    val der = java.util.Base64.getMimeDecoder.decode(
      keyPem
        .replaceAll("-----BEGIN (.*)-----", "")
        .replaceAll("-----END (.*)-----", "")
        .replaceAll("\\s", "")
    )
    // PKCS#8 does not name its algorithm in the PEM header, and the engine's generated CA is not
    // necessarily RSA — try the candidates rather than hardcoding one.
    val spec = new java.security.spec.PKCS8EncodedKeySpec(der)
    val key = List("EC", "RSA", "Ed25519", "DSA")
      .flatMap(alg =>
        scala.util.Try(java.security.KeyFactory.getInstance(alg).generatePrivate(spec)).toOption
      )
      .headOption
      .getOrElse(fail("could not load the engine's CA private key under any known algorithm"))
    val ks = java.security.KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry("ca", key, "changeit".toCharArray, Array(cert))
    ks

  test("embedded: caMaterial returns the generated CA and every CA source form round-trips"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    val (certPem, keyPem) =
      try
        val ic = conn.intercept() // generated CA ⇒ the engine returns the key
        try
          val material = ic.caMaterial
          assert(material.isDefined, "a generated CA must hand back its key material")
          val m = material.get
          assertEquals(m.certPem, ic.caPem)
          assert(m.keyPem.contains("BEGIN"), m.keyPem.take(40))
          (m.certPem, m.keyPem)
        finally ic.close()
      finally conn.close()

    // Each source form must reproduce the SAME CA — that is what "persist a CA across runs" means.
    def caPemFrom(ca: CaMaterial): String =
      val c = RiftConnector.embedded()
      try
        val ic = c.intercept(InterceptConfig(ca = Some(ca)))
        try ic.caPem
        finally ic.close()
      finally c.close()

    assertEquals(caPemFrom(CaMaterial.Pem(certPem, keyPem)), certPem)
    assertEquals(
      caPemFrom(
        CaMaterial.fromPemBytes(
          IArray.unsafeFromArray(certPem.getBytes("UTF-8")),
          IArray.unsafeFromArray(keyPem.getBytes("UTF-8"))
        )
      ),
      certPem
    )

    val certFile = java.nio.file.Files.createTempFile("rift-ca", ".pem")
    val keyFile = java.nio.file.Files.createTempFile("rift-ca", ".key")
    try
      java.nio.file.Files.writeString(certFile, certPem)
      java.nio.file.Files.writeString(keyFile, keyPem)
      // The embedded engine runs in THIS process, so its filesystem is ours — the one transport
      // where a PemFiles path is guaranteed resolvable from the test.
      assertEquals(caPemFrom(CaMaterial.PemFiles(certFile, keyFile)), certPem)
    finally
      java.nio.file.Files.deleteIfExists(certFile)
      java.nio.file.Files.deleteIfExists(keyFile)

    assertEquals(
      caPemFrom(CaMaterial.fromKeyStore(keyStoreOf(certPem, keyPem), "changeit".toCharArray)),
      certPem
    )

  test("embedded: the system-CA trust variants carry more anchors than the intercept-only ones"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val ic = conn.intercept()
      try
        assert(ic.sslContextWithSystemCAs != null)

        // Entry count is the observable difference: the plain export holds the intercept CA alone,
        // the system variant adds the platform anchors. Asserting "> 1" alone would pass on a
        // truststore that simply duplicated the CA.
        def entries(writeTo: (TruststoreFormat, String, java.nio.file.Path) => Unit): Int =
          val p = java.nio.file.Files.createTempFile("rift-ts", ".p12")
          try
            writeTo(TruststoreFormat.Pkcs12, "changeit", p)
            val ks = java.security.KeyStore.getInstance("PKCS12")
            ks.load(java.nio.file.Files.newInputStream(p), "changeit".toCharArray)
            ks.size()
          finally java.nio.file.Files.deleteIfExists(p)

        val plain = entries(ic.exportTruststore)
        val withSystem = entries(ic.exportTruststoreWithSystemCAs)
        assertEquals(plain, 1, "the plain truststore should hold the intercept CA and nothing else")
        assert(withSystem > plain, s"system-CA export added no anchors: $withSystem vs $plain")
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

  // ── issue #121: the one-intercept-per-engine contract, as the engine actually enforces it ────
  // `RiftConnector.intercept`'s scaladoc claimed a second call was "an engine-side error, surfaced
  // as a `RiftError`, not hidden". Against the live engine it is neither engine-side nor a
  // `RiftError`: the facade's own guard raises `IllegalStateException`, which is not a
  // `RiftException` subtype, so `FacadeBoundary` rethrows it as a defect. Nothing asserted this, so
  // the doc drifted from the behaviour unnoticed. Both docs are corrected; this pins the truth.
  test("embedded: a second intercept() is refused by the facade, as a defect"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val ic = conn.intercept()
      val whileOpen = intercept[IllegalStateException](conn.intercept())
      assert(whileOpen.getMessage.contains("already started"), whileOpen.getMessage)

      // And closing does not make the engine re-interceptable, so "at most one per engine" means
      // once per engine, not one at a time. (A *failed* start is different — the facade resets its
      // guard on any RuntimeException from the start path — so this is one successful start.)
      ic.close()
      val afterClose = intercept[IllegalStateException](conn.intercept())
      assert(afterClose.getMessage.contains("already started"), afterClose.getMessage)
    finally conn.close()

  // The observable that lets the effect surfaces prove their finalizers do real work. Note what it
  // is NOT: the facade's `Intercept.close()` is exactly `clearRules()`, so the listener is not torn
  // down — it stops only with the owning engine — and the handle stays live afterwards (`caPem`
  // still answers, a further `rule(...)` still registers). Cleared rules are the strongest signal
  // release actually has, which is why the cats spec asserts on this and not on object death.
  test("embedded: closing an intercept releases the rules it registered"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val ic = conn.intercept()
      ic.rule("api.example.com").when(get("/health")).serve(ok.json("""{"ok":true}"""))
      assert(ic.rules.nonEmpty, "precondition: the rule should be registered before close")
      ic.close()
      assertEquals(ic.rules, Vector.empty, "close() left the engine-side rules in place")
    finally conn.close()

  // ── issue #87/#127: the admin SSE event stream, live ─────────────────────────────────────────
  // This used to pin the *gap*: under rift-java 0.2.1 `RiftTransport.events()`'s interface default
  // threw and only `RemoteTransport` overrode it, so the embedded transport — the one this suite
  // runs on — had no admin stream at all. The assertion was written to go red the day upstream
  // implemented it, which is exactly what the 0.2.2 bump did (rift-java#177). This is the walk that
  // red was asking for.
  //
  // The poll runs on its own daemon thread with a bounded wait: `poll()` blocks, so an engine that
  // stopped delivering would otherwise hang the suite rather than fail it.
  test("embedded: the admin event stream delivers lifecycle events and ends on close"):
    requireEmbedded(RiftConnector.isEmbeddedAvailable)

    val conn = RiftConnector.embedded()
    try
      val events = conn.events(EventStreamConfig(types = Set(EventType.Lifecycle)))
      val seen = new java.util.concurrent.LinkedBlockingQueue[RiftEvent]()
      val ended = new java.util.concurrent.CountDownLatch(1)
      val pump: Runnable = () =>
        try
          var open = true
          while open do
            events.poll() match
              case Some(event) => seen.offer(event); ()
              case None => open = false
        finally ended.countDown()
      val poller = new Thread(pump, "embedded-smoke-event-poll")
      poller.setDaemon(true)
      poller.start()

      def nextEvent(what: String): RiftEvent =
        Option(seen.poll(20, java.util.concurrent.TimeUnit.SECONDS))
          .getOrElse(fail(s"no event arrived within 20s while waiting for $what"))

      // The facade opens every stream with a Hello handshake carrying the engine's own version.
      nextEvent("the Hello handshake") match
        case hello: RiftEvent.Hello =>
          assert(hello.engineVersion.nonEmpty, s"Hello carried no engine version: $hello")
        case other => fail(s"expected Hello first, got $other")

      val imposter = conn.create(rift.dsl.imposter("smoke-events").port(0).build)
      nextEvent("the Created lifecycle event") match
        case changed: RiftEvent.ImposterChanged =>
          assertEquals(changed.action, ImposterAction.Created)
          assertEquals(changed.port, Some(imposter.port))
        case other => fail(s"expected an ImposterChanged(Created), got $other")

      imposter.delete()

      // close() must end the stream rather than leave the blocked poll hanging — the contract
      // `EventStreamConnector`'s scaladoc states, and the one a `Resource`/`Scope` finalizer relies
      // on. Without it a leaked consumer thread would outlive every test in this suite.
      events.close()
      assert(
        ended.await(20, java.util.concurrent.TimeUnit.SECONDS),
        "close() did not end the stream — poll() is still blocked"
      )
    finally conn.close()
