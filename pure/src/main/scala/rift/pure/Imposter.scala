package rift.pure

import java.net.URI
import java.nio.file.Path

import rift.RiftError
import rift.json.Json
import rift.model.{
  FlowId,
  Port,
  RecordedRequest,
  ScenarioStatus,
  Stub,
  StubId,
  Times,
  VerificationResult,
  VerifyDetail
}
import rift.dsl.{RequestMatch, StubBuilder, StubPhase}
import rift.bridge.{ImposterDefinition, RecordSpec, TailFilter}

/** Mirrors `rift.bridge.ImposterConnector` (DESIGN.md §5.11) 1:1, `Either[RiftError, _]`-shaped.
  *
  * No cursor request tail (`requests`/`requests(pollEvery)` on the ZIO/Cats handles, or a
  * `recordedPage`/`recordedSince` paging API): pure has no stream or effect system to drive polling
  * with, so `recorded()` here is a one-shot snapshot, not a subscription.
  */
final class Imposter private[pure] (private[pure] val connector: rift.bridge.ImposterConnector):

  def port: Port = connector.port
  def uri: URI = connector.uri

  def definition: Either[RiftError, ImposterDefinition] = catchRiftError(connector.definition)

  def addStub(stub: StubBuilder[StubPhase.Complete]): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(connector.addStub(stub.build)))

  /** Insert at `index` in the stub list, which is match-priority order. `index == stubs.size`
    * appends; out of range is a `Left(RiftError.InvalidDefinition)` from the engine rather than
    * being clamped here, so the bound is always the engine's live view of the list.
    */
  def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(connector.addStub(stub.build, index)))

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

  /** Drop journal entries matching `filters` server-side, or the whole journal when `filters` is
    * empty — the same reading as `recordedPage`. Shares the `TailFilter` vocabulary, so it covers
    * header, flow, method and path.
    */
  def clearRecorded(filters: TailFilter*): Either[RiftError, Unit] =
    catchRiftError(connector.clearRecorded(filters*))

  /** Clears the cached proxy responses that `proxyOnce`/`proxyAlways` replay — a different store
    * from the recorded-request journal, which this leaves untouched.
    */
  def clearProxyResponses(): Either[RiftError, Unit] =
    catchRiftError(connector.clearProxyResponses())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): Either[RiftError, Unit] =
    catchRiftError(connector.verify(matching, times))

  def verify(matching: RequestMatch, times: Int): Either[RiftError, Unit] =
    verify(matching, Times.Exactly(times))

  /** `verify`'s non-throwing counterpart (issue #88): the outcome as a value — including `satisfied
    * \= false` — rather than a `Left(RiftError.VerificationFailed)`.
    */
  def verifyResult(
      matching: RequestMatch,
      details: VerifyDetail*
  ): Either[RiftError, VerificationResult] =
    catchRiftError(connector.verifyResult(matching, details*))

  def verifyResult(
      matching: RequestMatch,
      times: Times,
      details: VerifyDetail*
  ): Either[RiftError, VerificationResult] =
    catchRiftError(connector.verifyResult(matching, times, details*))

  def verifyNoInteractions(): Either[RiftError, Unit] =
    catchRiftError(connector.verifyNoInteractions())

  def scenarios: Scenarios = new Scenarios(connector.scenarios)
  def space(flowId: FlowId): SpaceHandle = new SpaceHandle(connector.space(flowId))
  def flowState(flowId: FlowId): FlowStateHandle = new FlowStateHandle(connector.flowState(flowId))

  /** Start a proxy-capture recording session: this imposter proxies to `origin` and turns matched
    * traffic into stubs per `spec`. `close()` on the returned `Recording` stops and **discards** it
    * (the facade default) — read/persist the captured stubs via `stop`/`snapshot`/`persist` before
    * closing.
    */
  def startRecording(origin: URI, spec: RecordSpec = RecordSpec()): Either[RiftError, Recording] =
    catchRiftError(new Recording(connector.startRecording(origin, spec)))

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
  * `setState`/`reset` are NOT flow-scoped: the underlying facade (`io.github.achirdlabs.rift.
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

/** An isolated `_rift` flow-state space (mirrors `rift.bridge.SpaceHandle`).
  *
  * No space-scoped cursor tail here either, for the same reason the imposter has none: pure has no
  * stream or effect system to drive polling with, so `recorded()` is a one-shot snapshot. The
  * zio/cats/fs2 surfaces carry the paging form.
  */
final class SpaceHandle private[pure] (underlying: rift.bridge.SpaceHandle):
  def flowId: FlowId = underlying.flowId

  def addStub(stub: StubBuilder[StubPhase.Complete]): Either[RiftError, StubRef] =
    catchRiftError(new StubRef(underlying.addStub(stub.build)))

  def stubs: Either[RiftError, Vector[Stub]] = catchRiftError(underlying.stubs)

  def recorded(): Either[RiftError, Vector[RecordedRequest]] =
    catchRiftError(underlying.recorded())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): Either[RiftError, Unit] =
    catchRiftError(underlying.verify(matching, times))

  def verifyResult(
      matching: RequestMatch,
      details: VerifyDetail*
  ): Either[RiftError, VerificationResult] =
    catchRiftError(underlying.verifyResult(matching, details*))

  def verifyResult(
      matching: RequestMatch,
      times: Times,
      details: VerifyDetail*
  ): Either[RiftError, VerificationResult] =
    catchRiftError(underlying.verifyResult(matching, times, details*))

  def delete(): Either[RiftError, Unit] = catchRiftError(underlying.delete())

/** Per-flow key/value state (mirrors `rift.bridge.FlowStateHandle`).
  *
  * No `clear`: the underlying facade (`io.github.achirdlabs.rift.FlowState`) exposes only
  * `get`/`put`/`delete`, no bulk clear — faking one via a delete-every-known-key loop would need a
  * key listing the facade doesn't provide either, so it is left off rather than faked (mirrors the
  * ZIO/Cats handles' identical omission).
  */
final class FlowStateHandle private[pure] (underlying: rift.bridge.FlowStateHandle):
  def get(key: String): Either[RiftError, Option[Json]] = catchRiftError(underlying.get(key))
  def put(key: String, value: Json): Either[RiftError, Unit] =
    catchRiftError(underlying.put(key, value))
  def delete(key: String): Either[RiftError, Unit] = catchRiftError(underlying.delete(key))

/** A live proxy-capture recording session (mirrors `rift.bridge.RecordingConnector`), obtained from
  * `Imposter.startRecording` — `close()` stops and **discards** it (the facade default). Read or
  * persist the captured stubs via `stop`/`snapshot`/`persist` before closing.
  */
final class Recording private[pure] (connector: rift.bridge.RecordingConnector)
    extends AutoCloseable:
  def stop(): Either[RiftError, Vector[Stub]] = catchRiftError(connector.stop())
  def snapshot(): Either[RiftError, Vector[Stub]] = catchRiftError(connector.snapshot())
  def persist(path: Path): Either[RiftError, Unit] = catchRiftError(connector.persist(path))
  def close(): Unit = connector.close()
