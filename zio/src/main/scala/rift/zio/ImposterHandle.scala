package rift.zio

import java.net.URI

import zio.*
import zio.stream.ZStream

import rift.RiftError
import rift.json.Json
import rift.model.{FlowId, Port, RecordedRequest, ScenarioStatus, Stub, StubId, Times}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.bridge.{ImposterDefinition, RecordedPage}

/** Mirrors `rift.bridge.ImposterConnector` (DESIGN.md §5.3).
  *
  * `startRecording` is omitted: the bridge doesn't expose it yet — `ImposterConnector`'s own
  * scaladoc tracks the gap as issue #35. Not faked here.
  */
trait ImposterHandle:
  def port: Port
  def uri: URI
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
  def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] // README sugar: exact count
  def verifyNoInteractions: IO[RiftError, Unit]
  def requests: ZStream[Any, RiftError, RecordedRequest] // 100ms poll
  def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest]
  def scenarios: Scenarios
  def space(flowId: FlowId): SpaceHandle
  def flowState(flowId: FlowId): FlowStateHandle
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

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit] =
    blockingIO(connector.verify(matching, times))

  def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] =
    verify(matching, Times.Exactly(times))

  def verifyNoInteractions: IO[RiftError, Unit] = blockingIO(connector.verifyNoInteractions())

  def requests: ZStream[Any, RiftError, RecordedRequest] = requests(100.millis)

  def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest] =
    val fetchPage: Option[Long] => IO[RiftError, RecordedPage] = {
      case None => blockingIO(connector.recordedPage())
      case Some(cursor) => blockingIO(connector.recordedSince(cursor))
    }
    RequestTail.stream(fetchPage, pollEvery)

  def scenarios: Scenarios = ScenariosLive(connector.scenarios)
  def space(flowId: FlowId): SpaceHandle = SpaceHandleLive(connector.space(flowId))
  def flowState(flowId: FlowId): FlowStateHandle = FlowStateHandleLive(connector.flowState(flowId))

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
  * `setState`/`reset` are NOT flow-scoped: the underlying facade (`io.github.etacassiopeia.rift.
  * Scenarios`) only exposes a flow-scoped `list`, not flow-scoped `setState`/`reset` — adding a
  * `flowId` parameter here would silently no-op it rather than actually scope the call, so it is
  * left off rather than faked.
  */
trait Scenarios:
  def list: IO[RiftError, Chunk[ScenarioStatus]]
  def list(flowId: FlowId): IO[RiftError, Chunk[ScenarioStatus]]
  def state(name: String): IO[RiftError, String]
  def setState(name: String, state: String): IO[RiftError, Unit]
  def reset: IO[RiftError, Unit]

private[zio] final case class ScenariosLive(underlying: rift.bridge.ScenariosHandle)
    extends Scenarios:
  def list: IO[RiftError, Chunk[ScenarioStatus]] = blockingIO(Chunk.fromIterable(underlying.list()))
  def list(flowId: FlowId): IO[RiftError, Chunk[ScenarioStatus]] =
    blockingIO(Chunk.fromIterable(underlying.list(FlowId.value(flowId))))
  def state(name: String): IO[RiftError, String] = blockingIO(underlying.state(name))
  def setState(name: String, state: String): IO[RiftError, Unit] =
    blockingIO(underlying.setState(name, state))
  def reset: IO[RiftError, Unit] = blockingIO(underlying.reset())

/** An isolated `_rift` flow-state space (mirrors `rift.bridge.SpaceHandle`). */
trait SpaceHandle:
  def flowId: FlowId
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef]
  def stubs: IO[RiftError, Chunk[Stub]]
  def recorded: IO[RiftError, Chunk[RecordedRequest]]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit]
  def delete: IO[RiftError, Unit]

private[zio] final case class SpaceHandleLive(underlying: rift.bridge.SpaceHandle)
    extends SpaceHandle:
  def flowId: FlowId = underlying.flowId

  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] =
    blockingIO(StubRef(underlying.addStub(stub.build)))

  def stubs: IO[RiftError, Chunk[Stub]] = blockingIO(Chunk.fromIterable(underlying.stubs))

  def recorded: IO[RiftError, Chunk[RecordedRequest]] =
    blockingIO(Chunk.fromIterable(underlying.recorded()))

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[RiftError, Unit] =
    blockingIO(underlying.verify(matching, times))

  def delete: IO[RiftError, Unit] = blockingIO(underlying.delete())

/** Per-flow key/value state (mirrors `rift.bridge.FlowStateHandle`).
  *
  * No `clear`: the underlying facade (`io.github.etacassiopeia.rift.FlowState`) exposes only
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
