package rift.zio

import java.net.URI
import java.nio.file.Path

import zio.*
import zio.stream.ZStream

import rift.RiftError
import rift.json.Json
import rift.model.{FlowId, Port, RecordedRequest, ScenarioStatus, Stub, StubId, Times}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.bridge.{
  ImposterDefinition,
  RecordSpec,
  RecordedPage,
  RecordingConnector,
  TailEvent,
  TailFilter
}

/** Mirrors `rift.bridge.ImposterConnector` (DESIGN.md §5.3). */
trait ImposterHandle:
  def port: Port
  def uri: URI
  def definition: IO[RiftError, ImposterDefinition]
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]

  /** Insert at `index` in the stub list, which is match-priority order. `index == stubs.size`
    * appends; out of range fails with `RiftError.InvalidDefinition` from the engine rather than
    * being clamped here, so the bound is always the engine's live view of the list.
    */
  def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[RiftError, StubRef]
  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]
  def replaceStubs(stubs: Chunk[Stub]): IO[RiftError, Unit]
  def stubs: IO[RiftError, Chunk[Stub]]
  def stub(id: StubId): IO[RiftError, StubRef]
  def recorded: IO[RiftError, Chunk[RecordedRequest]]
  def recorded(matching: RequestMatch): IO[RiftError, Chunk[RecordedRequest]]
  def clearRecorded: IO[RiftError, Unit]

  /** Drop journal entries matching `filters` server-side, or the whole journal when `filters` is
    * empty — the same reading as `recordedPage`. Shares the `TailFilter` vocabulary, so it covers
    * header, flow, method and path.
    */
  def clearRecorded(filters: Chunk[TailFilter]): IO[RiftError, Unit]

  /** Clears the cached proxy responses that `proxyOnce`/`proxyAlways` replay — a different store
    * from the recorded-request journal, which this leaves untouched.
    */
  def clearProxyResponses: IO[RiftError, Unit]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit]
  def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] // README sugar: exact count
  def verifyNoInteractions: IO[RiftError, Unit]
  def requests: ZStream[Any, RiftError, RecordedRequest] // 100ms poll
  def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest]

  /** Server-side `filters`-narrowed request tail (`TailFilter.Header`/`Flow`/`Method`/`Path` →
    * facade `MatchClause`); the engine advances the cursor past rejected entries, so this never
    * re-scans the journal.
    */
  def requests(
      pollEvery: Duration,
      filters: Chunk[TailFilter]
  ): ZStream[Any, RiftError, RecordedRequest]

  /** The signal-carrying tail: the same cursor poll as `requests`, but each element is a
    * `TailEvent` so a caller can observe `Truncated`/`Degraded`/`Restored` explicitly. `requests`
    * is `requestEvents.collect { case Received(r) => r }` — one implementation, two views.
    */
  def requestEvents(
      pollEvery: Duration = 100.millis,
      filters: Chunk[TailFilter] = Chunk.empty
  ): ZStream[Any, RiftError, TailEvent]

  def scenarios: Scenarios
  def space(flowId: FlowId): SpaceHandle
  def flowState(flowId: FlowId): FlowStateHandle

  /** Scoped proxy-capture recording: the session is acquired on the blocking pool and, on scope
    * release, `close()`d — which stops and **discards** it (the facade default). Read/persist the
    * captured stubs via the `RecordingHandle` (`stop`/`snapshot`/`persist`) inside the scope,
    * before release. Mirrors `Rift.intercept`'s scoped lifecycle.
    */
  def startRecording(
      origin: URI,
      spec: RecordSpec = RecordSpec()
  ): ZIO[Scope, RiftError, RecordingHandle]

  def enable: IO[RiftError, Unit]
  def disable: IO[RiftError, Unit]
  def delete: IO[RiftError, Unit]

private[zio] final case class ImposterHandleLive(connector: rift.bridge.ImposterConnector)
    extends ImposterHandle:
  def port: Port = connector.port
  def uri: URI = connector.uri

  def definition: IO[RiftError, ImposterDefinition] = blockingIO(connector.definition)

  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] =
    blockingIO(StubRef(connector.addStub(stub.build)))

  def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[RiftError, StubRef] =
    blockingIO(StubRef(connector.addStub(stub.build, index)))

  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] =
    blockingIO(StubRef(connector.addStubFirst(stub.build)))

  def replaceStubs(stubs: Chunk[Stub]): IO[RiftError, Unit] =
    blockingIO(connector.replaceStubs(stubs.toVector))

  def stubs: IO[RiftError, Chunk[Stub]] = blockingIO(Chunk.fromIterable(connector.stubs))

  def stub(id: StubId): IO[RiftError, StubRef] =
    blockingIO(StubRef(connector.stub(StubId.value(id))))

  def recorded: IO[RiftError, Chunk[RecordedRequest]] =
    blockingIO(Chunk.fromIterable(connector.recorded()))

  def recorded(matching: RequestMatch): IO[RiftError, Chunk[RecordedRequest]] =
    blockingIO(Chunk.fromIterable(connector.recorded(matching)))

  def clearRecorded: IO[RiftError, Unit] = blockingIO(connector.clearRecorded())
  def clearRecorded(filters: Chunk[TailFilter]): IO[RiftError, Unit] =
    blockingIO(connector.clearRecorded(filters*))
  def clearProxyResponses: IO[RiftError, Unit] = blockingIO(connector.clearProxyResponses())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit] =
    blockingIO(connector.verify(matching, times))

  def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] =
    verify(matching, Times.Exactly(times))

  def verifyNoInteractions: IO[RiftError, Unit] = blockingIO(connector.verifyNoInteractions())

  def requests: ZStream[Any, RiftError, RecordedRequest] = requests(100.millis)

  def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest] =
    requests(pollEvery, Chunk.empty)

  def requests(
      pollEvery: Duration,
      filters: Chunk[TailFilter]
  ): ZStream[Any, RiftError, RecordedRequest] =
    RequestTail.stream(fetchPage(filters), pollEvery)

  def requestEvents(
      pollEvery: Duration = 100.millis,
      filters: Chunk[TailFilter] = Chunk.empty
  ): ZStream[Any, RiftError, TailEvent] =
    RequestTail.events(fetchPage(filters), pollEvery)

  private def fetchPage(filters: Chunk[TailFilter]): Option[Long] => IO[RiftError, RecordedPage] = {
    case None => blockingIO(connector.recordedPage(filters*))
    case Some(cursor) => blockingIO(connector.recordedSince(cursor, filters*))
  }

  def scenarios: Scenarios = ScenariosLive(connector.scenarios)
  def space(flowId: FlowId): SpaceHandle = SpaceHandleLive(connector.space(flowId))
  def flowState(flowId: FlowId): FlowStateHandle = FlowStateHandleLive(connector.flowState(flowId))

  def startRecording(
      origin: URI,
      spec: RecordSpec
  ): ZIO[Scope, RiftError, RecordingHandle] =
    ZIO
      .acquireRelease(blockingIO(connector.startRecording(origin, spec)))(rec =>
        ZIO.attemptBlocking(rec.close()).orDie
      )
      .map(RecordingHandleLive(_))

  def enable: IO[RiftError, Unit] = blockingIO(connector.enable())
  def disable: IO[RiftError, Unit] = blockingIO(connector.disable())
  def delete: IO[RiftError, Unit] = blockingIO(connector.delete())

/** A stub previously added to an imposter — re-fetch its assigned index/id, replace it, or delete
  * it without re-adding (mirrors `rift.bridge.StubHandle`).
  */
final class StubRef private[zio] (underlying: rift.bridge.StubHandle):
  def index: IO[RiftError, Int] = blockingIO(underlying.index)
  def id: IO[RiftError, Option[String]] = blockingIO(underlying.id)
  def definition: IO[RiftError, Stub] = blockingIO(underlying.definition)
  def replace(stub: Stub): IO[RiftError, Unit] = blockingIO(underlying.replace(stub))
  def delete: IO[RiftError, Unit] = blockingIO(underlying.delete())

/** An imposter's named-scenario state machine (mirrors `rift.bridge.ScenariosHandle`).
  *
  * The flowless `setState`/`reset` write every flow at once; the per-flow `setState(name, state,
  * flowId)` writes a single flow's state on a correlated imposter (matching the per-flow
  * `list(flowId)`/`state(name, flowId)` reads). `reset` has no per-flow form — the facade exposes
  * only a whole-imposter reset — so a single flow is returned to its initial state via a per-flow
  * `setState` back to that value.
  */
trait Scenarios:
  def list: IO[RiftError, Chunk[ScenarioStatus]]
  def list(flowId: FlowId): IO[RiftError, Chunk[ScenarioStatus]]
  def state(name: String): IO[RiftError, String]

  /** The current state of scenario `name` within a specific flow, for a correlated imposter whose
    * scenario state is partitioned by `(flowId, scenarioName)`. Derived from `list(flowId)`.
    */
  def state(name: String, flowId: FlowId): IO[RiftError, String]
  def setState(name: String, state: String): IO[RiftError, Unit]

  /** Write scenario `name`'s state within a single `flowId` on a correlated imposter, leaving other
    * flows untouched — the per-flow write counterpart to `state(name, flowId)`.
    */
  def setState(name: String, state: String, flowId: FlowId): IO[RiftError, Unit]
  def reset: IO[RiftError, Unit]

private[zio] final case class ScenariosLive(underlying: rift.bridge.ScenariosHandle)
    extends Scenarios:
  def list: IO[RiftError, Chunk[ScenarioStatus]] = blockingIO(Chunk.fromIterable(underlying.list()))
  def list(flowId: FlowId): IO[RiftError, Chunk[ScenarioStatus]] =
    blockingIO(Chunk.fromIterable(underlying.list(FlowId.value(flowId))))
  def state(name: String): IO[RiftError, String] = blockingIO(underlying.state(name))
  def state(name: String, flowId: FlowId): IO[RiftError, String] =
    list(flowId).flatMap(_.find(_.name == name) match
      case Some(status) => ZIO.succeed(status.state)
      case None =>
        ZIO.fail(
          RiftError.InvalidDefinition(s"no scenario '$name' for flow ${FlowId.value(flowId)}", None)
        )
    )
  def setState(name: String, state: String): IO[RiftError, Unit] =
    blockingIO(underlying.setState(name, state))
  def setState(name: String, state: String, flowId: FlowId): IO[RiftError, Unit] =
    blockingIO(underlying.setState(name, state, FlowId.value(flowId)))
  def reset: IO[RiftError, Unit] = blockingIO(underlying.reset())

/** An isolated `_rift` flow-state space (mirrors `rift.bridge.SpaceHandle`). */
trait SpaceHandle:
  def flowId: FlowId
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]

  /** Add a fully-built model `Stub` to this space as-is — for stubs the phantom-typed builder can't
    * carry, notably a scenario stub with its `ScenarioRef` triplet. The stub is registered under
    * this space's flow, so a correlated imposter advances that scenario per-flow natively.
    */
  def addStub(stub: Stub): IO[RiftError, StubRef]
  def stubs: IO[RiftError, Chunk[Stub]]
  def recorded: IO[RiftError, Chunk[RecordedRequest]]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit]
  def delete: IO[RiftError, Unit]

private[zio] final case class SpaceHandleLive(underlying: rift.bridge.SpaceHandle)
    extends SpaceHandle:
  def flowId: FlowId = underlying.flowId

  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] =
    blockingIO(StubRef(underlying.addStub(stub.build)))

  def addStub(stub: Stub): IO[RiftError, StubRef] =
    blockingIO(StubRef(underlying.addStub(stub)))

  def stubs: IO[RiftError, Chunk[Stub]] = blockingIO(Chunk.fromIterable(underlying.stubs))

  def recorded: IO[RiftError, Chunk[RecordedRequest]] =
    blockingIO(Chunk.fromIterable(underlying.recorded()))

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit] =
    blockingIO(underlying.verify(matching, times))

  def delete: IO[RiftError, Unit] = blockingIO(underlying.delete())

/** Per-flow key/value state (mirrors `rift.bridge.FlowStateHandle`).
  *
  * No `clear`: the underlying facade (`io.github.achirdlabs.rift.FlowState`) exposes only
  * `get`/`put`/`delete`, no bulk clear — faking one via a delete-every-known-key loop would need a
  * key listing the facade doesn't provide either, so it is left off rather than faked.
  */
trait FlowStateHandle:
  def get(key: String): IO[RiftError, Option[Json]]
  def put(key: String, value: Json): IO[RiftError, Unit]
  def delete(key: String): IO[RiftError, Unit]

private[zio] final case class FlowStateHandleLive(underlying: rift.bridge.FlowStateHandle)
    extends FlowStateHandle:
  def get(key: String): IO[RiftError, Option[Json]] = blockingIO(underlying.get(key))
  def put(key: String, value: Json): IO[RiftError, Unit] = blockingIO(underlying.put(key, value))
  def delete(key: String): IO[RiftError, Unit] = blockingIO(underlying.delete(key))

/** A live proxy-capture recording session (mirrors `rift.bridge.RecordingConnector`), obtained from
  * `ImposterHandle.startRecording` as a scoped resource — release stops and discards it. Read or
  * persist the captured stubs before the scope closes.
  */
trait RecordingHandle:
  def stop: IO[RiftError, Chunk[Stub]]
  def snapshot: IO[RiftError, Chunk[Stub]]
  def persist(path: Path): IO[RiftError, Unit]

private[zio] final case class RecordingHandleLive(underlying: RecordingConnector)
    extends RecordingHandle:
  def stop: IO[RiftError, Chunk[Stub]] = blockingIO(Chunk.fromIterable(underlying.stop()))
  def snapshot: IO[RiftError, Chunk[Stub]] = blockingIO(Chunk.fromIterable(underlying.snapshot()))
  def persist(path: Path): IO[RiftError, Unit] = blockingIO(underlying.persist(path))
