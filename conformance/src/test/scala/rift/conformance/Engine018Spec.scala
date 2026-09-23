package rift.conformance

import java.io.{BufferedReader, InputStreamReader}
import java.net.{Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.{
  HttpsURLConnection,
  KeyManagerFactory,
  SSLContext,
  TrustManager,
  X509TrustManager
}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

import rift.RiftError
import rift.dsl.*
import rift.model.{ClientAuth, Method, Port, Protocol, ScriptEngine, TcpFaultKind}
import rift.zio.{ImposterHandle, Rift}

/** Live checks that engine 0.18.0 **does** what the DSL writes for the features adopted in
  * #156–#178 (issue #188). Every other test of those features is JSON-level — the DSL writes the
  * expected document and the model round-trips it — which a wrong key name, or a shape the engine
  * parses and ignores, passes silently. Each case here asserts an effect only a live engine can
  * produce, and is authored with `rift.dsl` so it is this SDK's output under test, not raw JSON
  * (the two raw-JSON stubs are the #159 and #157 shapes, which only reach the engine that way).
  *
  * Runs on both transports under the same `RIFT_G3_REQUIRE` guard as the corpus replay:
  *   - embedded (JDK 22 lane): no script injection (the embedded options have no switch for it),
  *     but the intercept runs;
  *   - spawn (JDK 21 lane): the engine is started with `--allowInjection` (`SpawnConfig` default),
  *     so the script case runs there.
  *
  * A case a lane cannot host is not registered on it, and the lane's suite name says which — never
  * a silent green.
  */
object Engine018Spec extends ZIOSpecDefault:

  def spec = suite("engine 0.18.0 live checks (issue #188)")(
    lane(
      "embedded",
      G3Require.decideEmbedded(rift.bridge.RiftConnector.isEmbeddedAvailable, G3Require.required),
      Rift.embedded,
      Capabilities(injection = false, intercept = true)
    ),
    lane(
      "spawn",
      G3Require.decideSpawn(G3Require.required),
      Rift.spawn(),
      Capabilities(injection = true, intercept = false)
    )
  ) @@ TestAspect.sequential

  private final case class Capabilities(injection: Boolean, intercept: Boolean)

  private def lane(
      name: String,
      decision: G3Require.Decision,
      layer: ZLayer[Any, RiftError, Rift],
      caps: Capabilities
  ) =
    val label =
      s"$name lane (script cases ${if caps.injection then "on" else "off: no injection"}, " +
        s"intercept ${if caps.intercept then "on" else "off"})"
    decision match
      case G3Require.Decision.Run =>
        (suite(label)(
          (commonCases ++ Option.when(caps.injection)(scriptCase) ++
            Option.when(caps.intercept)(interceptCase))*
        ) @@ TestAspect.sequential).provideSomeLayerShared[Scope](layer.orDie)
      case G3Require.Decision.Skip =>
        suite(label)(
          test("skipped: this lane is not required on this job") {
            ZIO
              .logWarning(s"engine 0.18.0 live checks skipped on the $name lane")
              .as(assertCompletes)
          }
        )
      // Mirrors the corpus replay's backstop (#63): a lane the job REQUIRES but cannot run is red.
      case G3Require.Decision.Fail(reason) =>
        suite(label)(test("required lane unavailable")(ZIO.die(new AssertionError(reason))))

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────

  private val client = HttpClient.newHttpClient()

  private def created(b: ImposterBuilder): ZIO[Rift & Scope, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](r => ZIO.acquireRelease(r.create(b))(_.delete.orDie))

  private def portOf(h: ImposterHandle): Int = Port.value(h.port)

  private def send(port: Int, path: String, method: String = "GET"): Task[HttpResponse[String]] =
    ZIO.attemptBlocking(
      client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
          .method(method, HttpRequest.BodyPublishers.noBody())
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    )

  /** The status line's code, or `None` when the connection is reset or closed before one arrives. A
    * raw socket, not `HttpClient`, because the JDK client retries an idempotent request once on a
    * reset connection, which would consume two injected faults with one call.
    */
  private def rawStatus(port: Int, path: String): Task[Option[Int]] =
    ZIO.attemptBlocking {
      scala.util
        .Using(new Socket("127.0.0.1", port)) { socket =>
          socket.setSoTimeout(5000)
          val out = socket.getOutputStream
          out.write(
            s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.US_ASCII)
          )
          out.flush()
          val line =
            new BufferedReader(
              new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII)
            )
              .readLine()
          Option(line).flatMap(_.split(' ').lift(1)).flatMap(_.toIntOption)
        }
        .toOption
        .flatten
    }

  // ── cases every lane runs ──────────────────────────────────────────────────────────────────────

  private def commonCases = Vector(
    // #172 — stateOps writes flow state after the response; a templated `{{ state.* }}` reads it.
    test("stateOps: two increments are visible to a later request in the same flow") {
      for
        imp <- created(
          imposter("stateops")
            .stub(on(Method.POST, "/hit").reply(ok.incrementState("hits")))
            .stub(on(Method.GET, "/count").reply(ok.text("hits={{ state.hits }}").templated))
        )
        _ <- send(portOf(imp), "/hit", "POST")
        _ <- send(portOf(imp), "/hit", "POST")
        r <- send(portOf(imp), "/count")
      yield assertTrue(r.statusCode() == 200, r.body() == "hits=2")
    },
    // #173 — behaviors run on the proxied response: copy fills a token the origin left, and the
    // wait delays it.
    test("proxy behaviors: copy and wait run on the upstream response") {
      for
        origin <- created(
          imposter("origin").stub(on(Method.GET, "/bob").reply(ok.text("hello ${who}")))
        )
        proxy <- created(
          imposter("proxy").stub(
            on(Method.GET, "/bob").reply(
              proxyTo(s"http://127.0.0.1:${portOf(origin)}")
                .copy(path, "${who}", regex("[a-z]+$"))
                .after(scala.concurrent.duration.FiniteDuration(250, "ms"))
            )
          )
        )
        // on the live clock: the ZIO test environment's clock is a TestClock, which never advances
        timed <- Live.live(send(portOf(proxy), "/bob").timed)
        (elapsed, r) = timed
      yield assertTrue(r.statusCode() == 200, r.body() == "hello bob", elapsed >= 250.millis)
    },
    // #173 — repeat on a fault response: the fault is served twice, then the next response.
    test("fault repeat: the reset is served twice, then the stub's next response") {
      for
        imp <- created(
          imposter("flaky").stub(
            on(Method.GET, "/flaky")
              .reply(fault(TcpFaultKind.ConnectionResetByPeer).repeat(2))
              .thenReply(ok.text("up"))
          )
        )
        first <- rawStatus(portOf(imp), "/flaky")
        second <- rawStatus(portOf(imp), "/flaky")
        third <- rawStatus(portOf(imp), "/flaky")
      yield assertTrue(first.isEmpty, second.isEmpty, third.contains(200))
    },
    // #159 — a `behaviors` array is an ordered program: both copy elements run, not just the last.
    test("behaviors array: every copy element runs") {
      val raw =
        """{"predicates":[{"equals":{"path":"/abc"}}],"responses":[{"is":{"statusCode":200,"body":"${a}-${b}"},"behaviors":[{"copy":{"from":"path","into":"${a}","using":{"method":"regex","selector":"[a-z]+$"}}},{"copy":{"from":{"query":"q"},"into":"${b}","using":{"method":"regex","selector":".+"}}}]}]}"""
      for
        stub <- ZIO.fromEither(stubFromJson(raw)).orDieWith(e => new AssertionError(e.toString))
        imp <- created(imposter("program").stubs(Vector(stub)))
        r <- send(portOf(imp), "/abc?q=zz")
      yield assertTrue(r.statusCode() == 200, r.body() == "abc-zz")
    },
    // #174 — the journal records what each request was answered with, and how long it took.
    test("recorded requests carry status and latencyMs") {
      for
        imp <- created(imposter("recorded").record.stub(on(Method.GET, "/r").reply(status(201))))
        _ <- send(portOf(imp), "/r")
        recs <- imp.recorded
        rec = recs.head
      yield assertTrue(
        recs.size == 1,
        rec.status.contains(201),
        rec.latencyMs.isDefined,
        rec.summary.startsWith("GET /r → 201 in ")
      )
    },
    // #175 — mutualAuth: a client without a certificate is refused at the handshake; one with any
    // certificate whose key it holds gets through. Built from the DSL's definition with protocol
    // https and no certificate of its own, so the engine serves a self-signed one.
    test("client-certificate auth refuses a client without a certificate") {
      for
        imp <- ZIO.serviceWithZIO[Rift](r =>
          ZIO.acquireRelease(
            r.create(
              imposter("mtls").requireClientCertificate
                .stub(on(Method.GET, "/secure").reply(ok.text("in")))
                .build
                .copy(protocol = Protocol.Https, clientAuth = ClientAuth(mutualAuth = Some(true)))
            )
          )(_.delete.orDie)
        )
        without <- httpsStatus(portOf(imp), "/secure", clientKeys = None).either
        keys <- ZIO.attemptBlocking(clientKeyStore())
        withCert <- httpsStatus(portOf(imp), "/secure", clientKeys = Some(keys))
      yield assertTrue(without.isLeft, withCert == 200)
    }
  )

  // ── lane-specific cases ────────────────────────────────────────────────────────────────────────

  // #157 — a script naming no engine runs under the imposter's defaultEngine. `function` is not Rhai,
  // so a Rhai default (the pre-0.18.0 behaviour) cannot produce this body.
  private def scriptCase =
    test("defaultEngine decides a script that names no engine") {
      val raw =
        """{"predicates":[{"equals":{"path":"/js"}}],"responses":[{"_rift":{"script":{"code":"function respond(ctx) { return http(200, { engine: 'js' }); }"}}}]}"""
      for
        stub <- ZIO.fromEither(stubFromJson(raw)).orDieWith(e => new AssertionError(e.toString))
        imp <- created(
          imposter("default-engine")
            .scriptEngine(ScriptEngine.JavaScript, scala.concurrent.duration.FiniteDuration(2, "s"))
            .stubs(Vector(stub))
        )
        r <- send(portOf(imp), "/js")
      yield assertTrue(
        r.statusCode() == 200,
        r.body().replace(" ", "").contains("\"engine\":\"js\"")
      )
    }

  // #177 — an intercept serve rule with a repeated header sends one line per value.
  private def interceptCase =
    test("intercept serve sends every value of a repeated header") {
      for
        ic <- ZIO.serviceWithZIO[Rift](_.intercept())
        _ <- ic
          .rule("cookies.example.com")
          .serve(ok.header("Set-Cookie", "a=1").header("Set-Cookie", "b=2").text("x"))
        ssl <- ic.sslContext
        proxy <- ic.proxySelector
        viaIntercept = HttpClient.newBuilder().proxy(proxy).sslContext(ssl).build()
        r <- ZIO.attemptBlocking(
          viaIntercept.send(
            HttpRequest.newBuilder(URI.create("https://cookies.example.com/")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
          )
        )
      yield assertTrue(r.headers().allValues("set-cookie").asScala.toList == List("a=1", "b=2"))
    }

  // ── TLS helpers for the client-certificate case ───────────────────────────────────────────────

  private object TrustAll extends X509TrustManager:
    def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
    def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
    def getAcceptedIssuers: Array[X509Certificate] = Array.empty

  /** GET over TLS to the engine's self-signed listener, presenting `clientKeys` if given. The
    * server certificate and host name are not checked: what is under test is the server's check of
    * the client.
    */
  private def httpsStatus(port: Int, path: String, clientKeys: Option[KeyStore]): Task[Int] =
    ZIO.attemptBlocking {
      val ctx = SSLContext.getInstance("TLS")
      val keyManagers = clientKeys.map { ks =>
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        kmf.init(ks, "changeit".toCharArray)
        kmf.getKeyManagers
      }
      ctx.init(keyManagers.orNull, Array[TrustManager](TrustAll), null)
      val conn = URI
        .create(s"https://127.0.0.1:$port$path")
        .toURL
        .openConnection()
        .asInstanceOf[HttpsURLConnection]
      conn.setSSLSocketFactory(ctx.getSocketFactory)
      conn.setHostnameVerifier((_, _) => true)
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)
      try conn.getResponseCode
      finally conn.disconnect()
    }

  /** A throwaway client key pair and self-signed certificate, generated by the JDK's `keytool` so
    * no key material is committed (the repo's rule). `mutualAuth` without `rejectUnauthorized`
    * accepts any certificate whose key the client holds.
    */
  private def clientKeyStore(): KeyStore =
    val dir = Files.createTempDirectory("rift-mtls")
    val file: Path = dir.resolve("client.p12")
    try
      val keytool = Path.of(java.lang.System.getProperty("java.home"), "bin", "keytool").toString
      val proc = new ProcessBuilder(
        keytool,
        "-genkeypair",
        "-alias",
        "client",
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        "CN=rift-scala-client",
        "-validity",
        "1",
        "-storetype",
        "PKCS12",
        "-keystore",
        file.toString,
        "-storepass",
        "changeit",
        "-keypass",
        "changeit",
        "-noprompt"
      ).redirectErrorStream(true).start()
      val output = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if proc.waitFor() != 0 then throw new IllegalStateException(s"keytool failed: $output")
      val ks = KeyStore.getInstance("PKCS12")
      scala.util.Using.resource(Files.newInputStream(file))(ks.load(_, "changeit".toCharArray))
      ks
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
