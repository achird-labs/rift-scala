# rift-scala

Official Scala 3 SDK for [Rift](https://github.com/EtaCassiopeia/rift) — a high-performance,
Mountebank-compatible HTTP/HTTPS mock server written in Rust. Effect-library-native:
ZIO, Cats Effect 3 / FS2, Kyo, or no effect system at all.

> **Status: design phase.** API design and milestones are tracked in the issues of this repo
> (milestones M3/M4). No artifacts are published yet.

## What it will look like

```scala
import rift.dsl.*
import rift.zio.*

object PaymentsSpec extends ZIOSpecDefault:
  val users = imposter("users").record
    .stub(on(GET, "/api/users/1").reply(ok.json("""{"id":1}""")))

  def spec = suite("payments")(
    test("records the lookup"):
      for
        api <- Rift.create(users)
        _   <- callSut(api.baseUri)
        _   <- api.verify(on(GET, "/api/users/1"), times = 1)
      yield assertCompletes
  ).provideShared(Rift.embedded)
```

## Modules

| Artifact | Contents |
|---|---|
| `rift-scala-model` | pure typed wire model + DSL (no effect dependency) |
| `rift-scala-bridge` | transports + natives via [rift-java](https://github.com/EtaCassiopeia/rift-java) |
| `rift-scala-zio` / `rift-scala-zio-testkit` | ZIO service + ZLayer lifecycles, `ZStream` request tail, zio-test glue |
| `rift-scala-cats` / `rift-scala-cats-testkit` | Cats Effect 3 `Rift[F]`, `Resource` lifecycles, munit/weaver glue |
| `rift-scala-fs2` | `Stream[F, RecordedRequest]` tailing + verification pipes |
| `rift-scala-kyo` | ops as `A < (Async & Abort[RiftError] & Resource)` |
| `rift-scala-pure` | `Either`-based sync surface, `Using`-friendly |

One DSL, every effect system: embedded (in-process, no Docker), connect (any running Rift
admin endpoint), spawn, or container — full feature surface on each, including stateful
scenarios, fault injection, spaces/flow-state, and TLS-MITM intercept.
