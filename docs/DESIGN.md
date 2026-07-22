# rift-scala — Library Design

**Status:** accepted design, implementation phase (M3/M4/M5).
**Informed by:** rift engine **v0.15.0** wire surface, **rift-java v0.1.3** (released 2026-07), the
`sdk-conformance` contract (RFC-003 §9.2), and **zio-bdd v1.4.2**'s `MockControl` SPI.
(Originally authored against v0.13.5 / rift-java 0.1.1; re-verified against v0.14.0 — the
imposter-definition wire surface is unchanged, and the engine now carries the `Inject` wait
variant natively, closing the rift#608 gap this design already modeled. Re-verified again against
v0.15.0: the conformance corpus fixtures are byte-identical to v0.14.0's, so nothing this design
models moved.)

This document is the single source of truth for the public API of every rift-scala module. Each
feature issue (#2–#13 and the M5 issues) links to its section here; scope changes land here first.

---

## 1. Goals

rift-scala is the official Scala 3 SDK for [Rift](https://github.com/achird-labs/rift), a
high-performance Mountebank-compatible HTTP/HTTPS mock server written in Rust.

1. **One DSL, every effect system.** A single pure, dependency-free model + DSL, with thin
   effect-native surfaces for ZIO 2, Cats Effect 3 (+ FS2), Kyo, and plain Scala (`Either`).
   Idiomatic per backend — a ZIO user sees `ZLayer`/`ZStream`, a CE user sees
   `Resource`/`Stream`, nobody sees the other's library on their classpath.
2. **Full feature surface on every transport.** Embedded (in-process via rift-java's FFM
   bridge, no Docker), connect (any running admin endpoint), spawn (managed local binary), and
   container (testcontainers) — including stateful scenarios, spaces/flow-state, fault
   injection, scripting, proxy record/replay, and TLS-MITM intercept.
3. **Test-framework-first developer experience.** First-class glue for zio-test,
   munit-cats-effect, and weaver; a zio-bdd `MockControl` adapter so rift-scala is the
   foundation other test libraries build on, not just an end-user tool.
4. **Conformance-gated.** CI replays the engine's versioned `sdk-conformance` corpus through
   the typed DSL over both embedded and remote transports. A fixture the DSL cannot express is
   a red build.

**Non-goals:** reimplementing FFM/native loading or the admin HTTP client (rift-java owns
those — issue #3); Scala 2 support; Scala.js/Native (revisit if the engine grows a WASM FFI).

---

## 2. Upstream contracts

Facts the design is built on (verified against the released artifacts):

### rift-java 0.1.1

| Artifact (`io.github.achird-labs`) | JDK | Role for rift-scala |
|---|---|---|
| `rift-java-core` | 17+ | Facade (`Rift`, `Imposter`, `Intercept`, …), remote + spawn transports, `JsonValue`, sealed `RiftException`. Zero runtime deps. |
| `rift-java-embedded` | 22+ | In-process engine via stable FFM; ServiceLoader-discovered `EmbeddedEngineProvider`. |
| `rift-java-embedded-jdk21` | 21 (preview) | Same sources compiled `--release 21 --enable-preview`. |
| `rift-java-natives` | — | `librift_ffi` cdylib, 6 classifier jars (`darwin-aarch64`, `linux-x86_64`, …). |
| `rift-java-testcontainers` | 17+ | `RiftContainer` with `hostResolver`-wired `client()`. |
| `rift-java-bom` | — | Pins everything, including natives classifiers. |

Key facade facts:

- All calls are **synchronous and blocking** (JDK `HttpClient` / direct FFM downcalls).
  Handles are stateless views — every call round-trips to the engine.
- **Raw-JSON escape hatches exist at every level** and are documented as *the* seam for
  bridges: `Rift.create(String)`, `Imposter.addStub(JsonValue)`, `RequestMatch.ofJson(...)`,
  `ImposterDefinition.fromJson/toJson`.
- Errors: `sealed RiftException permits InvalidDefinition, EngineUnavailable,
  CommunicationError, ImposterNotFound, EngineError` (all unchecked), plus
  `VerificationException extends AssertionError`.
- Engine pin: rift-java 0.1.3 → engine **0.15.0**; compatibility floor 0.13.1; version
  preflight on `connect` (configurable `FAIL | WARN | OFF`).
- Embedded needs `--enable-native-access=ALL-UNNAMED` and a natives classifier jar (or
  `-Drift.ffi.lib`); on JDK 21 additionally `--enable-preview` with the `-jdk21` artifact.

### The sdk-conformance contract

Every release of the engine publishes `sdk-conformance-<version>.tar.gz`:
`manifest.json` (+ `engineVersion`, fixture list with `requires` capability gates from the
closed set `injection | proxy | redis | https | shell`) and `corpus/imposters/NN-name.json`
fixtures carrying optional `_verify.sequence[]` transcripts. The contract for an official SDK
(rift-scala is named as one) is:

1. **DSL-expressibility gate** — rebuild each fixture through the typed DSL and deep-equal
   the serialized JSON against the fixture.
2. **Replay gate** — serve each fixture and replay `_verify.sequence[]`
   (`request` → `expect: {status, bodyContains}`).
3. Run both over **embedded and remote** transports with identical assertions.
4. Skip only what the manifest's `requires` gates say to skip.
5. Version-lock the corpus to the pinned engine version.

### zio-bdd 1.4.2 (extension target)

zio-bdd's backend seam is the `MockControl` SPI: `provision(MockSource)`, rule CRUD,
`received`, plus typed capability accessors (`faults`, `scenarios`, `stateInspection`,
`scripting`, `proxyRecord`, `templating`, `intercept`) negotiated via a `Capability` enum, with
errors as values (`MockError`). Its portable model (`MockRule`, `RequestMatch`, `ResponseDef`,
`RecordedRequest`, `MockSpace`) is deliberately backend-neutral; `NativeSpec[Backend.Rift]`
carries raw imposter JSON as the escape hatch. Adapters ship as `ZLayer`s. Section 5.12
designs a rift-scala-backed adapter.

---

## 3. Architecture

```
                    ┌───────────────────────────┐
                    │       rift-scala-model    │  pure: wire model, JSON AST,
                    │  (no dependencies at all) │  DSL, client-side matcher
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │      rift-scala-bridge    │  rift-java-core (compile),
                    │ blocking connector, error │  rift-java-testcontainers (optional);
                    │ mapping, config types     │  embedded backend picked up at runtime
                    └──┬───────┬───────┬───────┬┘
                       │       │       │       │
              ┌────────▼─┐ ┌───▼────┐ ┌▼─────┐ ┌▼───────┐
              │   zio    │ │  cats  │ │ kyo  │ │  pure  │
              └───┬───┬──┘ └─┬────┬─┘ └──────┘ └────────┘
                  │   │      │    │
       ┌──────────▼┐ ┌▼──────▼─┐ ┌▼─────────────┐
       │zio-testkit│ │   fs2   │ │ cats-testkit │
       └─────┬─────┘ └─────────┘ └──────────────┘
             │
       ┌─────▼───────┐    codec side-cars (depend only on model):
       │   zio-bdd   │    ┌──────────┐  ┌───────┐
       │  (adapter)  │    │ zio-json │  │ circe │
       └─────────────┘    └──────────┘  └───────┘

       conformance (unpublished, aggregates zio + cats + pure)
```

Published artifacts (`io.github.achird-labs`, Scala 3):

| Artifact | Package | Hard deps | Issue |
|---|---|---|---|
| `rift-scala-model` | `rift.model`, `rift.json`, `rift.dsl` | *(none)* | #2 |
| `rift-scala-bridge` | `rift.bridge` | model, `rift-java-core`; `rift-java-testcontainers` `% Optional` | #3 |
| `rift-scala-zio` | `rift.zio` | bridge, `zio`, `zio-streams` | #4 |
| `rift-scala-zio-testkit` | `rift.zio.testkit` | zio, `zio-test` | #5 |
| `rift-scala-zio-json` | `rift.json.ziojson` | model, `zio-json` | #16 |
| `rift-scala-cats` | `rift.cats` | bridge, `cats-effect` | #8 |
| `rift-scala-cats-testkit` | `rift.cats.testkit` | cats, `munit-cats-effect` `% Optional`, `weaver-cats` `% Optional` | #10 |
| `rift-scala-fs2` | `rift.fs2` | cats, `fs2-core` | #9 |
| `rift-scala-circe` | `rift.json.circe` | model, `circe-core` | #17 |
| `rift-scala-kyo` | `rift.kyo` | bridge, `kyo-core` | #11 |
| `rift-scala-pure` | `rift.pure` | bridge | #12 |
| `rift-scala-zio-bdd` | `rift.ziobdd` | zio module, `zio-bdd-mock` | #18 |

Root package is `rift` (as the README promises: `import rift.dsl.*`, `import rift.zio.*`).

**Convention for library sources:** inside `package rift.zio` / `rift.cats` / `rift.fs2`, the
enclosing-package member shadows the root library package, so all internal imports of effect
libraries use `_root_.zio.*` / `_root_.cats.*` / `_root_.fs2.*`.

**The same shadowing reaches users, and is accepted for the backend packages only** (#24). A
wildcard import of a *parent* binds its subpackage names, so `import rift.*` shadows the root `zio` /
`cats` / `fs2` packages in that file. That is tolerable here because `rift` holds a single symbol
(`RiftError`), making `import rift.*` a rare spelling with an easy precise alternative — and because
these names are the product's headline ergonomics (`rift.zio.Rift`) and mirror the artifact names.
The rule of thumb: **don't wildcard-import `rift` in a file that imports an effect library by its
root name.**

It is *not* tolerable for a codec side-car, which is why `rift-scala-zio-json` is `rift.json.ziojson`
rather than `rift.json.zio`: `rift.json` holds the `Json` AST and friends, so `import rift.json.*` is
a natural line, and every user of that artifact has `zio.*` imports in the same codebase. Adding the
artifact would have broken previously-valid files with an error that never names the cause. Any
future side-car must avoid a segment spelled like its library's root package (`rift.json.circe` is
fine — circe roots at `io.circe`).

---

## 4. Cross-cutting decisions

| # | Decision | Alternatives rejected |
|---|---|---|
| D1 | `model` owns a minimal vendored JSON AST with parser **and** writer (`rift.json.Json`, ~450 LoC, zero deps) | zio-json (drags a ZIO dep into cats/kyo/pure); writer-only (round-trip acceptance and `fromJson` escape hatches need parsing); reusing rift-java's `JsonValue` (couples the pure model to a JVM artifact) |
| D2 | The bridge talks to rift-java's **public facade** and feeds it **serialized JSON** through the documented escape hatches; responses are parsed back into the Scala model | Translating Scala model → Java model object-by-object (double maintenance, drifts); driving the `RiftTransport` SPI directly (reimplements spawn/ServiceLoader/preflight/intercept wiring) |
| D3 | One error ADT for the whole SDK: `enum RiftError extends Exception with NoStackTrace`, defined in `bridge`; mapping from `RiftException` happens once, at the bridge boundary | Per-module error types (N translations); non-Throwable ADT (Cats Effect and Kyo's `Abort` interop, and `ZIO#refineToOrDie`, all get simpler when the typed error *is* a Throwable) |
| D4 | No shared tagless-final core. `bridge` exposes one blocking, throwing, Scala-typed connector; each effect module wraps it in its own idiom (~300 LoC each) | Tagless-final `Rift[F[_]]` shared across ZIO/Kyo/pure (forces a lowest-common-denominator API, leaks a typeclass hierarchy into the ZIO surface, and pure/`Either` doesn't fit `F[_]` without ceremony) |
| D5 | Verification runs **engine-side** (facade `verify`); the testkits *additionally* run the model's pure client-side matcher over `recorded()` to render readable diffs on failure | Client-side-only verification (drifts from engine predicate semantics); engine-side-only (workable now that `VerificationException.result()` is structured, but the testkits' client-side pass still renders diffs in the model's own predicate vocabulary) |
| D6 | Request tailing is **cursor-polling** over the engine's stable per-port journal index (rift#603): `recordedPage()` baselines, `recordedSince(cursor)` fetches strictly-newer, default every 100 ms, exposed as `ZStream` / fs2 `Stream[RecordedRequest]`. Adopting the cursor is a **drop-in** (signatures unchanged); the richer SSE `/events` stream (rift#461 — lifecycle + `lagged` events) is an **additive new type**, not a transparent swap | Client-side array-offset poll (`all.drop(offset)` silently skips/replays: journal positions shift under the 10k `pop_front` eviction, `DELETE savedRequests`, and clears — no stable positions); blocking long-poll (no such endpoint) |
| D7 | Body codecs are a tiny model-level typeclass `JsonBody[A]`; zio-json and circe instances ship as **separate side-car artifacts** so no backend forces a JSON library on users | zio-json hard dep in the zio module / circe in cats (violates "cats-core never leaks", and ZIO users on jsoniter shouldn't pull zio-json) |
| D8 | JDK: build & publish on 21 (LTS floor, matches rift-java-core's 17+ easily); embedded tests run on the 22 CI job with `rift-java-embedded`, the 21 job covers remote transports (and optionally embedded via `-jdk21` + `--enable-preview`) | Requiring JDK 22 for the whole build (excludes LTS-pinned users for no reason — only the *embedded test runtime* needs it) |
| D9 | Version pinning: rift-scala `x.y.z` pins rift-java (and transitively the engine + corpus version). `rift.bridge.RiftVersions` exposes all three at runtime | Independent engine pin in rift-scala (two pins that can disagree) |

---

## 5. Module designs

### 5.1 `rift-scala-model` — pure wire model + DSL *(issue #2)*

Zero dependencies. Three packages:

#### 5.1.1 `rift.json` — minimal JSON AST

```scala
package rift.json

enum Json:
  case Obj(fields: Vector[(String, Json)]) // insertion-ordered, duplicate keys rejected
  case Arr(items: Vector[Json])
  case Str(value: String)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Null

object Json:
  def parse(text: String): Either[JsonError.Parse, Json]
  def obj(fields: (String, Json)*): Json
  def arr(items: Json*): Json

  extension (self: Json)
    def render: String                       // compact, stable field order
    def renderPretty: String
    def semanticEquals(other: Json): Boolean // key order ignored, 1 == 1.0
    def get(path: String*): Option[Json]     // cheap field navigation for tests
```

`semanticEquals` mirrors rift-java's `JsonValue.semanticEquals` and is the comparator for the
conformance expressibility gate. Every wire type provides `def toJson: Json` and a companion
`def fromJson(json: Json): Either[JsonError.Decode, A]`. Unknown wire keys are preserved on an
`extra: Vector[(String, Json)]` component (same policy as rift-java: a modeled key appearing
in `extra` is a construction error) so decode → encode round-trips fixtures byte-for-byte
semantically, and forward-compatible engine additions survive.

#### 5.1.2 `rift.model` — the wire model

Scala 3 enums + case classes, mirroring the engine's JSON exactly. The important shapes
(scala-fied from the verified rift-java 0.1.1 model, which round-trips the same wire format):

```scala
package rift.model

opaque type Port = Int          // 1..65535, Port.dynamic for engine-assigned
opaque type FlowId = String     // non-empty
opaque type StubId = String
opaque type ScenarioState = String // convenience; plain strings accepted everywhere

enum Protocol:
  case Http, Https

enum Method:
  case GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
  case Custom(name: String)

final case class ImposterDefinition(
  port: Option[Port]                 = None,   // None = engine assigns; explicit ports verbatim
  protocol: Protocol                 = Protocol.Http,
  name: Option[String]               = None,
  recordRequests: Boolean            = false,
  recordMatches: Boolean             = false,
  stubs: Vector[Stub]                = Vector.empty,
  defaultResponse: Option[IsResponse]= None,
  defaultForward: Option[String]     = None,
  allowCors: Boolean                 = false,
  strictBehaviors: Boolean           = false,
  tls: Option[TlsMaterial]           = None,   // cert + key PEM for https
  rift: Option[RiftConfig]           = None,   // the _rift extension block
  extra: Vector[(String, Json)]      = Vector.empty
)

final case class Stub(
  predicates: Vector[Predicate],
  responses: Vector[Response],                  // ≥ 1 enforced by the DSL, cycled round-robin
  name: Option[String]          = None,
  id: Option[StubId]            = None,
  routePattern: Option[String]  = None,         // "/users/:id" → ${request.pathParams.id}
  space: Option[FlowId]         = None,         // _rift correlated isolation
  scenario: Option[ScenarioRef] = None,         // scenarioName/required/new triplet
  extra: Vector[(String, Json)] = Vector.empty
)

final case class ScenarioRef(
  name: String,
  requiredState: Option[String],  // implicit engine initial state: "Started"
  newState: Option[String]
)

final case class Predicate(op: PredicateOp, params: PredicateParams = PredicateParams.empty)

enum PredicateOp:
  case Equals(fields: Fields)
  case DeepEquals(fields: Fields)
  case Contains(fields: Fields)
  case StartsWith(fields: Fields)
  case EndsWith(fields: Fields)
  case Matches(fields: Fields)
  case Exists(fields: Fields)
  case And(predicates: Vector[Predicate])
  case Or(predicates: Vector[Predicate])
  case Not(predicate: Predicate)
  case Inject(script: String)                   // JS, engine-gated by --allowInjection

final case class PredicateParams(
  caseSensitive: Boolean               = false,
  except: Option[String]               = None,
  selector: Option[PredicateSelector]  = None
)

enum PredicateSelector:
  case JsonPath(selector: String)
  case XPath(selector: String, namespaces: Map[String, String] = Map.empty)

enum Response:
  case Is(response: IsResponse, behaviors: Behaviors = Behaviors.empty,
          rift: Option[RiftResponseExt] = None, extra: Vector[(String, Json)] = Vector.empty)
  case Proxy(proxy: ProxyResponse, extra: Vector[(String, Json)] = Vector.empty)
  case Inject(script: String, extra: Vector[(String, Json)] = Vector.empty)
  case Fault(fault: TcpFaultKind, extra: Vector[(String, Json)] = Vector.empty)
  case RiftScript(rift: RiftResponseExt, extra: Vector[(String, Json)] = Vector.empty)

enum TcpFaultKind:
  case ConnectionResetByPeer, EmptyResponse, RandomDataThenClose, MalformedResponseChunk

final case class RiftResponseExt(
  fault: Option[FaultConfig]   = None,   // probabilistic latency/error/tcp
  script: Option[ScriptSource] = None,   // rhai | js, inline | file | ref
  templated: Boolean           = false   // ${request.*} interpolation
)

enum ScriptSource:
  case Inline(engine: ScriptEngine, code: String)
  case File(engine: ScriptEngine, path: String)
  case Ref(name: String)                 // resolves against ImposterDefinition-level registry

enum ScriptEngine:
  case Rhai, JavaScript
```

`Behaviors` models `wait` (fixed/range/inject/script), `decorate`, `copy`, `lookup` (CSV),
`shellTransform`, `repeat`, plus `Unknown(key, raw)` for forward compatibility. `RiftConfig`
models the imposter-level `_rift` block: `flowState` (backend inmemory/redis, `ttlSeconds`,
`flowIdSource`), `scriptEngine` (default engine + `timeoutMs`), named `scripts` registry,
`metrics`. `FaultConfig` models `latency {probability, ms | minMs..maxMs}`,
`error {probability, status, body, headers}`, `tcp` (bare or probabilistic — probabilistic
requires engine ≥ 0.13.2, documented on the method).

Admin-protocol data also lives here (pure data, used by every backend and testkit):

```scala
final case class RecordedRequest(
  method: Method, path: String, query: Map[String, Vector[String]],
  headers: Headers, body: Option[Json], bodyText: Option[String],
  timestamp: Instant, requestFrom: Option[String], flowId: Option[FlowId],
  pathParams: Map[String, String], raw: Json)

final case class EngineInfo(version: String, commit: String, features: Set[String])
final case class ApplyResult(created: Int, replaced: Int, stubPatched: Int,
                             deleted: Int, failed: Vector[Json])
final case class ScenarioStatus(name: String, state: String)

enum Times:
  case Exactly(n: Int)
  case AtLeast(n: Int)
  case AtMost(n: Int)
  case Between(min: Int, max: Int)
object Times:
  val once: Times         = Exactly(1)
  val never: Times        = Exactly(0)
  val atLeastOnce: Times  = AtLeast(1)
```

#### 5.1.3 `rift.dsl` — the builder grammar

One `import rift.dsl.*` brings in the whole grammar. Builders are immutable (each step returns
a new value) and **phantom-typed where invalid states are cheap to forbid** — the flagship
example: a stub without a response does not compile.

```scala
package rift.dsl

sealed trait StubPhase
object StubPhase:
  sealed trait Matching extends StubPhase   // predicates only, usable as a RequestMatch
  sealed trait Complete extends StubPhase   // has ≥ 1 response, accepted by .stub(...)

final class StubBuilder[S <: StubPhase] private[dsl] (...) extends RequestMatch:
  def where(predicate: PredicateBuilder): StubBuilder[S]
  def caseSensitive: StubBuilder[S]                             // sugar for caseSensitive(true)
  def caseSensitive(value: Boolean): StubBuilder[S]             // explicit false pins the key on the wire
  def except(jsonField: String): StubBuilder[S]
  def reply(response: ResponseBuilder): StubBuilder[StubPhase.Complete]
  def thenReply(response: ResponseBuilder)                      // response cycling
      (using S =:= StubPhase.Complete): StubBuilder[StubPhase.Complete]
  def named(name: String): StubBuilder[S]
  def withId(id: StubId): StubBuilder[S]
  def route(pattern: String): StubBuilder[S]                    // "/users/:id"
  def inSpace(flowId: FlowId): StubBuilder[S]
  def build(using S =:= StubPhase.Complete): Stub
```

The grammar, end to end:

```scala
import rift.dsl.*
import rift.model.{Method, TcpFaultKind}, Method.*

// ── request matching ────────────────────────────────────────────────────────
on(GET, "/api/users/1")                        // method + path equals
onRequest                                       // match anything, refine with .where
get("/health"); post("/api/users")             // per-method shorthands

on(GET, "/api/users/1")
  .where(header("Accept").contains("json"))
  .where(query("page").is("2"))
  .where(body.jsonPath("$.user.role").is("admin"))
  .where(path.matches("""/api/users/\d+"""))
  .where(not(header("X-Debug").exists))
  .where(anyOf(query("v").is("1"), query("v").is("2")))

// field selectors: method | path | query(name) | header(name) | body
// operators: .is (equals) | .deepEquals | .contains | .startsWith | .endsWith
//            | .matches(regex) | .exists | .notExists
// selectors: body.jsonPath("$.x") | body.xpath("//item", ns = Map("a" -> "urn:a"))
// combinators: allOf | anyOf | not — nest arbitrarily

// ── responses ───────────────────────────────────────────────────────────────
ok                                              // 200, empty body
ok.json("""{"id":1}""")                        // raw JSON string (validated at build)
ok.json(user)                                   // any A with a JsonBody[A] instance
ok.text("pong")
created; accepted; noContent; notFound; badRequest
status(503).header("Retry-After", "30").json("""{"error":"unavailable"}""")
ok.binary(bytes)                                // _mode: binary, base64 on the wire
ok.json("""{"path":"${request.path}"}""").templated

// behaviors (Mountebank _behaviors)
ok.after(150.millis)                            // wait, fixed
ok.afterBetween(100.millis, 500.millis)         // wait, range
ok.decorate(js)                                 // JS decorate
ok.copy(from = path, into = "${id}", using = regex("""\d+"""))
ok.lookup(key = copyFrom(query("user")), csv = "users.csv", keyColumn = "id", into = "${row}")
ok.repeat(2)                                    // return this response twice before cycling

// _rift probabilistic faults (composable with any is-response)
ok.withLatencyFault(probability = 1.0, 500.millis to 2.seconds)
ok.withLatencyFault(probability = 0.5, 1.second)
ok.withErrorFault(probability = 0.3, status = 503, body = """{"error":"flaky"}""")
ok.withErrorFault(0.3, 503, "DOWN", headers = Map("Retry-After" -> "30")) // _rift.fault.error.headers
ok.withTcpFault(TcpFaultKind.ConnectionResetByPeer)               // always
ok.withTcpFault(probability = 0.1, TcpFaultKind.ConnectionResetByPeer)

// other response kinds
proxyTo("https://real-api.example.com").proxyOnce
  .generateBy(RequestField.Method, RequestField.Path)
fault(TcpFaultKind.ConnectionResetByPeer)       // Mountebank transport fault
inject("function (request) { return { statusCode: 200 }; }")
script(Script.rhai("fn respond(ctx) { http(200, #{ok: true}) }"))
script(Script.rhaiFile("checkout.rhai")); script(Script.ref("checkout"))

// ── stubs & cycling ─────────────────────────────────────────────────────────
on(GET, "/api/resource")
  .reply(status(503).header("Retry-After", "1"))
  .thenReply(status(503))
  .thenReply(ok.json("""{"ok":true}"""))        // 503, 503, 200, 200, ...

// ── stateful scenarios (FSM) ────────────────────────────────────────────────
scenario("checkout")
  .startingAt("Started")
  .when("Started", on(POST, "/pay")).respond(ok.json("""{"paid":true}""")).goTo("paid")
  .when("paid", on(GET, "/receipt")).respond(ok.json("""{"receipt":"r-1"}"""))
  .stubs                                        // Vector[Stub]

// ── imposters ───────────────────────────────────────────────────────────────
imposter("users")
  .port(4545)                                   // omit → engine assigns
  .record                                       // recordRequests = true
  .https(certPem, keyPem)
  .defaultResponse(notFound.text("no stub matched"))
  .strictBehaviors
  .flowState(inMemoryFlowState.ttl(5.minutes).flowIdFromHeader("X-Flow-Id"))
  .flowState(redisFlowState("redis://localhost:6379").keyPrefix("rift:"))
  .scriptEngine(ScriptEngine.Rhai, timeout = 2.seconds)
  .script("checkout", Script.rhaiFile("checkout.rhai"))
  .stub(on(GET, "/api/users/1").reply(ok.json("""{"id":1}""")))
  .stubs(scenario("checkout").startingAt("Started")./*...*/.stubs)

// ── escape hatches (issue #2: explicit requirement) ─────────────────────────
imposterFromJson("""{ "port": 4545, "protocol": "http", ... }""")  // Either[JsonError, ImposterBuilder]
stubFromJson("""{ "predicates": [...], "responses": [...] }""")
Json.parse(raw)                                                    // drop to the AST anywhere
```

Duration sugar (`150.millis`, `2.seconds`) comes from `scala.concurrent.duration` — stdlib,
no dependency. `RequestMatch` is the verification vocabulary: any `StubBuilder[_]` (its
predicate part) is a `RequestMatch`, so **the same expression stubs and verifies**:

```scala
val lookup = on(GET, "/api/users/1").where(header("Accept").contains("json"))
imposter("users").stub(lookup.reply(ok.json("""{"id":1}""")))
// later:
api.verify(lookup, times = 1)
```

#### 5.1.4 Client-side matcher (diff engine for testkits)

`rift.model.matching.RequestMatcher` evaluates a `RequestMatch` against a `RecordedRequest`
purely — used by all testkits to explain verification failures (D5):

```scala
object RequestMatcher:
  def evaluate(m: RequestMatch, r: RecordedRequest): MatchResult
  // MatchResult = Matched | Missed(failures: Vector[PredicateFailure])
  // PredicateFailure(predicate, field, expected, actual)
  def explain(m: RequestMatch, recorded: Vector[RecordedRequest]): VerificationReport
```

`VerificationReport` renders the near-miss table (per non-matching request: which predicate
failed, expected vs actual). It is best-effort — the engine's verdict (via the `verify`
endpoint) is authoritative; the matcher only explains.

#### 5.1.5 `JsonBody[A]` typeclass

```scala
package rift.model

trait JsonBody[A]:
  def encode(a: A): Json
  def decode(json: Json): Either[String, A]
```

Used by `ok.json(a: A)`, `RecordedRequest.bodyAs[A]`, and predicate builders
(`body.deepEquals(a)`). Instances come from the side-car artifacts (5.5, 5.9) or are
hand-written. `model` itself provides instances only for `Json`, `String`, and primitives.

**Acceptance (unchanged from issue #2):** decode→encode round-trips `rift/examples/*.json`
and all corpus fixtures under `semanticEquals`; every corpus fixture is expressible via the
DSL (verified fixture-by-fixture in the conformance module).

---

### 5.2 `rift-scala-bridge` — the blocking connector *(issue #3)*

Depends on `model` and `rift-java-core` (compile scope). Everything below the effect modules;
**no FFM, no HTTP, no native resolution in Scala** — all delegated to rift-java.

#### Error ADT (D3)

```scala
package rift

import scala.util.control.NoStackTrace

enum RiftError(message: String) extends Exception(message), NoStackTrace:
  case InvalidDefinition(message: String, cause: Option[Throwable])
  case EngineUnavailable(message: String, cause: Option[Throwable])
  case CommunicationError(message: String, cause: Option[Throwable])
  case ImposterNotFound(port: Port)
  case EngineError(code: Int, message: String)
  case VerificationFailed(report: VerificationReport)
  case DecodeFailed(message: String, payload: Option[Json])

object RiftError:
  /** Total mapping from the rift-java boundary. Anything unrecognized stays a defect. */
  def fromThrowable(t: Throwable): Option[RiftError]
```

Mapping: `InvalidDefinition|EngineUnavailable|CommunicationError|ImposterNotFound|EngineError`
1:1 from the sealed `RiftException`; `VerificationException` (which `extends AssertionError` and
carries a **structured** `result(): Optional[VerificationResult]` — `matched`, `total`,
`satisfied`, `requests`, and `closest` with per-predicate `failedPredicates`, *not* message-only)
→ `VerificationFailed`;
`JsonError.Decode` on response parsing → `DecodeFailed`. Because `RiftError` *is* an
`Exception`: ZIO uses `refineToOrDie[RiftError]`, Cats raises it directly, Kyo's
`Abort[RiftError]` and `pure`'s `Either[RiftError, A]` both hold the same values.

#### Connector

```scala
package rift.bridge

/** Blocking, throwing (RiftError), thread-safe. One instance per engine. */
final class RiftConnector private (underlying: JRift /* io.github.achirdlabs.rift.Rift */)
    extends AutoCloseable:
  def create(definition: ImposterDefinition): ImposterConnector   // JSON via Rift.create(String)
  def imposter(port: Port): ImposterConnector                     // facade Rift.imposter(int): Optional[Imposter]; bridge maps empty → ImposterNotFound
  def imposters(): Vector[ImposterConnector]
  def deleteAll(): Unit
  def replaceAll(definitions: Vector[ImposterDefinition]): Unit
  def applyConfig(config: Json): ApplyResult
  def info(): EngineInfo
  def adminUri: URI
  def intercept(options: InterceptConfig): InterceptConnector     // at most one per engine
  def close(): Unit

object RiftConnector:
  def embedded(config: EmbeddedConfig = EmbeddedConfig()): RiftConnector
  def connect(config: ConnectConfig): RiftConnector
  def spawn(config: SpawnConfig = SpawnConfig()): RiftConnector
  def container(config: ContainerConfig = ContainerConfig()): RiftConnector   // optional transport
  // container wraps rift-java's RiftContainer from the optional rift-java-testcontainers artifact;
  // absence from the classpath fails with EngineUnavailable naming the missing artifact.
```

`ImposterConnector` mirrors `rift.zio.ImposterHandle` (5.3) 1:1 but blocking/throwing —
stubs CRUD (`addStub`, `addStubFirst`, `replaceStubs`, `stub(id)`), `recorded([match])`,
`clearRecorded`, `verify(match, times)`, `verifyNoInteractions`, `scenarios`, `space(flowId)`,
`flowState(flowId)`, `startRecording(origin, spec)`, `enable/disable/delete`. All effect
modules and `pure` are thin wrappers over these two types, so behavior can never diverge
between backends.

Config case classes are Scala-idiomatic mirrors of rift-java's builder-style option types —
`ConnectConfig` maps onto `ConnectOptions.builder()…`, `EmbeddedConfig` onto `EmbeddedOptions`,
and `SpawnConfig` onto `SpawnOptions` (`ContainerConfig` configures the optional testcontainers
`RiftContainer`) — with the same defaults (`ConnectConfig(adminUri, apiKey, requestTimeout =
30.seconds, versionCheck = VersionCheck.Fail, hostResolver)`, `EmbeddedConfig(libraryPath,
adminHost = "127.0.0.1", adminPort = 0, serveAdminEagerly = false, apiKey)`,
`SpawnConfig(binaryPath, version, host, adminPort, allowInjection, localOnly, logLevel, env,
workingDir, mirrorUrl, startupTimeout = 15.seconds, shutdownTimeout = 5.seconds, inheritLog)`,
`ContainerConfig(image, imposterPorts, apiKey, gateway, interceptPort)`). The facade's
`SpawnOptions.version()` defaults to the **live** `RiftVersion.engineVersion()` (not a static
literal), so a `SpawnConfig` that leaves `version` unset spawns the engine pinned by this build.

Verification detail (D5): `verify` calls the facade; on `VerificationException` the bridge reads
its structured `result()` (`requests` for the matched calls, `closest` with `failedPredicates`
for the near-misses) to build the typed `VerificationReport` carried inside
`RiftError.VerificationFailed` — every backend gets rich diffs for free. Per-item decode failures
degrade to a partial report (never a wrong error case); the engine's `verify` stays authoritative.

```scala
object RiftVersions:
  val riftScala: String   // this build
  val riftJava: String    // pinned, e.g. "0.1.3"
  val engine: String      // transitively pinned, e.g. "0.15.0" (via rift-version.properties)
```

**Embedded runtime requirements** (documented, and validated with a clear
`EngineUnavailable` message): a `rift-java-embedded` (JDK 22+) or `rift-java-embedded-jdk21`
artifact plus a `rift-java-natives` classifier jar on the runtime classpath, and
`--enable-native-access=ALL-UNNAMED` (JDK 21: also `--enable-preview`). Recommended sbt:

```scala
libraryDependencies ++= Seq(
  "io.github.achird-labs" %% "rift-scala-zio"     % riftScalaV % Test,
  "io.github.achird-labs"  % "rift-java-embedded" % riftJavaV  % Test,
  "io.github.achird-labs"  % "rift-java-natives"  % riftJavaV  % Test
    classifier RiftNatives.currentClassifier  // helper: os.name + os.arch → classifier
)
Test / fork := true
Test / javaOptions += "--enable-native-access=ALL-UNNAMED"
```

---

### 5.3 `rift-scala-zio` *(issue #4)*

Service Pattern 2.0. Typed errors everywhere; blocking downcalls in `ZIO.attemptBlocking`
refined with `refineToOrDie[RiftError]` (anything else is a defect, as it should be).

```scala
package rift.zio

import _root_.zio.*
import _root_.zio.stream.ZStream

trait Rift:
  def create(definition: ImposterDefinition): IO[RiftError, ImposterHandle]
  def create(builder: ImposterBuilder): IO[RiftError, ImposterHandle]
  def createFromJson(json: String): IO[RiftError, ImposterHandle]
  def imposter(port: Port): IO[RiftError, ImposterHandle]
  def imposters: IO[RiftError, Chunk[ImposterHandle]]
  def deleteAll: IO[RiftError, Unit]
  def replaceAll(definitions: Chunk[ImposterDefinition]): IO[RiftError, Unit]
  def applyConfig(config: Json): IO[RiftError, ApplyResult]
  def info: IO[RiftError, EngineInfo]
  def adminUri: UIO[URI]
  def intercept(config: InterceptConfig = InterceptConfig()): ZIO[Scope, RiftError, InterceptHandle]

trait ImposterHandle:
  def port: Port
  def uri: URI                                   // base URI of the mock endpoint
  def definition: IO[RiftError, ImposterDefinition]
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]
  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]
  def replaceStubs(stubs: Chunk[Stub]): IO[RiftError, Unit]
  def stubs: IO[RiftError, Chunk[Stub]]
  def stub(id: StubId): IO[RiftError, StubRef]
  def recorded: IO[RiftError, Chunk[RecordedRequest]]
  def recorded(matching: RequestMatch): IO[RiftError, Chunk[RecordedRequest]]
  def clearRecorded: IO[RiftError, Unit]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit]
  def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit]  // README sugar
  def verifyNoInteractions: IO[RiftError, Unit]
  def requests: ZStream[Any, RiftError, RecordedRequest]               // 100ms poll
  def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest]
  def scenarios: Scenarios
  def space(flowId: FlowId): SpaceHandle
  def flowState(flowId: FlowId): FlowStateHandle
  def startRecording(origin: URI, spec: RecordSpec = RecordSpec()): ZIO[Scope, RiftError, RecordingHandle]
  def enable: IO[RiftError, Unit]
  def disable: IO[RiftError, Unit]
  def delete: IO[RiftError, Unit]

object Rift:
  // ── layers (all scoped; engine torn down on release) ──────────────────────
  val embedded: ZLayer[Any, RiftError, Rift]
  def embedded(config: EmbeddedConfig): ZLayer[Any, RiftError, Rift]
  def connect(adminUri: URI): ZLayer[Any, RiftError, Rift]
  def connect(config: ConnectConfig): ZLayer[Any, RiftError, Rift]
  def spawn(config: SpawnConfig = SpawnConfig()): ZLayer[Any, RiftError, Rift]
  def container(config: ContainerConfig = ContainerConfig()): ZLayer[Any, RiftError, Rift]

  // ── environment accessors (thin ZIO.serviceWithZIO delegates, for test DX) ─
  def create(builder: ImposterBuilder): ZIO[Rift, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](_.create(builder))
  def deleteAll: ZIO[Rift, RiftError, Unit] = ZIO.serviceWithZIO[Rift](_.deleteAll)
  // ... one per Rift method
```

Layer implementation is the standard scoped shape:

```scala
private[zio] final case class RiftLive(connector: RiftConnector) extends Rift:
  private def io[A](op: => A): IO[RiftError, A] =
    ZIO.attemptBlocking(op).refineToOrDie[RiftError]
  // every method: io(connector.<op>) mapped into handles

object Rift:
  val embedded: ZLayer[Any, RiftError, Rift] =
    ZLayer.scoped:
      ZIO.fromAutoCloseable(
        ZIO.attemptBlocking(RiftConnector.embedded()).refineToOrDie[RiftError]
      ).map(RiftLive(_))
```

The request tail (D6) — **cursor-tracking** poll over the engine's stable journal index
(`recordedPage()` baselines, `recordedSince(cursor)` fetches strictly-newer), chunk-friendly,
ends only by interruption:

```scala
def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest] =
  ZStream.unfoldChunkZIO(Option.empty[Long]): cursor =>       // held cursor, not an array offset
    page(cursor)                                              // recordedPage() | recordedSince(cursor)
      .map: p =>                                              // RecordedPage(requests, nextIndex, truncated)
        // nextIndex absent → engine exposes no stable index (older engine / degraded read):
        // hold the cursor and keep polling — never fall back to array offsets (silent loss).
        // truncated → retention evicted unseen entries: the one real loss case, signalled not hidden.
        Some((Chunk.fromIterable(p.requests), p.nextIndex.orElse(cursor)))
      .tap(_ => ZIO.sleep(pollEvery))
```

A client-side `all.drop(offset)` cannot be exactly-once: journal positions shift under the 10k
`pop_front` eviction, `DELETE savedRequests`, and scoped clears, so an offset tail silently skips
or replays entries with no way to notice. The server-assigned cursor survives clears, makes the
one genuine loss case (`truncated`) explicit, and advances past `match=`-rejected entries so a
filtered tail never re-scans.

Sub-APIs are records of effects, mirroring the engine's `_rift` admin routes:

```scala
trait Scenarios:
  def list: IO[RiftError, Chunk[ScenarioStatus]]
  def list(flowId: FlowId): IO[RiftError, Chunk[ScenarioStatus]]
  def state(name: String): IO[RiftError, String]
  def setState(name: String, state: String, flowId: Option[FlowId] = None): IO[RiftError, Unit]
  def reset(flowId: Option[FlowId] = None): IO[RiftError, Unit]

trait SpaceHandle:
  def flowId: FlowId
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]
  def stubs: IO[RiftError, Chunk[Stub]]
  def recorded: IO[RiftError, Chunk[RecordedRequest]]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit]
  def delete: IO[RiftError, Unit]

trait FlowStateHandle:
  def get(key: String): IO[RiftError, Option[Json]]
  def put(key: String, value: Json): IO[RiftError, Unit]
  def delete(key: String): IO[RiftError, Unit]
  def clear: IO[RiftError, Unit]

trait InterceptHandle:
  def proxyUri: URI
  def rule(host: String): InterceptRuleBuilder      // .when(match).serve(resp) | .forwardTo(port) | .redirectTo(imposter)
  def rule(): InterceptRuleBuilder                  // all-hosts (catch-all) form — matches every intercepted host
  def rules: IO[RiftError, Chunk[InterceptRule]]
  def clearRules: IO[RiftError, Unit]
  def caPem: IO[RiftError, String]
  def sslContext: IO[RiftError, SSLContext]         // trust material for the SUT's client
  def exportTruststore(format: TruststoreFormat, password: String, path: Path): IO[RiftError, Unit]
```

The canonical spec (from the README, now fully grounded):

```scala
import rift.dsl.*
import rift.zio.*
import zio.*, zio.test.*

object PaymentsSpec extends ZIOSpecDefault:
  val users = imposter("users").record
    .stub(on(GET, "/api/users/1").reply(ok.json("""{"id":1}""")))

  def spec = suite("payments")(
    test("records the lookup"):
      for
        api <- Rift.create(users)
        _   <- callSut(api.uri)
        _   <- api.verify(on(GET, "/api/users/1"), times = 1)
      yield assertCompletes
  ).provideShared(Rift.embedded)
```

---

### 5.4 `rift-scala-zio-testkit` *(issue #5)*

zio-test glue. Three concerns: engine sharing, per-test isolation, readable assertions.

```scala
package rift.zio.testkit

object RiftTestKit:
  // Engine layers — aliases with test-tuned defaults (embedded, dynamic ports).
  // Use with .provideShared / .provideSomeShared to share one engine per suite/JVM.
  val embedded: ZLayer[Any, RiftError, Rift]
  def fromEnv: ZLayer[Any, RiftError, Rift]
  // RIFT_ADMIN_URL set → connect; otherwise embedded. One-line CI/local switch.

  /** Imposter scoped to the enclosing test — deleted when the test's Scope closes. */
  def imposter(builder: ImposterBuilder): ZIO[Rift & Scope, RiftError, ImposterHandle]

  /** Space scoped to the test — for correlated isolation on a shared imposter. */
  def space(handle: ImposterHandle): ZIO[Scope, RiftError, SpaceHandle]  // fresh FlowId per test

object assertions:
  /** Engine-side verify; on failure renders the RequestMatcher near-miss table. */
  def assertReceived(handle: ImposterHandle, matching: RequestMatch,
                     times: Times = Times.atLeastOnce): UIO[TestResult]
  def assertNoInteractions(handle: ImposterHandle): UIO[TestResult]
  /** Await until `times` is satisfied or timeout — for async SUTs. Poll + Schedule. */
  def eventuallyReceived(handle: ImposterHandle, matching: RequestMatch, times: Times,
                         timeout: Duration = 5.seconds): UIO[TestResult]

object aspects:
  /** Provision an imposter for every test in the suite; handle via RiftTest.imposter(name). */
  def withImposter(name: String)(builder: ImposterBuilder): TestAspectAtLeastR[Rift]
  /** Wrap every stub of every provisioned imposter with a latency fault — chaos aspect. */
  def withLatency(min: Duration, max: Duration, probability: Double = 1.0): TestAspectAtLeastR[Rift]
  /** Reset all scenarios + recorded requests after each test (shared-imposter hygiene). */
  val resetAfterEach: TestAspectAtLeastR[Rift]
```

`withImposter` follows zio-test's environment idiom: the aspect provisions in `before`,
tears down in `after`, and publishes the handle in a `FiberRef`-backed registry readable via
`RiftTest.imposter(name): IO[RiftError, ImposterHandle]` — mirroring the ergonomics zio-bdd
users know from its fixture tags.

Failure output contract (the "readable diffs" acceptance): a failed `assertReceived` prints

```
✗ expected GET /api/users/1 with header Accept containing "json" — exactly 1 time, got 0
  3 recorded requests, closest matches:
  #2 GET /api/users/1        header Accept: expected contains "json", was "text/html"
  #1 GET /api/users/2        path: expected "/api/users/1", was "/api/users/2"
  #0 POST /api/users         method: expected GET, was POST
```

---

### 5.5 `rift-scala-zio-json` *(issue #16)*

One file: `given [A](using JsonCodec[A]): JsonBody[A]` bridging zio-json to the model
typeclass, plus `Json <-> zio.json.ast.Json` converters. Lets ZIO users write
`ok.json(User(1, "Alice"))` and `recorded.bodyAs[User]` with their existing codecs.

### 5.9 `rift-scala-circe` *(issue #17)*

Same shape for circe: `given [A](using Encoder[A], Decoder[A]): JsonBody[A]`,
`Json <-> io.circe.Json` converters.

---

### 5.6 `rift-scala-cats` *(issue #8)*

Tagless over `Async` (this *is* shared library code — the CE ecosystem convention), `Resource`
lifecycles, errors raised as `RiftError` (it is an `Exception` — D3 pays off: no wrapper).

```scala
package rift.cats

import _root_.cats.effect.{Async, Resource}

trait Rift[F[_]]:
  def create(definition: ImposterDefinition): F[ImposterHandle[F]]
  def create(builder: ImposterBuilder): F[ImposterHandle[F]]
  def createFromJson(json: String): F[ImposterHandle[F]]
  def imposter(port: Port): F[ImposterHandle[F]]
  def imposters: F[Vector[ImposterHandle[F]]]
  def deleteAll: F[Unit]
  def replaceAll(definitions: Vector[ImposterDefinition]): F[Unit]
  def applyConfig(config: Json): F[ApplyResult]
  def info: F[EngineInfo]
  def adminUri: F[URI]
  def intercept(config: InterceptConfig = InterceptConfig()): Resource[F, InterceptHandle[F]]

trait ImposterHandle[F[_]]:
  // same surface as the ZIO handle, F[_]-shaped; startRecording returns Resource
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): F[Unit]
  def startRecording(origin: URI, spec: RecordSpec = RecordSpec()): Resource[F, RecordingHandle[F]]
  // ...

object Rift:
  def embedded[F[_]: Async]: Resource[F, Rift[F]]
  def embedded[F[_]: Async](config: EmbeddedConfig): Resource[F, Rift[F]]
  def connect[F[_]: Async](adminUri: URI): Resource[F, Rift[F]]
  def connect[F[_]: Async](config: ConnectConfig): Resource[F, Rift[F]]
  def spawn[F[_]: Async](config: SpawnConfig = SpawnConfig()): Resource[F, Rift[F]]
  def container[F[_]: Async](config: ContainerConfig = ContainerConfig()): Resource[F, Rift[F]]
```

Implementation: every op is `Sync[F].blocking(connector.op)` — the typed failure travels as
the raised `RiftError`; callers recover with `recoverWith { case RiftError.ImposterNotFound(p) => ... }`.
Construction: `Resource.fromAutoCloseable(Sync[F].blocking(RiftConnector.embedded(config))).map(...)`.

```scala
// munit-cats-effect flavored usage (fixtures in 5.8)
Rift.embedded[IO].use { rift =>
  for
    api <- rift.create(imposter("users").record
             .stub(on(GET, "/api/users/1").reply(ok.json("""{"id":1}"""))))
    _   <- callSut(api.uri)
    _   <- api.verify(on(GET, "/api/users/1"), times = 1)
  yield ()
}
```

cats-core/cats-effect never leak into other modules (only `cats`, `cats-testkit`, `fs2`
depend on them).

---

### 5.7 `rift-scala-fs2` *(issue #9)*

Streaming tail + verification pipes on top of the cats module.

```scala
package rift.fs2

import _root_.fs2.{Pipe, Stream}

object syntax:
  extension [F[_]: Temporal](handle: ImposterHandle[F])
    /** Cursor-tracking poll (`recordedSince` over the stable journal index); emits each request
      * exactly once. `nextIndex` absent → hold the cursor; `truncated` → re-baseline is signalled. */
    def requestStream(pollEvery: FiniteDuration = 100.millis): Stream[F, RecordedRequest]

object pipes:
  /** Keep only requests matching m (client-side matcher). */
  def matching[F[_]](m: RequestMatch): Pipe[F, RecordedRequest, RecordedRequest]

/** Complete when `count` matching requests have been seen, fail on timeout.
  * The await-n-requests idiom for async SUTs. */
def awaitRequests[F[_]: Temporal](handle: ImposterHandle[F], matching: RequestMatch,
                                  count: Int, timeout: FiniteDuration = 5.seconds)
    : F[Vector[RecordedRequest]] =
  handle.requestStream().through(pipes.matching(matching))
    .take(count).compile.toVector.timeout(timeout)
```

Adopting the cursor is an internal swap — `requestStream` signatures stay. The engine's SSE
`/events` stream (rift#461) is a *separate*, richer surface (imposter lifecycle + `lagged`
events) tracked as an additive event stream, not a transparent replacement for this
`Stream[F, RecordedRequest]`.

---

### 5.8 `rift-scala-cats-testkit` *(issue #10)*

Glue for **munit-cats-effect** and **weaver** (both `% Optional` — users pull the one they
use). The assertion core (diff rendering via `RequestMatcher`) is shared internally.

```scala
package rift.cats.testkit.munit

/** One embedded engine per suite, imposters per test. */
trait RiftSuite extends CatsEffectSuite:
  /** Override to switch transport (e.g. RiftTransports.fromEnv for CI). */
  protected def riftResource: Resource[IO, Rift[IO]] = Rift.embedded[IO]

  protected val rift: Fixture[Rift[IO]] =
    ResourceSuiteLocalFixture("rift", riftResource)
  override def munitFixtures = List(rift)

  /** Test-scoped imposter: created before the body, deleted after. */
  def withImposter(builder: ImposterBuilder)(body: ImposterHandle[IO] => IO[Unit]): IO[Unit]

  def assertReceived(handle: ImposterHandle[IO], matching: RequestMatch,
                     times: Times = Times.atLeastOnce): IO[Unit]  // fails with rendered diff
```

```scala
package rift.cats.testkit.weaver

/** Share one engine across suites via weaver's GlobalResource. */
object RiftGlobal extends GlobalResource:
  def sharedResources(global: GlobalWrite): Resource[IO, Unit] =
    Rift.embedded[IO].flatMap(global.putR(_))

/** Per-suite convenience: Res = Rift[IO]. */
abstract class RiftIOSuite(global: GlobalRead) extends IOSuite:
  type Res = Rift[IO]
  def sharedResource: Resource[IO, Rift[IO]] =
    global.getOrFailR[Rift[IO]]()

// plus expectations: expectReceived(handle, matching, times): IO[Expectations]
```

---

### 5.10 `rift-scala-kyo` *(issue #11)*

The same surface in Kyo idiom — ops are pending-effect values, lifecycle owned by Kyo's
resource effect. (Kyo 1.x names: `Sync` for suspended side effects, `Scope` for resources —
`Resource` in pre-1.0 releases; pin whichever is current at implementation time and record it
in the module README.)

```scala
package rift.kyo

import _root_.kyo.*

final class Rift private[kyo] (connector: RiftConnector):
  def create(definition: ImposterDefinition): ImposterHandle < (Sync & Abort[RiftError])
  def create(builder: ImposterBuilder): ImposterHandle < (Sync & Abort[RiftError])
  def imposter(port: Port): ImposterHandle < (Sync & Abort[RiftError])
  def deleteAll: Unit < (Sync & Abort[RiftError])
  // ... same surface

object Rift:
  def embedded(config: EmbeddedConfig = EmbeddedConfig()): Rift < (Sync & Abort[RiftError] & Scope)
  def connect(config: ConnectConfig): Rift < (Sync & Abort[RiftError] & Scope)
  def spawn(config: SpawnConfig = SpawnConfig()): Rift < (Sync & Abort[RiftError] & Scope)
```

Implementation: `Abort.catching[RiftError](connector.op)` inside `Sync.defer`; acquisition via
`Scope.acquireRelease`. Direct-style usage:

```scala
import kyo.*

val program: Unit < (Async & Abort[RiftError] & Scope) =
  for
    rift <- Rift.embedded()
    api  <- rift.create(imposter("users").record
              .stub(on(GET, "/api/users/1").reply(ok.json("""{"id":1}"""))))
    _    <- callSut(api.uri)
    _    <- api.verify(on(GET, "/api/users/1"), times = 1)
  yield ()
```

---

### 5.11 `rift-scala-pure` *(issue #12)*

No effect system. The simplest reference implementation of the surface and the entry drug for
plain-Scala (and Java-adjacent) users. Also the natural glue for scalatest/JUnit users.

```scala
package rift.pure

final class Rift private (connector: RiftConnector) extends AutoCloseable:
  def create(definition: ImposterDefinition): Either[RiftError, Imposter]
  def create(builder: ImposterBuilder): Either[RiftError, Imposter]
  def imposter(port: Port): Either[RiftError, Imposter]
  def deleteAll(): Either[RiftError, Unit]
  // ... same surface, Either-shaped; Imposter mirrors ImposterHandle
  def close(): Unit

object Rift:
  def embedded(config: EmbeddedConfig = EmbeddedConfig()): Either[RiftError, Rift]
  def connect(config: ConnectConfig): Either[RiftError, Rift]
  /** Throwing variants (RiftError is an Exception) — for scala.util.Using. */
  def embeddedUnsafe(config: EmbeddedConfig = EmbeddedConfig()): Rift
  def connectUnsafe(config: ConnectConfig): Rift
```

```scala
import scala.util.Using

Using.resource(Rift.embeddedUnsafe()) { rift =>
  val api = rift.create(imposter("users").stub(
    on(GET, "/api/users/1").reply(ok.json("""{"id":1}""")))).fold(throw _, identity)
  callSut(api.uri)
  api.verify(on(GET, "/api/users/1"), times = 1).fold(throw _, identity)
}
```

---

### 5.12 `rift-scala-zio-bdd` — `MockControl` adapter *(issue #18)*

zio-bdd already ships two bespoke Rift adapters (container + embedded-FFM). This module lets
zio-bdd (and any library built on its `MockControl` SPI) run on rift-scala instead — one
maintained bridge, full capability surface, and rift-scala's conformance guarantees.

```scala
package rift.ziobdd

import _root_.zio.bdd.mock.*

object RiftScalaBackend:
  /** Wrap an already-provided rift.zio.Rift service. */
  val fromService: URLayer[rift.zio.Rift, MockControl]
  // convenience stacks
  def embedded(isolation: Isolation = Isolation.PerInstance): ZLayer[Any, MockError, MockControl]
  def connect(config: ConnectConfig, isolation: Isolation): ZLayer[Any, MockError, MockControl]
```

Design points:

- `capabilities = Set(Faults, StatefulScenarios, StateInspection, Scripting, ProxyRecord,
  Templating, Intercept)` — the full enum; rift is the reference backend.
- Portable-model mapping is mechanical and total: `MockRule(RequestMatch(PathMatch/ValueMatch/
  BodyMatch), ResponseDef, Priority)` → `rift.dsl` stub (Priority.Overlay → `addStubFirst`);
  `MockSource.Json/Resource/File/Dir` → `createFromJson`; `NativeSpec[Backend.Rift]` →
  `createFromJson` verbatim.
- `Isolation.Correlated` maps to spaces + `flowIdFromHeader`; `MockSpace.inject` carries the
  flow-id header, exactly like zio-bdd's own `RiftCorrelatedSpace`.
- Errors: `RiftError → MockError` is a total mapping (`EngineUnavailable → ProvisionFailed`,
  `ImposterNotFound/SpaceNotFound`, `InvalidDefinition → InvalidDefinition`,
  `CommunicationError/EngineError → CommunicationError`).
- Validation: run zio-bdd's published conformance scenario sets (`CoreConformanceScenarios`,
  `FaultScenarios`, `ScriptingScenarios`, `TemplatingScenarios`) against this adapter in CI.

This is also the worked example for the design goal "usable for building other libraries":
the adapter uses only public rift-scala API, and its source doubles as documentation for
anyone integrating rift-scala into another test framework.

---

### 5.13 `conformance` — unpublished gate module *(issues #6, #13)*

sbt module `conformance` (`publish / skip := true`), depends on `zio`, `cats`, `pure`,
`zio-testkit`.

- **Corpus acquisition:** sbt task `conformance / fetchCorpus` downloads
  `sdk-conformance-<engine>.tar.gz` for `RiftVersions.engine` from the rift release, caches
  under `~/.cache/rift-scala/`, unpacks to `conformance/target/corpus`. CI-friendly and
  version-locked by construction (contract rule 5).
- **Gate 1 — codec round-trip (automatic):** for every fixture in `manifest.json`:
  `Json.parse → ImposterDefinition.fromJson → .toJson → semanticEquals(original)`.
- **Gate 2 — DSL expressibility (hand-authored):** each fixture is re-authored through
  `rift.dsl` in `conformance/src/.../fixtures/Fixture01BasicRest.scala` etc., serialized and
  `semanticEquals`-compared against the corpus JSON. A new corpus fixture that cannot be
  expressed fails compilation or comparison — the red-build the contract demands.
- **Gate 3 — replay:** serve each fixture and replay `_verify.sequence[]` (`request` →
  `expect {status, bodyContains}`) with a plain JDK HTTP client, honoring `requires` skips.
  Runs through **both** the ZIO surface (M3 gate, issue #6) and the cats surface (M4 gate,
  issue #13), over **embedded and connect** transports.
- **Parity table (issue #13):** a generated markdown table (one row per `Rift`/handle
  operation, one column per backend) emitted from a shared op-inventory test, committed to
  `docs/PARITY.md`; CI fails if regeneration differs — backends cannot silently drift.

---

## 6. Developer experience

### Quick starts (the four front doors)

Each backend README leads with a complete, copy-pasteable test. ZIO and cats versions appear
in 5.3/5.8 above; Kyo in 5.10; pure in 5.11. Shared properties:

- **Two imports** get you everything: `import rift.dsl.*` + the backend
  (`rift.zio.*` / `rift.cats.*` / `rift.kyo.*` / `rift.pure.*`).
- **Same nouns everywhere**: `Rift`, `ImposterHandle`/`Imposter`, `StubRef`, `RequestMatch`,
  `Times`, `RiftError`. Learning transfers across backends; so do stubs, since they're all
  `rift.model` values.
- **Stub once, verify with the same expression** (`RequestMatch` unification).
- **Errors are values with rendered reports** — `RiftError.VerificationFailed(report)`
  pretty-prints the near-miss table in every framework.

### Failure-message philosophy

Every failure a user can hit in the first five minutes has a curated message:

- missing embedded backend → `EngineUnavailable` naming the exact artifacts to add
  (`rift-java-embedded` + natives classifier for the current OS/arch) and the JVM flags;
- engine/SDK version skew → the preflight message includes both versions and the floor;
- verification failure → near-miss table (5.4);
- JSON escape-hatch typos → `DecodeFailed` with a JSON path to the offending key.

### For library authors (the zio-bdd lesson, generalized)

Integrating rift-scala into another framework needs exactly three ingredients, all public:

1. a **lifecycle** primitive (`ZLayer` / `Resource` / `AutoCloseable`) — pick your module;
2. the **pure model** (`rift.model`, `rift.dsl`) for specs and matching — no effect dep, so
   even a Future-based or synchronous framework can build DSL surfaces on it;
3. the **matcher/report** types for assertions integration.

The zio-bdd adapter (5.12) and the two testkits are reference implementations of this recipe.

---

## 7. CI, build, and publishing

- **CI matrix** (extends the existing workflow): JDK 21 job — compile, scalafmt, unit tests,
  conformance over `connect` (service container `zainalpour/rift-proxy:v<engine>`); JDK 22
  job — everything plus embedded-transport conformance (`rift-java-embedded` + natives,
  `--enable-native-access=ALL-UNNAMED`). Optionally a third job proving
  `rift-java-embedded-jdk21` + `--enable-preview` works on 21.
- **Dependency pins** live in `project/Dependencies.scala`: `riftJava = "0.1.1"` (single
  source of truth; engine + corpus version derive from it at runtime/build). Effect-library
  floors at design time: ZIO 2.1.x, zio-json 0.7.x, CE 3.6.x, fs2 3.12.x, circe 0.14.x,
  munit-cats-effect 2.x, weaver 0.9.x (`org.typelevel`), Kyo latest stable, zio-bdd 1.4.x —
  each module's issue bumps to latest at implementation time.
- **Publishing** stays sbt-ci-release on `v*` tags (already wired). New artifacts (zio-json,
  circe, zio-bdd) join the aggregate. `conformance` is `publish / skip`.
- **Version policy**: 0.x while the engine is 0.x; `versionScheme := "early-semver"`
  (already set). A rift-java bump is a minor; a model wire-format change is a minor with
  release-note callouts. The rift-release version-bump automation (program task 6.4) watches
  rift-java releases and opens the bump PR (issue #13).

---

## 8. Milestone map

| Milestone | Issues | Modules |
|---|---|---|
| **M3 — model, bridge, ZIO** | #2 model · #3 bridge · #4 zio · #5 zio-testkit · #16 zio-json · #6 ZIO conformance · #7 ledger sample | model, bridge, zio, zio-testkit, zio-json, conformance |
| **M4 — Cats, FS2, Kyo, pure** | #8 cats · #9 fs2 · #10 cats-testkit · #17 circe · #11 kyo · #12 pure · #13 cats conformance + parity + release automation | cats, fs2, cats-testkit, circe, kyo, pure |
| **M5 — ecosystem integrations** | #18 zio-bdd adapter | zio-bdd |

Dependency chain: #2 → #3 → {#4 → #5 → #6/#7, #8 → {#9, #10, #13}, #11, #12} → M5.
rift-java 0.1.1 is released — nothing in M3 is blocked anymore.

## 9. Open questions / upstream follow-ups

1. **Typed verify over the facade** — ✅ resolved in rift-java 0.1.2. `Imposter.verify`'s
   `VerificationException` now carries a structured `result(): Optional[VerificationResult]`
   (`requests`, plus `closest` with per-predicate `failedPredicates`), so the bridge reads the
   engine's structured diff directly instead of parsing a string message
   ([rift-java#127](https://github.com/achird-labs/rift-java/issues/127)). D5's testkit
   client-side matcher stays — it renders those diffs in the model's own predicate vocabulary.
2. **Request-tail cursor & SSE** — two *separate* engine features, both now shipped:
   the stable savedRequests cursor ([rift#603](https://github.com/achird-labs/rift/issues/603) —
   `?since=<index>` + `x-rift-next-index` / `x-rift-truncated`) and the admin SSE `/events`
   stream ([rift#461](https://github.com/achird-labs/rift/issues/461) — lifecycle + `lagged`
   events). The bridge reaches both only through rift-java's facade (D2): the **cursor is live in
   rift-java 0.1.2** (`Imposter.recordedPage()` / `recordedSince(long)` → `RecordedPage{requests,
   nextIndex, truncated}`, [rift-java#130](https://github.com/achird-labs/rift-java/issues/130)),
   so D6's cursor tail is buildable today. The **SSE client**
   ([rift-java#131](https://github.com/achird-labs/rift-java/issues/131)) has since landed on the
   facade — `Rift.events(EventStreamOptions)` returns an `EventStream` of the `RiftEvent` ADT,
   including the lifecycle and `Lagged` variants — so that surface is now adoptable rather than
   pending. (`Dependencies.scala` pins `riftJava = "0.1.3"` → engine 0.15.0.)
3. **`rift-scala-bom` / sbt natives helper** — if classifier selection proves to be a support
   burden, ship `RiftNatives.currentClassifier` as a tiny sbt plugin or documented snippet
   first (bridge README), promote to an artifact on demand.
4. **zio-bdd convergence** — once 5.12 (#18) passes zio-bdd's conformance matrix, zio-bdd's
   bespoke rift adapters can delegate to rift-scala — already tracked upstream as
   [zio-bdd#285](https://github.com/EtaCassiopeia/zio-bdd/issues/285).
