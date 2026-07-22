package rift.zio.testkit

import java.net.URI

import zio.*
import zio.stream.ZStream

import rift.RiftError
import rift.bridge.{ImposterDefinition, RecordSpec, TailEvent, TailFilter}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.model.{FlowId, Port, RecordedRequest, Stub, StubId, Times}
import rift.zio.{FlowStateHandle, ImposterHandle, RecordingHandle, Scenarios, SpaceHandle, StubRef}

/** A hand-rolled `ImposterHandle` for the assertion gate tests (`AssertionsSpec`) — no live engine.
  * Only `verify`/`verifyNoInteractions` are exercised by those tests; every other member is
  * unreachable from them and dies loudly if ever called, rather than returning a value that would
  * misrepresent a real engine response.
  */
private[testkit] final class FakeImposterHandle(
    verifyResult: IO[RiftError, Unit] = ZIO.unit,
    verifyNoInteractionsResult: IO[RiftError, Unit] = ZIO.unit
) extends ImposterHandle:

  private def unreachable[A]: A =
    throw new NotImplementedError("FakeImposterHandle: not exercised by AssertionsSpec")

  def port: Port = unreachable
  def uri: URI = unreachable
  def definition: IO[RiftError, ImposterDefinition] = ZIO.die(new NotImplementedError)
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] =
    ZIO.die(new NotImplementedError)
  def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[RiftError, StubRef] =
    ZIO.die(new NotImplementedError)
  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] =
    ZIO.die(new NotImplementedError)
  def replaceStubs(stubs: Chunk[Stub]): IO[RiftError, Unit] = ZIO.die(new NotImplementedError)
  def stubs: IO[RiftError, Chunk[Stub]] = ZIO.die(new NotImplementedError)
  def stub(id: StubId): IO[RiftError, StubRef] = ZIO.die(new NotImplementedError)
  def recorded: IO[RiftError, Chunk[RecordedRequest]] = ZIO.die(new NotImplementedError)
  def recorded(matching: RequestMatch): IO[RiftError, Chunk[RecordedRequest]] =
    ZIO.die(new NotImplementedError)
  def clearRecorded: IO[RiftError, Unit] = ZIO.die(new NotImplementedError)
  def verify(matching: RequestMatch, times: Times): IO[RiftError, Unit] = verifyResult
  def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] = verifyResult
  def verifyNoInteractions: IO[RiftError, Unit] = verifyNoInteractionsResult
  def requests: ZStream[Any, RiftError, RecordedRequest] = ZStream.die(new NotImplementedError)
  def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest] =
    ZStream.die(new NotImplementedError)
  def requests(
      pollEvery: Duration,
      filters: Chunk[TailFilter]
  ): ZStream[Any, RiftError, RecordedRequest] =
    ZStream.die(new NotImplementedError)
  def requestEvents(
      pollEvery: Duration,
      filters: Chunk[TailFilter]
  ): ZStream[Any, RiftError, TailEvent] =
    ZStream.die(new NotImplementedError)
  def scenarios: Scenarios = unreachable
  def space(flowId: FlowId): SpaceHandle = unreachable
  def flowState(flowId: FlowId): FlowStateHandle = unreachable
  def startRecording(origin: URI, spec: RecordSpec): ZIO[Scope, RiftError, RecordingHandle] =
    ZIO.die(new NotImplementedError)
  def enable: IO[RiftError, Unit] = ZIO.die(new NotImplementedError)
  def disable: IO[RiftError, Unit] = ZIO.die(new NotImplementedError)
  def delete: IO[RiftError, Unit] = ZIO.die(new NotImplementedError)
