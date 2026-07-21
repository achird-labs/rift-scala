# rift-scala

Official Scala 3 SDK for [Rift](https://github.com/achird-labs/rift) — a high-performance,
Mountebank-compatible HTTP/HTTPS mock server written in Rust.

Effect-library-native: **ZIO**, **Cats Effect 3 / FS2**, **Kyo**, or no effect system at all. One
DSL and one typed wire model underneath all of them, so the surface you import is a matter of taste
rather than capability.

**Current release: 0.1.0**, on Maven Central under `io.github.achird-labs`.

## Four ways to run the engine

| Transport | What it does | Needs |
|---|---|---|
| `Rift.embedded` | runs the engine **in-process** over Panama FFM | a natives jar + `--enable-native-access=ALL-UNNAMED` |
| `Rift.connect(uri)` | talks to any running Rift admin endpoint | a reachable engine |
| `Rift.spawn()` | manages a downloaded engine binary for you | nothing |
| `Rift.container()` | runs the engine in a Testcontainers container | Docker |

The test body is identical on all four — swapping transports is a one-line change to the layer.

## A first test

```scala
import zio.*
import zio.test.*

import rift.dsl.*
import rift.zio.Rift

object PaymentsSpec extends ZIOSpecDefault:

  def spec = suite("payments")(
    test("records the lookup"):
      for
        users <- Rift.create(
          imposter("users").record.stub(
            get("/api/users/1").reply(ok.json("""{"id":1}"""))
          )
        )
        _ <- callSut(users.uri) // point your SUT at users.uri
        _ <- users.verify(get("/api/users/1"), 1)
      yield assertCompletes
  ).provideShared(Rift.embedded)
```

`.record` makes the imposter keep every request it serves, which is what `verify` and `recorded`
read back. Without it the imposter still serves stubs, but there is nothing to assert against
afterwards.

## Installation

```scala
// ZIO
libraryDependencies += "io.github.achird-labs" %% "rift-scala-zio" % "0.1.0" % Test

// Cats Effect 3
libraryDependencies += "io.github.achird-labs" %% "rift-scala-cats" % "0.1.0" % Test

// no effect system
libraryDependencies += "io.github.achird-labs" %% "rift-scala-pure" % "0.1.0" % Test
```

For the embedded transport, add the engine's native library:

```scala
libraryDependencies ++= Seq(
  "io.github.achird-labs" % "rift-java-embedded" % "0.2.0" % Test,
  ("io.github.achird-labs" % "rift-java-natives" % "0.2.0" % Test)
    .classifier(RiftNatives.currentClassifier)   // linux-x86_64, darwin-aarch64, …
)
```

`rift-java-embedded` is JDK-22 bytecode (stable FFM); on JDK 21 use `rift-java-embedded-jdk21`,
which also requires `--enable-preview`. The SDK itself targets JDK 21+.

## Feature surface

Stubs and predicates, response cycling, behaviors, proxy record/replay, fault injection, stateful
scenarios, spaces/flow-state, request verification, a cursor-based request tail (`ZStream` on ZIO,
`fs2.Stream` on Cats), and TLS-MITM intercept — available on every transport.

The engine version is pinned transitively by
[rift-java](https://github.com/achird-labs/rift-java); it is never pinned separately.

## zio-bdd

`rift-scala-zio-bdd` implements [zio-bdd](https://github.com/EtaCassiopeia/zio-bdd)'s `MockControl`
SPI, and is certified against zio-bdd's **own published conformance catalogue**
(`zio-bdd-mock-conformance`) — the compliance bar zio-bdd defines for third-party adapters, rather
than a hand-written stand-in. It passes every scenario its capabilities cover, and that suite runs
in CI on every commit.

## Where to go next

- **[Ledger pattern](samples/ledger-pattern.md)** — a full walkthrough: TLS intercept in front of a
  SUT's egress, hot-swapping an imposter's stub set mid-test, and polling for an async write. It is
  mirrored by a runnable spec in the test suite, so the code on the page is code that executes.
- **[Design (RFC-003)](DESIGN.md)** — the accepted API design: module graph, effect-surface
  rationale, and the decisions behind the wire model.
- **[Engine parity](PARITY.md)** — what the SDK covers against the engine's own surface.
