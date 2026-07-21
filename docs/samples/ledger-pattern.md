# Sample: the ledger pattern (issue #7)

A walkthrough of a real-world consumer's shape — fixed-port TLS intercept with a CA, imposter
datafile hot-swap, revision-bump polling, and recorded-request verification — composed entirely
from shipped rift-scala APIs.

Runnable spec: [`zio-testkit/src/test/scala/rift/zio/testkit/LedgerPatternSampleSpec.scala`](https://github.com/achird-labs/rift-scala/blob/master/zio-testkit/src/test/scala/rift/zio/testkit/LedgerPatternSampleSpec.scala).

## The scenario

A payments SUT reads account balances from a downstream HTTPS "ledger" API
(`https://ledger.internal`) and, on some paths, fires an async reconcile write back to it. In tests
we don't stand up a real ledger and we don't rewire the SUT's HTTP client to point somewhere else —
instead we intercept its TLS egress on a **fixed port** and redirect `ledger.internal` traffic to a
rift imposter that plays the ledger's part.

Six moves, all against shipped APIs:

1. **Mock the ledger.** Create an imposter with `.record` on, carrying a scenario-A stub.
2. **Intercept on a fixed port.** `rift.intercept(InterceptConfig(port = 18443))`, default generated
   CA, then a single unqualified rule that redirects *everything* for `ledger.internal` to the
   imposter.
3. **Prove the read path.** A `java.net.http.HttpClient` built from `ic.sslContext` (trusts the
   intercept's minted leaf certs) and routed through `ic.address` as an explicit proxy hits
   `https://ledger.internal/accounts/42` and gets scenario A's balance back.
4. **Hot-swap the datafile.** `ledgerImposter.replaceStubs(...)` swaps in scenario B (frozen
   account) — same imposter, same intercept rule, same SUT config, only the stub set changes.
5. **Revision-bump poll.** After firing an async reconcile write, `eventuallyReceived` polls the
   imposter's request log until it lands, instead of assuming synchronous delivery.
6. **Verify.** `verify(matching, Times.AtLeast(2))` asserts the read landed at least twice (scenario
   A + scenario B); `recorded(matching)` reads the matching requests back out directly.

## Step by step

### 1 — mock the ledger (scenario A)

```scala
val ledgerImposter = rift.create(
  imposter("ledger").record.stub(
    get("/accounts/42").reply(ok.json("""{"accountId":"42","balance":100,"status":"active"}"""))
  )
)
```

(No `.port(...)`: an absent port means "let the engine assign one" — passing `0` is rejected. The
imposter is reached through the intercept's `redirectTo`, so its port never matters here.)

### 2 — fixed-port TLS intercept, default CA, host-wide redirect

```scala
val ic = rift.intercept(InterceptConfig(port = 18443))
ic.rule("ledger.internal").redirectTo(ledgerImposter)
```

No `.when(...)` is chained on the rule: an unqualified `.rule(host)` redirects *every* request for
that host, so the same rule covers both the balance read (`GET /accounts/42`) and the later
reconcile write (`POST /accounts/42/reconcile`) — see `InterceptRuleBuilder.redirectTo`'s own
scaladoc in `rift.bridge.InterceptConnector`, which calls this out as exactly the datafile-hot-swap
shape issue #7 needs.

### 3 — the SUT's client, routed through the intercept

```scala
val client = HttpClient.newBuilder()
  .proxy(ProxySelector.of(proxyAddr))  // proxyAddr <- ic.address
  .sslContext(sslCtx)                  // sslCtx    <- ic.sslContext
  .build()
```

`ic.sslContext` already trusts the intercept's CA (generated or committed — see below), so no
separate truststore file is needed for this in-process client.

### 4 — datafile hot-swap

```scala
ledgerImposter.replaceStubs(
  Chunk(get("/accounts/42").reply(ok.json("""{"accountId":"42","balance":0,"status":"frozen"}""")).build)
)
```

The intercept rule and the SUT's proxy config never change — only the imposter's stub set does.
That's the whole value of `replaceStubs`: swap scenarios between test phases without tearing
anything down.

### 5 — revision-bump poll

```scala
ledgerImposter.replaceStubs(Chunk(/* scenario B GET stub */, post("/accounts/42/reconcile").reply(accepted).build))
// fire the SUT's async write in the background, then:
eventuallyReceived(ledgerImposter, post("/accounts/42/reconcile"), timeout = 5.seconds)
```

`eventuallyReceived` (from `rift.zio.testkit.assertions`) re-polls `verify` on a schedule until it
passes or the timeout elapses, returning the *last observed* result either way — so a timeout still
carries a near-miss diff, not a bare "timed out".

### 6 — verification

```scala
ledgerImposter.verify(get("/accounts/42"), Times.AtLeast(2))
val recordedGets = ledgerImposter.recorded(get("/accounts/42"))
```

On a mismatch, `verify` fails with the typed `RiftError.VerificationFailed`, whose
`VerificationReport` renders a near-miss diff — the closest recorded request and which predicate on
it failed — rather than a bare "not found" (D5; rendered by
`rift.zio.testkit.assertions.renderVerificationFailure`, exercised directly in `AssertionsSpec`).

## CA note: generated vs. committed

The runnable sample uses the **default generated CA** (`InterceptConfig(port = 18443)`, no `ca`
argument): the engine mints an ephemeral CA per run, and the SUT trusts it via the `SSLContext`
`ic.sslContext` returns — already carrying that CA in its trust chain. That is functionally
identical to a committed CA for everything this sample demonstrates.

A committed CA only buys you one extra thing: a **stable root across process restarts** — useful
when something outside this process (an OS trust store, a mounted truststore file, a separate SUT
process) needs to trust the intercept and can't just ask it for a fresh `SSLContext` each run. For
that case, a consumer supplies their own cert+key PEM pair:

```scala
def committedCaConfig(certPem: String, keyPem: String): InterceptConfig =
  InterceptConfig(host = "127.0.0.1", port = 18443, ca = Some(CaMaterial(certPem, keyPem)))
```

This helper is defined (compile-checked) in `LedgerPatternSampleSpec` but never invoked from the
runnable path — this repo does not commit certificate/key material to source control. A real
consumer sources that PEM pair from a secrets manager or CI secret, not from the repo.

## Why the test is guarded, not skipped in a suite layer

`Rift.embedded` acquires the embedded native engine, which isn't present on a bare CI JVM. The spec
checks `io.github.achirdlabs.rift.Rift.isEmbeddedAvailable()` **before** building any layer, and
only calls `Rift.embedded.build` (inside `ZIO.scoped`) once that check has already passed — so CI
skips the test instead of failing it, without ever paying the acquisition cost. The same guard shape
is used by `bridge.EmbeddedSmokeSpec` and `cats.EmbeddedSmokeSpec` elsewhere in this repo.
