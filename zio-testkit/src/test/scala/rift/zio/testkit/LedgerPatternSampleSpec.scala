package rift.zio.testkit

import java.net.{InetSocketAddress, ProxySelector, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import javax.net.ssl.SSLContext

import zio.*
import zio.test.*

import rift.bridge.{CaMaterial, InterceptConfig}
import rift.dsl.*
import rift.model.Times
import rift.zio.Rift
import rift.zio.testkit.assertions.eventuallyReceived

/** RUNNABLE SAMPLE — the "ledger pattern" (issue #7).
  *
  * This is a walkthrough, not a unit test: it narrates a real-world consumer's shape end to end so
  * a reader can lift each step directly into their own suite. See `docs/samples/ledger-pattern.md`
  * for the prose version of this same story.
  *
  * THE SCENARIO
  *
  * A payments SUT reads account balances from a downstream HTTPS "ledger" API
  * (`https://ledger.internal`) and, on some code paths, writes back an async reconcile call. In a
  * test environment we don't want to stand up a real ledger, and we don't want the SUT's HTTP
  * client rewired just to point at a mock — so instead we:
  *
  *   1. Mock the ledger as a rift imposter (`.record` on, so every hit is recoverable later). 2.
  *      Start a FIXED-PORT TLS-intercepting proxy in front of the SUT's egress and redirect
  *      `ledger.internal` traffic to that imposter — the SUT's config/URLs never change, only where
  *      the bytes actually land. 3. Prove the read path works, then HOT-SWAP the imposter's stub
  *      set (its "datafile") to a second scenario without moving the intercept or re-pointing the
  *      SUT at all. 4. Poll for a request that a truly async SUT write may not have landed yet when
  *      we ask ("revision-bump" polling — the imposter's request log is the source of truth, and we
  *      keep re-checking it rather than assuming synchronous delivery). 5. Verify what actually
  *      arrived, both via a hard assertion (`verify`) and by reading the recorded requests back out
  *      (`recorded`).
  *
  * WHY A FIXED PORT AND NOT AN EPHEMERAL ONE
  *
  * A SUT's outbound proxy configuration (its trust store, its `HTTPS_PROXY` env var, whatever
  * mechanism directs its egress through the intercept) is usually set once, outside the test
  * process — CI job config, a docker-compose `environment:` block, a `.env` file. That
  * configuration needs a port that doesn't change between runs, which is exactly what
  * `InterceptConfig(port = ...)` (as opposed to the default `port = 0` ephemeral allocation) is
  * for.
  *
  * WHY THIS TEST IS GUARDED
  *
  * `Rift.embedded` acquires the embedded native engine, which is absent on a bare CI JVM (no
  * `rift-java-natives` / `--enable-native-access`). Providing `Rift.embedded` as a *suite* layer
  * would force that acquisition unconditionally and turn "skip in CI" into "fail in CI". Instead,
  * `rift.bridge.RiftConnector.isEmbeddedAvailable` is checked FIRST, and the engine layer is only
  * built (via `ZLayer#build` inside `ZIO.scoped`, not `.provide` on a suite) once we already know
  * it's safe to do so — see `bridge.EmbeddedSmokeSpec` / `cats.EmbeddedSmokeSpec` for the same
  * guard shape in this repo.
  */
object LedgerPatternSampleSpec extends ZIOSpecDefault:

  /** The fixed port a real consumer would bake into their egress config once and never change. */
  private val FixedInterceptPort = 18443

  private val LedgerHost = "ledger.internal"

  /** CA NOTE — committed vs. generated.
    *
    * `InterceptConfig(ca = None)` (the default, used below) makes the engine mint an EPHEMERAL CA
    * per run; the SUT trusts it not by pre-installed configuration but by using the `SSLContext`
    * `ic.sslContext` hands back, which already has that CA in its trust chain. That's the flow this
    * runnable sample exercises, and it's equivalent in every way that matters here to a committed
    * CA — the only thing a committed CA buys you is a STABLE root across process restarts, e.g. so
    * an out-of-process SUT's OS trust store or a mounted truststore file doesn't need updating
    * every run.
    *
    * If your consumer genuinely needs that stability, you supply your own cert+key PEM pair via
    * `CaMaterial` — never commit that pair to source control (this repo's own rule: no committed
    * credentials/keys). This helper shows the SHAPE of that config; it is compile-checked by this
    * file but deliberately never called from the runnable path below, and it does not carry a real
    * PEM pair — see `docs/samples/ledger-pattern.md` for where a consumer would source one from (a
    * secrets manager / CI secret, not the repo).
    */
  def committedCaConfig(certPem: String, keyPem: String): InterceptConfig =
    InterceptConfig(
      host = "127.0.0.1",
      port = FixedInterceptPort,
      ca = Some(CaMaterial(certPem, keyPem))
    )

  /** The SUT's HTTP client: trusts the intercept's minted leaf certs via `ic.sslContext`, and is
    * routed through the intercept's own listening address as an explicit proxy — mirroring how a
    * real SUT's `HTTPS_PROXY`/trust-store wiring would point at this same fixed port.
    */
  private def sutHttpClient(sslCtx: SSLContext, proxyAddress: InetSocketAddress): HttpClient =
    HttpClient
      .newBuilder()
      .proxy(ProxySelector.of(proxyAddress))
      .sslContext(sslCtx)
      .build()

  /** The java.net.http calls below stand in for the SUT's own outbound HTTP calls. Their failure
    * mode isn't part of rift's typed `RiftError` domain — an I/O hiccup talking to our own
    * just-started intercept is unexpected test infrastructure trouble, not a value this sample's
    * assertions branch on — so it's promoted to a defect (`orDie`) rather than threaded through the
    * error channel (Cause/Exit: Fail vs. Die stay distinct on purpose).
    */
  private def sutGet(client: HttpClient, uri: URI): UIO[HttpResponse[String]] =
    ZIO
      .attemptBlocking(
        client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
      )
      .orDie

  private def sutPost(client: HttpClient, uri: URI): UIO[HttpResponse[String]] =
    ZIO
      .attemptBlocking(
        client.send(
          HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
      .orDie

  def spec = suite("ledger pattern sample (#7)")(
    test(
      "fixed-port TLS intercept + CA, datafile hot-swap, revision-bump poll, recorded-request verification"
    ) {
      // Skip (never FAIL) when the embedded native engine isn't on this JVM — checked BEFORE any
      // layer is forced, so CI never pays the acquisition cost this guard exists to avoid. The
      // `logWarning` leaves a trace so a green run that never exercised the walkthrough (e.g. CI
      // without `rift-java-natives`) is distinguishable from one that actually ran it.
      if !rift.bridge.RiftConnector.isEmbeddedAvailable then
        ZIO.logWarning("ledger-pattern sample skipped: no embedded engine on this JVM") *>
          ZIO.succeed(assertCompletes)
      else
        ZIO.scoped {
          for
            // `ZLayer#build` (not `.provide` on the suite) so the embedded engine is only acquired
            // inside this already-guarded branch; `env.get[Rift]` unwraps the built environment.
            env <- Rift.embedded.build
            rift = env.get[Rift]

            // ── Step 1 — mock the ledger: scenario A, a healthy account. ────────────────────────
            // `.record` keeps every request the imposter sees so `recorded`/`verify` can look back.
            // No `.port(...)`: the engine assigns an ephemeral port (an absent port means "engine
            // picks one"; passing 0 is rejected). The imposter is reached via the intercept's
            // `redirectTo`, so its actual port never matters here.
            // NOTE: `rift.create` is not scope-wrapped — the enclosing `Rift.embedded` scope tears the
            // whole engine (and this imposter) down on close. A consumer pointing this pattern at a
            // shared, long-lived engine (`RiftTestKit.fromEnv`) should instead acquire the imposter via
            // `RiftTestKit.imposter(...)` so it is deleted on scope exit rather than leaked.
            ledgerImposter <- rift.create(
              imposter("ledger").record
                .stub(
                  get("/accounts/42")
                    .reply(ok.json("""{"accountId":"42","balance":100,"status":"active"}"""))
                )
            )

            // ── Step 2 — fixed-port TLS intercept, default generated CA. ────────────────────────
            // No `.when(...)` on the rule: an unqualified `.rule(host)` redirects EVERY request for
            // that host, so both the balance read and the later reconcile write share one rule —
            // the shape this pattern needs (see `InterceptRuleBuilder.redirectTo`'s own scaladoc).
            ic <- rift.intercept(InterceptConfig(port = FixedInterceptPort))
            _ <- ic.rule(LedgerHost).redirectTo(ledgerImposter)

            sslCtx <- ic.sslContext
            proxyAddr <- ic.address
            client = sutHttpClient(sslCtx, proxyAddr)

            // ── Step 3 — prove the read path: scenario A comes back through the intercept. ──────
            scenarioARead <- sutGet(client, URI.create(s"https://$LedgerHost/accounts/42"))

            // ── Step 4 — datafile hot-swap. ──────────────────────────────────────────────────────
            // Same imposter, same intercept rule, same SUT config — only the stub set changes. This
            // is the whole point of `replaceStubs`: a consumer swaps scenarios between test phases
            // without tearing down or re-registering anything the SUT depends on.
            _ <- ledgerImposter.replaceStubs(
              Chunk(
                get("/accounts/42")
                  .reply(ok.json("""{"accountId":"42","balance":0,"status":"frozen"}"""))
                  .build
              )
            )
            scenarioBRead <- sutGet(client, URI.create(s"https://$LedgerHost/accounts/42"))

            // ── Step 5 — revision-bump poll. ─────────────────────────────────────────────────────
            // Add a stub for the write path too (the reconcile call needs somewhere to land), then
            // fire it in the background — standing in for an async SUT call whose completion this
            // test doesn't control. `eventuallyReceived` polls the imposter's request log until the
            // reconcile shows up (or the timeout elapses), rather than assuming it lands
            // synchronously with the call that triggered it.
            _ <- ledgerImposter.replaceStubs(
              Chunk(
                get("/accounts/42")
                  .reply(ok.json("""{"accountId":"42","balance":0,"status":"frozen"}"""))
                  .build,
                post("/accounts/42/reconcile").reply(accepted).build
              )
            )
            // `.fork` and never join: this stands in for an async SUT write whose completion the test
            // doesn't await. The fiber is safe to leave dangling — the enclosing `ZIO.scoped`
            // interrupts it on exit — and a genuine failure still surfaces: `sutPost` only produces
            // defects (`orDie`), and if the reconcile never lands `eventuallyReceived` times out and
            // `reconcileSeen` reds the test rather than passing silently. Do NOT add `.join` here — it
            // would defeat the poll-don't-assume-synchronous point of the pattern.
            _ <- sutPost(client, URI.create(s"https://$LedgerHost/accounts/42/reconcile")).fork
            reconcileSeen <- eventuallyReceived(
              ledgerImposter,
              post("/accounts/42/reconcile"),
              timeout = 5.seconds
            )

            // ── Step 6 — verification. ───────────────────────────────────────────────────────────
            // `verify` fails fast with a typed `RiftError.VerificationFailed` when the expectation
            // isn't met; its `VerificationReport` renders a near-miss diff (closest recorded request
            // + which predicate failed) rather than a bare "not found" — see
            // `assertions.renderVerificationFailure` (D5) for the rendering this sample relies on
            // when a `verify`/`eventuallyReceived` call in a real suite fails and needs debugging.
            // `recorded` is the read-only complement: pull the matching requests back out directly.
            _ <- ledgerImposter.verify(get("/accounts/42"), Times.AtLeast(2))
            recordedGets <- ledgerImposter.recorded(get("/accounts/42"))
          // `reconcileSeen` (a TestResult from `eventuallyReceived`) is combined in with `&&` rather
          // than collapsed to `.isSuccess`, so on failure it carries `eventuallyReceived`'s near-miss
          // diff (the closest recorded request + which predicate missed) instead of a bare boolean.
          yield reconcileSeen && assertTrue(
            scenarioARead.body().contains("100"),
            scenarioBRead.body().contains("frozen"),
            recordedGets.size >= 2
          )
        }
    }
  )
