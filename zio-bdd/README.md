# rift-scala-zio-bdd

A [zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) `MockControl` adapter backed by
`rift-scala-zio` (issue #18, DESIGN §5.12): zio-bdd suites — and anything else built on the
published `zio-bdd-mock` SPI — drive a Rift engine through rift-scala's typed surface.

```scala
import rift.ziobdd.RiftScalaBackend
import zio.bdd.mock.*

// One-liner test stack: embedded in-process engine + adapter.
val control: ZLayer[Any, MockError, MockControl] = RiftScalaBackend.embedded()

// Or wrap a Rift service you already provide (any transport: embedded/connect/spawn/container).
val fromRift: URLayer[rift.zio.Rift, MockControl] = RiftScalaBackend.fromService

// Correlated isolation: one shared imposter partitioned by a flow header.
val correlated = RiftScalaBackend.embedded(Isolation.Correlated)
```

## Why this, when zio-bdd ships its own Rift adapters (`zio-bdd-rift*`)?

Those adapters speak the engine's admin API directly — hand-rolled JSON over zio-http, a
client-side port allocator, and positional rule-index bookkeeping. This adapter rides
`rift.zio.Rift` instead, which changes what can go wrong:

- **One protocol implementation.** Every wire shape comes from `rift.dsl` / `rift.model`, the
  same code the sdk-conformance corpus gates (G1–G3) — not a second, drift-prone copy of the
  Mountebank dialect.
- **No port races.** Spaces get **engine-assigned ephemeral ports** (`port` omitted) instead of a
  probe-then-bind allocator with its inherent TOCTOU window.
- **Native rule identity.** `RuleId`s map to Rift **stub ids** (`handle.stub(StubId)`), not a
  client-tracked index that must mirror server order.
- **Typed spaces + intercept.** Correlated isolation uses the `SpaceHandle` surface; the
  `Intercept` capability is the `Rift.intercept` TLS-MITM proxy (#34), truststore export included.

Behavioral parity with the upstream adapters is deliberate where suites can observe it: separate
ANDed predicates, a 404 unmatched default, `_rift.flowState` always attached, first-match
fault/capability stubs, `proxyOnce` + method/path predicate generators, and text-verbatim JSON
bodies.

## Capabilities

The full SPI set: `Faults`, `StatefulScenarios`, `StateInspection`, `Scripting`, `ProxyRecord`,
`Templating`, `Intercept`.

Known edges (all fail with a **typed** `MockError.InvalidDefinition`, never silently):

- On a **Correlated** space, scenario `define` (with the default `Started` initial state) and
  `currentState` work per-flow; only the state *writes* (`reset`/`setState`) and a **custom initial
  state** remain gapped — they need a per-flow `setState` the `rift.zio` surface doesn't expose yet
  (tracked upstream in rift-java#151). Use `PerInstance` isolation for those. A later rule mutation
  rebuilds the space and re-registers the scenario stubs, but (like any correlated rebuild) resets
  the flow's scenario state to its start — so run rule mutations before defining scenarios, or on a
  separate space.
- Correlated rule mutation beyond a `Base` append rebuilds the space (the space endpoint has no
  per-stub delete), which clears its recorded requests and flow state — mutate at scenario
  boundaries, as with the upstream adapter.
- `Intercept` needs a transport whose engine can run the intercept listener (the embedded
  provider); on a remote `connect` engine the first intercept call surfaces the engine's answer.
- An intercept `Redirect` to a **Correlated** space forwards to the shared imposter without the
  correlation header, so requests land on the 404 default rather than the flow's stubs — redirect
  to PerInstance spaces.
- Spaces a suite never `destroy()`s are swept when the adapter layer releases, so a long-lived
  `connect` engine does not accumulate orphaned imposters.

## Testing

- `RiftModelMappingSpec` / `RiftScalaBackendContractSpec` run engine-free on every JVM.
- `RiftScalaBackendLiveSpec` runs the MockControl contract against a real embedded engine; it is
  guarded on `JRift.isEmbeddedAvailable()` (the repo-standard skip) and executes on the JDK 22 CI
  job, where the embedded jars are wired.

zio-bdd's cross-backend conformance scenario sets are not published as an artifact (they live in
zio-bdd's test sources); when they are, running them against this adapter is a test-wiring
follow-up, not an adapter change.
