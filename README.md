# rift-scala

Official Scala 3 SDK for [Rift](https://github.com/achird-labs/rift) — a high-performance,
Mountebank-compatible HTTP/HTTPS mock server written in Rust. Effect-library-native:
ZIO, Cats Effect 3 / FS2, Kyo, or no effect system at all.

📖 **Documentation site: [achird-labs.github.io/rift-scala](https://achird-labs.github.io/rift-scala/)**

> **Available on Maven Central.** The current release is **0.1.0** under the
> `io.github.achird-labs` group ID. It runs the engine four ways — embedded (in-process over
> Panama FFM, no Docker), connected to any running admin endpoint, as a managed spawned
> binary, or in a container — with the full feature surface on each.

## What it looks like

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

`Rift.embedded` needs no Docker and no separate binary; swap it for `Rift.connect(uri)`,
`Rift.spawn()` or `Rift.container()` without touching the test body.

## Installation

Every module is published under `io.github.achird-labs` for Scala 3. Pick the surface that
matches your effect system:

```scala
// ZIO
libraryDependencies += "io.github.achird-labs" %% "rift-scala-zio" % "0.1.0" % Test

// Cats Effect 3
libraryDependencies += "io.github.achird-labs" %% "rift-scala-cats" % "0.1.0" % Test

// no effect system
libraryDependencies += "io.github.achird-labs" %% "rift-scala-pure" % "0.1.0" % Test
```

The **embedded** transport additionally needs the engine's native library and the JVM flag
`--enable-native-access=ALL-UNNAMED`:

```scala
libraryDependencies ++= Seq(
  "io.github.achird-labs" % "rift-java-embedded" % "0.2.2" % Test,
  ("io.github.achird-labs" % "rift-java-natives" % "0.2.2" % Test)
    .classifier(RiftNatives.currentClassifier)   // linux-x86_64, darwin-aarch64, …
)
```

`rift-java-embedded` is JDK-22 bytecode (stable FFM); on JDK 21 use `rift-java-embedded-jdk21`,
which also requires `--enable-preview`. The other transports have no such requirement — the
SDK itself targets **JDK 21+**.

## Modules

| Artifact | Contents |
|---|---|
| `rift-scala-model` | pure typed wire model + DSL (no effect dependency) |
| `rift-scala-bridge` | transports + natives via [rift-java](https://github.com/achird-labs/rift-java) |
| `rift-scala-zio` / `rift-scala-zio-testkit` | ZIO service + ZLayer lifecycles, `ZStream` request tail, zio-test glue |
| `rift-scala-cats` / `rift-scala-cats-testkit` | Cats Effect 3 `Rift[F]`, `Resource` lifecycles, munit/weaver glue |
| `rift-scala-fs2` | `Stream[F, RecordedRequest]` tailing + verification pipes |
| `rift-scala-kyo` | ops as `A < (Async & Abort[RiftError] & Resource)` |
| `rift-scala-pure` | `Either`-based sync surface, `Using`-friendly |
| `rift-scala-zio-json` / `rift-scala-circe` | `JsonBody[A]` codec side-cars (typed request/response bodies) |
| `rift-scala-zio-bdd` | [zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) `MockControl` adapter backed by rift-scala |

One DSL, every effect system — full feature surface on each, including stateful scenarios,
fault injection, spaces/flow-state, proxy record/replay and TLS-MITM intercept.

### zio-bdd conformance

`rift-scala-zio-bdd` is certified against zio-bdd's **own published conformance catalogue**
(`zio-bdd-mock-conformance`), the compliance bar zio-bdd defines for third-party `MockControl`
adapters — not a hand-written stand-in. It passes every scenario its capabilities cover, and the
suite runs in CI on every commit.

## Documentation

- [Design](docs/DESIGN.md) — the accepted API design (RFC-003), module graph, and rationale
- [Parity](docs/PARITY.md) — feature parity against the engine surface
- [Ledger pattern](docs/samples/ledger-pattern.md) — a runnable end-to-end walkthrough
  (intercept + datafile hot-swap + async polling), mirrored by a real spec in the test suite

## Building

Scala 3, sbt multi-module. Requires JDK 21+ (CI builds on JDK 21 and 22).

```sh
sbt compile          # compile every module
sbt test             # run all tests
sbt scalafmtCheckAll # verify formatting (scalafmtAll to fix)
```

Module dependency graph: `model ◁ bridge ◁ {zio, cats, kyo, pure}`, `zio ◁ zio-testkit ◁ zio-bdd`,
`cats ◁ {cats-testkit, fs2}`, plus the model-only codec side-cars `zio-json` and `circe` (full
picture in [docs/DESIGN.md](docs/DESIGN.md) §3).

The engine version is pinned transitively by `rift-java` — never pin it separately. `scripts/bump.sh`
moves that pin and every version literal that tracks it; a daily workflow opens the bump PR
automatically. Releases publish to Maven Central via `sbt-ci-release` on a `v*` tag.
