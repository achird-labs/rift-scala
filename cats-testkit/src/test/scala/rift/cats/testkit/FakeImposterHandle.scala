package rift.cats.testkit

import java.net.URI

import _root_.cats.effect.{IO, Resource}

import rift.bridge.{ImposterDefinition, RecordSpec, RecordedPage, TailFilter}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.model.{FlowId, Port, RecordedRequest, Stub, StubId, Times}
import rift.cats.{FlowStateHandle, ImposterHandle, RecordingHandle, Scenarios, SpaceHandle, StubRef}

/** A hand-rolled `ImposterHandle[IO]` for the assertion gate tests (`AssertionsSpec`) — no live
  * engine. Only `verify`/`verifyNoInteractions` are exercised by those tests; every other member is
  * unreachable from them and raises loudly if ever called, rather than returning a value that would
  * misrepresent a real engine response.
  */
private[testkit] final class FakeImposterHandle(
    verifyResult: IO[Unit] = IO.unit,
    verifyNoInteractionsResult: IO[Unit] = IO.unit
) extends ImposterHandle[IO]:

  private def unreachable[A]: A =
    throw new NotImplementedError("FakeImposterHandle: not exercised by AssertionsSpec")

  def port: Port = unreachable
  def uri: URI = unreachable
  def definition: IO[ImposterDefinition] = IO.raiseError(new NotImplementedError)
  def addStub(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] =
    IO.raiseError(new NotImplementedError)
  def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[StubRef[IO]] =
    IO.raiseError(new NotImplementedError)
  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] =
    IO.raiseError(new NotImplementedError)
  def replaceStubs(stubs: Vector[Stub]): IO[Unit] = IO.raiseError(new NotImplementedError)
  def stubs: IO[Vector[Stub]] = IO.raiseError(new NotImplementedError)
  def stub(id: StubId): IO[StubRef[IO]] = IO.raiseError(new NotImplementedError)
  def recorded: IO[Vector[RecordedRequest]] = IO.raiseError(new NotImplementedError)
  def recorded(matching: RequestMatch): IO[Vector[RecordedRequest]] =
    IO.raiseError(new NotImplementedError)
  def recordedPage(filters: TailFilter*): IO[RecordedPage] = IO.raiseError(new NotImplementedError)
  def recordedSince(cursor: Long, filters: TailFilter*): IO[RecordedPage] =
    IO.raiseError(new NotImplementedError)
  def clearRecorded: IO[Unit] = IO.raiseError(new NotImplementedError)
  def clearRecorded(filters: rift.bridge.TailFilter*): IO[Unit] =
    IO.raiseError(new NotImplementedError)
  def clearProxyResponses: IO[Unit] = IO.raiseError(new NotImplementedError)
  def verify(matching: RequestMatch, times: Times): IO[Unit] = verifyResult
  def verify(matching: RequestMatch, times: Int): IO[Unit] = verifyResult
  def verifyNoInteractions: IO[Unit] = verifyNoInteractionsResult
  def startRecording(origin: URI, spec: RecordSpec): Resource[IO, RecordingHandle[IO]] =
    Resource.eval(IO.raiseError(new NotImplementedError))
  def scenarios: Scenarios[IO] = unreachable
  def space(flowId: FlowId): SpaceHandle[IO] = unreachable
  def flowState(flowId: FlowId): FlowStateHandle[IO] = unreachable
  def enable: IO[Unit] = IO.raiseError(new NotImplementedError)
  def disable: IO[Unit] = IO.raiseError(new NotImplementedError)
  def delete: IO[Unit] = IO.raiseError(new NotImplementedError)
