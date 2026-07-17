package rift.cats

import java.net.URI
import java.nio.file.Path

import _root_.cats.effect.{Async, Resource}

import rift.json.Json
import rift.model.{FlowId, Port, RecordedRequest, ScenarioStatus, Stub, StubId, Times}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.bridge.{ImposterDefinition, RecordSpec, RecordingConnector}

/** Mirrors `rift.bridge.ImposterConnector` (DESIGN.md §5.6) 1:1, `F[_]`-shaped over `Async`.
  *
  * No cursor request *tail* (`requests`/`requests(pollEvery)` on the ZIO handle): the `fs2.Stream`
  * built by looping `recordedPage`/`recordedSince` belongs to the `rift-scala-fs2` module (issue
  * #9), not here. This trait exposes only the two `F`-shaped page reads that stream is built on.
  */
trait ImposterHandle[F[_]]:
  def port: Port
  def uri: URI
  def definition: F[ImposterDefinition]
  def addStub(stub: StubBuilder[StubPhase.Complete]): F[StubRef[F]]
  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): F[StubRef[F]]
  def replaceStubs(stubs: Vector[Stub]): F[Unit]
  def stubs: F[Vector[Stub]]
  def stub(id: StubId): F[StubRef[F]]
  def recorded: F[Vector[RecordedRequest]]
  def recorded(matching: RequestMatch): F[Vector[RecordedRequest]]

  /** Baseline read for the cursor request tail (DESIGN.md §5.3, D6) — the `rift-scala-fs2` module's
    * `requestStream`/`requestEvents` are built on this pair, not on `recorded`, so they can page
    * forward via `recordedSince` instead of an `all.drop(offset)` scheme. `filters` are applied
    * server-side (`TailFilter` → facade `MatchClause`); no `filters` is the unfiltered baseline.
    */
  def recordedPage(filters: rift.bridge.TailFilter*): F[rift.bridge.RecordedPage]

  /** Strictly-newer page since `cursor` (a prior `recordedPage`/`recordedSince` call's
    * `nextIndex`), optionally `filters`-narrowed.
    */
  def recordedSince(cursor: Long, filters: rift.bridge.TailFilter*): F[rift.bridge.RecordedPage]

  def clearRecorded: F[Unit]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): F[Unit]
  def verify(matching: RequestMatch, times: Int): F[Unit] // README sugar: exact count
  def verifyNoInteractions: F[Unit]
  def scenarios: Scenarios[F]
  def space(flowId: FlowId): SpaceHandle[F]
  def flowState(flowId: FlowId): FlowStateHandle[F]

  /** Scoped proxy-capture recording: the session is acquired on the blocking pool and, on resource
    * release, `close()`d — which stops and **discards** it (the facade default). Read/persist the
    * captured stubs via the `RecordingHandle` (`stop`/`snapshot`/`persist`) inside the resource,
    * before release. Mirrors `Rift.intercept`'s resource lifecycle.
    */
  def startRecording(origin: URI, spec: RecordSpec = RecordSpec()): Resource[F, RecordingHandle[F]]

  def enable: F[Unit]
  def disable: F[Unit]
  def delete: F[Unit]

private[cats] final class ImposterHandleLive[F[_]: Async](
    private[cats] val connector: rift.bridge.ImposterConnector
) extends ImposterHandle[F]:
  def port: Port = connector.port
  def uri: URI = connector.uri

  def definition: F[ImposterDefinition] = blockingF(connector.definition)

  def addStub(stub: StubBuilder[StubPhase.Complete]): F[StubRef[F]] =
    blockingF(new StubRef[F](connector.addStub(stub.build)))

  def addStubFirst(stub: StubBuilder[StubPhase.Complete]): F[StubRef[F]] =
    blockingF(new StubRef[F](connector.addStubFirst(stub.build)))

  def replaceStubs(stubs: Vector[Stub]): F[Unit] = blockingF(connector.replaceStubs(stubs))

  def stubs: F[Vector[Stub]] = blockingF(connector.stubs)

  def stub(id: StubId): F[StubRef[F]] =
    blockingF(new StubRef[F](connector.stub(StubId.value(id))))

  def recorded: F[Vector[RecordedRequest]] = blockingF(connector.recorded())

  def recorded(matching: RequestMatch): F[Vector[RecordedRequest]] =
    blockingF(connector.recorded(matching))

  def recordedPage(filters: rift.bridge.TailFilter*): F[rift.bridge.RecordedPage] =
    blockingF(connector.recordedPage(filters*))

  def recordedSince(cursor: Long, filters: rift.bridge.TailFilter*): F[rift.bridge.RecordedPage] =
    blockingF(connector.recordedSince(cursor, filters*))

  def clearRecorded: F[Unit] = blockingF(connector.clearRecorded())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): F[Unit] =
    blockingF(connector.verify(matching, times))

  def verify(matching: RequestMatch, times: Int): F[Unit] =
    verify(matching, Times.Exactly(times))

  def verifyNoInteractions: F[Unit] = blockingF(connector.verifyNoInteractions())

  def scenarios: Scenarios[F] = new ScenariosLive[F](connector.scenarios)
  def space(flowId: FlowId): SpaceHandle[F] = new SpaceHandleLive[F](connector.space(flowId))
  def flowState(flowId: FlowId): FlowStateHandle[F] =
    new FlowStateHandleLive[F](connector.flowState(flowId))

  def startRecording(
      origin: URI,
      spec: RecordSpec
  ): Resource[F, RecordingHandle[F]] =
    Resource
      .fromAutoCloseable(blockingF(connector.startRecording(origin, spec)))
      .map(
        new RecordingHandleLive[F](_)
      )

  def enable: F[Unit] = blockingF(connector.enable())
  def disable: F[Unit] = blockingF(connector.disable())
  def delete: F[Unit] = blockingF(connector.delete())

/** A stub previously added to an imposter — re-fetch its assigned index/id, replace it, or delete
  * it without re-adding (mirrors `rift.bridge.StubHandle`).
  */
final class StubRef[F[_]: Async] private[cats] (underlying: rift.bridge.StubHandle):
  def index: F[Int] = blockingF(underlying.index)
  def id: F[Option[String]] = blockingF(underlying.id)
  def definition: F[Stub] = blockingF(underlying.definition)
  def replace(stub: Stub): F[Unit] = blockingF(underlying.replace(stub))
  def delete: F[Unit] = blockingF(underlying.delete())

/** An imposter's named-scenario state machine (mirrors `rift.bridge.ScenariosHandle`).
  *
  * `setState`/`reset` are NOT flow-scoped: the underlying facade (`io.github.etacassiopeia.rift.
  * Scenarios`) only exposes a flow-scoped `list`, not flow-scoped `setState`/`reset` — adding a
  * `flowId` parameter here would silently no-op it rather than actually scope the call, so it is
  * left off rather than faked (mirrors the ZIO handle's identical omission).
  */
trait Scenarios[F[_]]:
  def list: F[Vector[ScenarioStatus]]
  def list(flowId: FlowId): F[Vector[ScenarioStatus]]
  def state(name: String): F[String]
  def setState(name: String, state: String): F[Unit]
  def reset: F[Unit]

private[cats] final class ScenariosLive[F[_]: Async](underlying: rift.bridge.ScenariosHandle)
    extends Scenarios[F]:
  def list: F[Vector[ScenarioStatus]] = blockingF(underlying.list())
  def list(flowId: FlowId): F[Vector[ScenarioStatus]] =
    blockingF(underlying.list(FlowId.value(flowId)))
  def state(name: String): F[String] = blockingF(underlying.state(name))
  def setState(name: String, state: String): F[Unit] = blockingF(underlying.setState(name, state))
  def reset: F[Unit] = blockingF(underlying.reset())

/** An isolated `_rift` flow-state space (mirrors `rift.bridge.SpaceHandle`). */
trait SpaceHandle[F[_]]:
  def flowId: FlowId
  def addStub(stub: StubBuilder[StubPhase.Complete]): F[StubRef[F]]
  def stubs: F[Vector[Stub]]
  def recorded: F[Vector[RecordedRequest]]
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): F[Unit]
  def delete: F[Unit]

private[cats] final class SpaceHandleLive[F[_]: Async](underlying: rift.bridge.SpaceHandle)
    extends SpaceHandle[F]:
  def flowId: FlowId = underlying.flowId

  def addStub(stub: StubBuilder[StubPhase.Complete]): F[StubRef[F]] =
    blockingF(new StubRef[F](underlying.addStub(stub.build)))

  def stubs: F[Vector[Stub]] = blockingF(underlying.stubs)

  def recorded: F[Vector[RecordedRequest]] = blockingF(underlying.recorded())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): F[Unit] =
    blockingF(underlying.verify(matching, times))

  def delete: F[Unit] = blockingF(underlying.delete())

/** Per-flow key/value state (mirrors `rift.bridge.FlowStateHandle`).
  *
  * No `clear`: the underlying facade (`io.github.etacassiopeia.rift.FlowState`) exposes only
  * `get`/`put`/`delete`, no bulk clear — faking one via a delete-every-known-key loop would need a
  * key listing the facade doesn't provide either, so it is left off rather than faked (mirrors the
  * ZIO handle's identical omission).
  */
trait FlowStateHandle[F[_]]:
  def get(key: String): F[Option[Json]]
  def put(key: String, value: Json): F[Unit]
  def delete(key: String): F[Unit]

private[cats] final class FlowStateHandleLive[F[_]: Async](
    underlying: rift.bridge.FlowStateHandle
) extends FlowStateHandle[F]:
  def get(key: String): F[Option[Json]] = blockingF(underlying.get(key))
  def put(key: String, value: Json): F[Unit] = blockingF(underlying.put(key, value))
  def delete(key: String): F[Unit] = blockingF(underlying.delete(key))

/** A live proxy-capture recording session (mirrors `rift.bridge.RecordingConnector`), obtained from
  * `ImposterHandle.startRecording` as a `Resource[F, RecordingHandle[F]]` — release stops and
  * discards it. Read or persist the captured stubs before the resource is released.
  */
trait RecordingHandle[F[_]]:
  def stop: F[Vector[Stub]]
  def snapshot: F[Vector[Stub]]
  def persist(path: Path): F[Unit]

private[cats] final class RecordingHandleLive[F[_]: Async](underlying: RecordingConnector)
    extends RecordingHandle[F]:
  def stop: F[Vector[Stub]] = blockingF(underlying.stop())
  def snapshot: F[Vector[Stub]] = blockingF(underlying.snapshot())
  def persist(path: Path): F[Unit] = blockingF(underlying.persist(path))
