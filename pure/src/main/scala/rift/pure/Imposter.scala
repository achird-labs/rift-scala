package rift.pure

import java.net.URI

import rift.RiftError
import rift.json.Json
import rift.model.{FlowId, Port, RecordedRequest, ScenarioStatus, Stub, StubId, Times}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.bridge.ImposterDefinition

/** Mirrors `rift.bridge.ImposterConnector` (DESIGN.md §5.11) 1:1, `Either[RiftError, _]`-shaped.
  *
  * `startRecording` is omitted here: the bridge and ZIO surfaces ship it (#35), but the pure
  * `Using`-friendly recording surface is a stacked follow-up (like the intercept surface's
  * cats/pure follow-up #45). Not faked here.
  *
  * No cursor request tail (`requests`/`requests(pollEvery)` on the ZIO/Cats handles, or a
  * `recordedPage`/`recordedSince` paging API): pure has no stream or effect system to drive polling
  * with, so `recorded()` here is a one-shot snapshot, not a subscription.
  */
final class Imposter private[pure] (connector: rift.bridge.ImposterConnector):

  def port: Port = connector.port
  def uri: URI = connector.uri

  def definition: Either[RiftError, ImposterDefinition] = catchRiftError(connector.definition)

  def addStub(stub: StubBuilder[StubPhase.Complete]): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(connector.addStub(stub.build)))

  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(connector.addStubFirst(stub.build)))

  def replaceStubs(stubs: Vector[Stub]): Either[RiftError, Unit] =
    catchRiftError(connector.replaceStubs(stubs))

  def stubs: Either[RiftError, Vector[Stub]] = catchRiftError(connector.stubs)

  def stub(id: StubId): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(connector.stub(StubId.value(id))))

  def recorded(): Either[RiftError, Vector[RecordedRequest]] =
    catchRiftError(connector.recorded())

  def recorded(matching: RequestMatch): Either[RiftError, Vector[RecordedRequest]] =
    catchRiftError(connector.recorded(matching))

  def clearRecorded(): Either[RiftError, Unit] = catchRiftError(connector.clearRecorded())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): Either[RiftError, Unit] =
    catchRiftError(connector.verify(matching, times))

  def verify(matching: RequestMatch, times: Int): Either[RiftError, Unit] =
    verify(matching, Times.Exactly(times))

  def verifyNoInteractions(): Either[RiftError, Unit] =
    catchRiftError(connector.verifyNoInteractions())

  def scenarios: Scenarios = new Scenarios(connector.scenarios)
  def space(flowId: FlowId): SpaceHandle = new SpaceHandle(connector.space(flowId))
  def flowState(flowId: FlowId): FlowStateHandle = new FlowStateHandle(connector.flowState(flowId))

  def enable(): Either[RiftError, Unit] = catchRiftError(connector.enable())
  def disable(): Either[RiftError, Unit] = catchRiftError(connector.disable())
  def delete(): Either[RiftError, Unit] = catchRiftError(connector.delete())

/** A stub previously added to an imposter — re-fetch its assigned index/id, replace it, or delete
  * it without re-adding (mirrors `rift.bridge.StubHandle`).
  */
final class StubRef private[pure] (underlying: rift.bridge.StubHandle):
  def index: Either[RiftError, Int] = catchRiftError(underlying.index)
  def id: Either[RiftError, Option[String]] = catchRiftError(underlying.id)
  def definition: Either[RiftError, Stub] = catchRiftError(underlying.definition)
  def replace(stub: Stub): Either[RiftError, Unit] = catchRiftError(underlying.replace(stub))
  def delete(): Either[RiftError, Unit] = catchRiftError(underlying.delete())

/** An imposter's named-scenario state machine (mirrors `rift.bridge.ScenariosHandle`).
  *
  * `setState`/`reset` are NOT flow-scoped: the underlying facade (`io.github.etacassiopeia.rift.
  * Scenarios`) only exposes a flow-scoped `list`, not flow-scoped `setState`/`reset` — adding a
  * `flowId` parameter here would silently no-op it rather than actually scope the call, so it is
  * left off rather than faked (mirrors the ZIO/Cats handles' identical omission).
  */
final class Scenarios private[pure] (underlying: rift.bridge.ScenariosHandle):
  def list(): Either[RiftError, Vector[ScenarioStatus]] = catchRiftError(underlying.list())
  def list(flowId: FlowId): Either[RiftError, Vector[ScenarioStatus]] =
    catchRiftError(underlying.list(FlowId.value(flowId)))
  def state(name: String): Either[RiftError, String] = catchRiftError(underlying.state(name))
  def setState(name: String, state: String): Either[RiftError, Unit] =
    catchRiftError(underlying.setState(name, state))
  def reset(): Either[RiftError, Unit] = catchRiftError(underlying.reset())

/** An isolated `_rift` flow-state space (mirrors `rift.bridge.SpaceHandle`). */
final class SpaceHandle private[pure] (underlying: rift.bridge.SpaceHandle):
  def flowId: FlowId = underlying.flowId

  def addStub(stub: StubBuilder[StubPhase.Complete]): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(underlying.addStub(stub.build)))

  def stubs: Either[RiftError, Vector[Stub]] = catchRiftError(underlying.stubs)

  def recorded(): Either[RiftError, Vector[RecordedRequest]] =
    catchRiftError(underlying.recorded())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): Either[RiftError, Unit] =
    catchRiftError(underlying.verify(matching, times))

  def delete(): Either[RiftError, Unit] = catchRiftError(underlying.delete())

/** Per-flow key/value state (mirrors `rift.bridge.FlowStateHandle`).
  *
  * No `clear`: the underlying facade (`io.github.etacassiopeia.rift.FlowState`) exposes only
  * `get`/`put`/`delete`, no bulk clear — faking one via a delete-every-known-key loop would need a
  * key listing the facade doesn't provide either, so it is left off rather than faked (mirrors the
  * ZIO/Cats handles' identical omission).
  */
final class FlowStateHandle private[pure] (underlying: rift.bridge.FlowStateHandle):
  def get(key: String): Either[RiftError, Option[Json]] = catchRiftError(underlying.get(key))
  def put(key: String, value: Json): Either[RiftError, Unit] =
    catchRiftError(underlying.put(key, value))
  def delete(key: String): Either[RiftError, Unit] = catchRiftError(underlying.delete(key))
