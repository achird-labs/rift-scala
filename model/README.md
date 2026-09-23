# rift-scala-model

The pure wire model for Rift: **zero dependencies**, no effect system, no I/O. Everything else in
rift-scala (`bridge`, `zio`, `cats`, `fs2`, `kyo`, `pure`, the testkits, the codec side-cars) is
built on top of this module.

Four packages:

| Package | Contents |
|---|---|
| `rift.json` | Minimal JSON AST — `Json` enum, parser, writer, `semanticEquals`, `get` |
| `rift.model` | The Mountebank + `_rift` wire model as ADTs, admin data types, `JsonBody[A]` |
| `rift.model.matching` | `RequestMatcher` — pure client-side predicate evaluation and near-miss diffs |
| `rift.dsl` | The builder grammar (`on` / `where` / `reply`, scenarios, imposters) |

```scala
import rift.dsl.*
import rift.model.Method.*

imposter("users")
  .port(4545)
  .record
  .stub(on(GET, "/api/users/1").where(header("Accept").contains("json")).reply(ok.json("""{"id":1}""")))
```

## Codec decision (D1) — why a vendored JSON AST

Issue #2 required a codec spike: **an internal minimal JSON writer vs zio-json**. The resolution is
a vendored minimal JSON AST (`rift.json`) with **both a parser and a writer**.

| Option | Verdict |
|---|---|
| **Vendored AST + parser + writer** (chosen) | Keeps `model` dependency-free, so Cats/Kyo/pure users never pull a ZIO artifact. |
| zio-json | Rejected: drags a ZIO dependency into the shared base, and therefore into the `cats`, `kyo` and `pure` surfaces. |
| Writer only (no parser) | Rejected: the acceptance criterion is a decode -> encode **round-trip**, and the `fromJson` escape hatches must parse. Writing only half a codec means hand-rolling a parser later anyway. |
| Reusing rift-java's `JsonValue` | Rejected: couples the pure model to a JVM artifact, defeating the point of a dependency-free base. |

Typed codecs for user payloads are **not** this module's job. `model` ships `JsonBody[A]` instances
only for `Json`, `String` and primitives; real instances arrive via the side-car artifacts
`rift-scala-zio-json` (#16) and `rift-scala-circe` (#17), which depend on `model` alone. That is what
lets a ZIO user write `ok.json(user)` with their existing `JsonCodec` while a Cats user does the same
with a circe `Encoder`, and neither pays for the other's dependency.

`semanticEquals` mirrors rift-java's `JsonValue.semanticEquals` (key order ignored, `1 == 1.0`) and
is the comparator for the conformance expressibility gate.

### How the wire shapes were established

The Mountebank core (`predicates`, `responses`, `_behaviors`) is exercised by the vendored fixtures, so
it is verified by `RoundTripSpec`. The **`_rift` extension surface is not** — no example imposter contains
a `_rift` block — so those shapes were verified by reading the references directly and are pinned by
`RiftWireShapeSpec`, which cites the proving `file:line` for each:

- the **Rift engine** (`crates/rift-mock-core/src/imposter/types.rs` and friends) — authoritative;
- **rift-java 0.1.2** — the SDK this one mirrors (D2);
- **zio-bdd** — an independent Scala cross-check.

Two conventions come from that comparison rather than from taste, and should not be "tidied up":

- `protocol`, `predicates` and `responses` are **always emitted**, even at their defaults, because
  rift-java decodes them as optional but writes them unconditionally
  (`ImposterDefinition.java:108`, `Stub.java:88-89` vs `:103-104`). Omitting them would be a
  gratuitous divergence between the two SDKs on the wire.
- `cert`/`key` are **flat** top-level imposter fields; there is no `tls` wrapper on the wire
  (`types.rs:812-817`). The Scala `tls: Option[TlsMaterial]` field is an ergonomic grouping that is
  flattened on encode.

### Behaviors — an ordered program, two wire forms

A response's behaviors are `Behaviors(entries: Vector[Behavior], spelling)`: an ordered program of
steps (`Wait`, `Decorate`, `Copy`, `Lookup`, `ShellTransform`, `Repeat`, and `Unknown` for a key
this module does not model). Two wire shapes carry it:

- the **`_behaviors` object**, which fixtures and the DSL write. Its keys run in the engine's fixed
  order (`wait`, `lookup`, `copy`, `shellTransform`, `decorate`) and none can repeat;
- the **`behaviors` array** of single-key elements (`[{"wait":100},{"decorate":"..."}]`), which the
  engine's `GET /imposters` writes. From engine 0.18.0 every element runs, in array order, and a
  key may repeat.

Both decode, and encoding writes back the spelling that was read. A block read as an object is
still written as the array when a key repeats, because the object would keep only one of them.
A single `copy`/`lookup` entry or `shellTransform` command keeps its bare spelling (`{...}` rather
than `[{...}]`), since `semanticEquals` tells `Obj` from `Arr`. A response-level `repeat` beside
`is`, Mountebank's spelling, is carried as `Repeat(count, responseLevel = true)` and written back
beside `is`.

Two array shapes get special handling. A multi-key element runs its keys in the
engine's order, not the order written, so it is expanded into one step per key in that order and
written back one key per element: the spelling changes, what runs does not. A `null` value for a
modeled key is refused with an error saying how to rewrite the document. The engine never writes
one, and in the array form it would silently remove every earlier step of its key.

### Still unverified

`Admin.ScenarioStatus`'s `{name, state}` shape has no reference in the engine or rift-java, so it is
asserted by the conformance corpus (#6) rather than from this module. Everything else previously
tracked in [#20](https://github.com/achird-labs/rift-scala/issues/20) is now modeled.

### `_behaviors.wait` — four canonical spellings

Per the [rift#608 ruling](https://github.com/achird-labs/rift/issues/608), every form below is
canonical, and each **re-encodes to the spelling it was decoded from** so that
`GET /imposters?replayable=true` hands an author back their own config. The variant set mirrors
rift-java's `WaitSpec`, so both SDKs accept and emit the same wire forms.

| Wire | Case | DSL | Portable to Mountebank? |
|---|---|---|---|
| `100` | `Fixed(100)` | `ok.after(100.millis)` | yes |
| `"function () {...}"` | `Script(...)` | *(decode/round-trip only)* | yes |
| `{"min":100,"max":500}` | `Range(100, 500)` | `ok.afterBetween(100.millis, 500.millis)` | no — Rift extension |
| `{"inject":"function () {...}"}` | `Inject(...)` | `ok.afterInject(js)` | no — Rift/SDK spelling |

`afterBetween` emits the native `{min,max}` wait rather than a generated JS function, so a random
delay needs nothing enabled at serve time. `Script` has no DSL sugar on purpose: it exists so a
Mountebank-portable config round-trips faithfully, while new configs get the Rift spelling.

### Forward compatibility

Unknown wire keys are preserved on an `extra: Vector[(String, Json)]` component of
`ImposterDefinition` / `Stub` / `Response` and friends, so an engine that grows a field does not
break round-tripping. That includes every level of the `_rift` extension: `RiftConfig`,
`FlowStateConfig` and `RiftResponseExt` each carry their own `extra`, so an engine-added key such
as `_rift.warnings` survives a read and a write. Following rift-java 0.1.2's policy, putting a
**modeled** key into `extra` is a construction error rather than a silent override.

### Testing

Tests use **munit**, not zio-test: this module must stay effect-agnostic, and its own test
classpath is held to the same standard as its compile classpath. The `zeroDepCheck` build task fails
the build if a non-Test dependency is ever added here.

Acceptance fixtures under `src/test/resources/examples/` are vendored from the `rift` repo at the
pinned engine version; see their `PROVENANCE.md`.
