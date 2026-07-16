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

### `_behaviors` — two wire forms in, one out

`POST /imposters` takes `_behaviors` as an **object**; `GET /imposters` hands it back as an **array
of single-key objects** (`[{"wait":100},{"decorate":"..."}]`, engine `behaviors_to_array`). Both
decode. Encoding always uses the object form — rift-java's policy verbatim, so a GET → PUT
normalizes spelling identically in both SDKs.

The array is the only form that can repeat a key. `copy`/`lookup`/`shellTransform` are the only keys
the object form spells as an array, so they are the only ones that may repeat — they accumulate into
that single array. Any other repeated key, **scalar or unknown**, has no object-form representation,
so it is a decode error rather than a silent last-wins that would lose data on the next encode.

That allow-list is deliberate: an unknown key the engine grows later is not vector-valued just
because this module doesn't model it, so its value passes through untouched. Wrapping it would turn
`{"futureThing":{...}}` into `{"futureThing":[{...}]}` on the next PUT — a document the author never
wrote, and precisely what `unknown` exists to prevent.

The one thing this cannot round-trip is an array that repeats a non-vector key — the same limitation
rift-java documents, and unreachable from any engine output.

### Still unverified

`Admin.ScenarioStatus`'s `{name, state}` shape has no reference in the engine or rift-java, so it is
asserted by the conformance corpus (#6) rather than from this module. Everything else previously
tracked in [#20](https://github.com/EtaCassiopeia/rift-scala/issues/20) is now modeled.

### `_behaviors.wait` — four canonical spellings

Per the [rift#608 ruling](https://github.com/EtaCassiopeia/rift/issues/608), every form below is
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
break round-tripping. Following rift-java 0.1.2's policy, putting a **modeled** key into `extra` is a
construction error rather than a silent override.

### Testing

Tests use **munit**, not zio-test: this module must stay effect-agnostic, and its own test
classpath is held to the same standard as its compile classpath. The `zeroDepCheck` build task fails
the build if a non-Test dependency is ever added here.

Acceptance fixtures under `src/test/resources/examples/` are vendored from the `rift` repo at the
pinned engine version; see their `PROVENANCE.md`.
