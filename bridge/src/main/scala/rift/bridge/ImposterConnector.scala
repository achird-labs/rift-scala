package rift.bridge

import java.net.URI
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.dsl.RequestMatch
import rift.json.Json
import rift.model.{FlowId, Port, RecordedRequest, Stub, Times}

import io.github.achirdlabs.rift.Imposter as JImposter
import io.github.achirdlabs.rift.StubRef as JStubRef
import io.github.achirdlabs.rift.Scenarios as JScenarios
import io.github.achirdlabs.rift.Space as JSpace
import io.github.achirdlabs.rift.FlowState as JFlowState
import io.github.achirdlabs.rift.Recording as JRecording

/** Blocking, throwing (`RiftError`) handle on one imposter — mirrors `rift.zio.ImposterHandle`
  * (DESIGN.md §5.2/§5.3) 1:1 but blocking.
  */
final class ImposterConnector private[bridge] (underlying: JImposter):

  /** The wrapped facade `Imposter` — needed by `InterceptRuleBuilder.redirectTo`, which routes
    * intercepted traffic to this imposter (the facade's `redirectTo` takes the `Imposter` itself).
    */
  private[bridge] def jImposter: JImposter = underlying

  def port: Port =
    FacadeBoundary.run {
      val p = underlying.port()
      Port
        .from(p)
        .getOrElse(
          throw RiftError.CommunicationError(s"engine reported an out-of-range port: $p", None)
        )
    }

  def uri: URI = FacadeBoundary.run(underlying.uri())

  def name: Option[String] = FacadeBoundary.run(underlying.name().toScala)

  def definition: rift.model.ImposterDefinition =
    FacadeBoundary.run(FacadeDecode.imposterDefinition(underlying.definition()))

  def addStub(stub: Stub): StubHandle =
    FacadeBoundary.run(StubHandle(underlying.addStub(FacadeEncode.stub(stub))))

  def addStub(stub: Stub, index: Int): StubHandle =
    FacadeBoundary.run(StubHandle(underlying.addStub(FacadeEncode.stub(stub), index)))

  def addStubFirst(stub: Stub): StubHandle =
    FacadeBoundary.run(StubHandle(underlying.addStubFirst(FacadeEncode.stub(stub))))

  def replaceStubs(stubs: Vector[Stub]): Unit =
    FacadeBoundary.run(underlying.replaceStubs(FacadeEncode.json(Json.Arr(stubs.map(_.toJson)))))

  def stub(id: String): StubHandle = FacadeBoundary.run(StubHandle(underlying.stub(id)))

  def stubs: Vector[Stub] = FacadeBoundary.run(FacadeDecode.stubs(underlying.stubs()))

  def recorded(): Vector[RecordedRequest] =
    FacadeBoundary.run(FacadeDecode.recordedRequests(underlying.recorded()))

  def recorded(matching: RequestMatch): Vector[RecordedRequest] =
    FacadeBoundary.run(
      FacadeDecode.recordedRequests(underlying.recorded(FacadeEncode.requestMatch(matching)))
    )

  /** Baseline read for the cursor request tail (DESIGN.md §5.3, D6) — pair with `recordedSince` to
    * page forward without an `all.drop(offset)` scheme (see `RecordedPage`'s scaladoc for why that
    * silently loses entries). Called with no `filters` it is the unfiltered baseline.
    *
    * `filters` are applied server-side (`MatchClause`), so the engine advances the cursor past
    * rejected entries and the tail never re-scans. `TailFilter` mirrors the facade's narrow
    * `MatchClause` (`header`/`flowId`) exactly and totally; richer `RequestMatch` predicate
    * filtering is a tracked upstream ask (a lossy translation is refused), so consumers needing it
    * filter client-side over the unfiltered tail.
    */
  def recordedPage(filters: TailFilter*): RecordedPage =
    FacadeBoundary.run(
      FacadeDecode.recordedPage(underlying.recordedPage(FacadeEncode.matchClauses(filters)*))
    )

  /** Strictly-newer page since `cursor` (the value a prior `recordedPage()`/`recordedSince()` call
    * returned as `nextIndex`), optionally `filters`-narrowed. See `recordedPage`'s scaladoc for the
    * server-side filter semantics.
    */
  def recordedSince(cursor: Long, filters: TailFilter*): RecordedPage =
    FacadeBoundary.run(
      FacadeDecode.recordedPage(
        underlying.recordedSince(cursor, FacadeEncode.matchClauses(filters)*)
      )
    )

  def clearRecorded(): Unit = FacadeBoundary.run(underlying.clearRecorded())

  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): Unit =
    FacadeBoundary.run(
      underlying.verify(FacadeEncode.requestMatch(matching), FacadeEncode.times(times))
    )

  def verifyNoInteractions(): Unit = FacadeBoundary.run(underlying.verifyNoInteractions())

  def scenarios: ScenariosHandle = FacadeBoundary.run(ScenariosHandle(underlying.scenarios()))

  def space(flowId: FlowId): SpaceHandle =
    FacadeBoundary.run(SpaceHandle(flowId, underlying.space(FlowId.value(flowId))))

  def flowState(flowId: FlowId): FlowStateHandle =
    FacadeBoundary.run(FlowStateHandle(underlying.flowState(FlowId.value(flowId))))

  /** Start a proxy-capture recording session: this imposter proxies to `origin` and turns matched
    * traffic into stubs per `spec` (DESIGN.md §5.2). The returned `RecordingConnector` owns the
    * live session — `close()`/`Using` stops and discards it (the facade default), so a caller that
    * wants to keep the captured stubs must call `stop()`/`snapshot()`/`persist(...)` before it
    * closes.
    */
  def startRecording(origin: URI, spec: RecordSpec = RecordSpec()): RecordingConnector =
    FacadeBoundary.run(RecordingConnector(underlying.startRecording(origin.toString, spec.toJava)))

  def enable(): Unit = FacadeBoundary.run(underlying.enable())
  def disable(): Unit = FacadeBoundary.run(underlying.disable())
  def delete(): Unit = FacadeBoundary.run(underlying.delete())

/** A live proxy-capture recording session (`ImposterConnector.startRecording`, DESIGN.md §5.2).
  * Blocking/throwing; `AutoCloseable` so it is `Using`-friendly — `close()` stops and **discards**
  * the recording (mirrors the facade `Recording.close()` default), so persist or read the stubs via
  * `stop()`/`snapshot()`/`persist(...)` before closing.
  */
final class RecordingConnector private[bridge] (underlying: JRecording) extends AutoCloseable:
  /** Stop recording and return the captured stubs. */
  def stop(): Vector[Stub] = FacadeBoundary.run(FacadeDecode.stubs(underlying.stop()))

  /** The stubs captured so far, without stopping the session. */
  def snapshot(): Vector[Stub] = FacadeBoundary.run(FacadeDecode.stubs(underlying.snapshot()))

  /** Write the captured stubs to `path` as a loadable imposter file. */
  def persist(path: Path): Unit = FacadeBoundary.run(underlying.persist(path))

  def close(): Unit = FacadeBoundary.run(underlying.close())

/** A stub previously added to an imposter — lets a caller re-fetch its assigned index/id, replace
  * it, or delete it without re-adding.
  */
final class StubHandle private[bridge] (underlying: JStubRef):
  def index: Int = FacadeBoundary.run(underlying.index())
  def id: Option[String] = FacadeBoundary.run(underlying.id().toScala)
  def definition: Stub = FacadeBoundary.run(FacadeDecode.stub(underlying.definition()))
  def replace(stub: Stub): Unit = FacadeBoundary.run(underlying.replace(FacadeEncode.stub(stub)))
  def delete(): Unit = FacadeBoundary.run(underlying.delete())

/** An imposter's named-scenario state machine. */
final class ScenariosHandle private[bridge] (underlying: JScenarios):
  def list(): Vector[rift.model.ScenarioStatus] =
    FacadeBoundary.run(
      underlying.list().asScala.toVector.map(s => rift.model.ScenarioStatus(s.name(), s.state()))
    )
  def list(name: String): Vector[rift.model.ScenarioStatus] =
    FacadeBoundary.run(
      underlying
        .list(name)
        .asScala
        .toVector
        .map(s => rift.model.ScenarioStatus(s.name(), s.state()))
    )
  def state(name: String): String = FacadeBoundary.run(underlying.state(name))
  def setState(name: String, state: String): Unit =
    FacadeBoundary.run(underlying.setState(name, state))

  /** Write scenario `name`'s state within a single `flowId` on a correlated imposter — the per-flow
    * write counterpart to the per-flow `list(flowId)` read. Leaves other flows' state untouched.
    */
  def setState(name: String, state: String, flowId: String): Unit =
    FacadeBoundary.run(underlying.setState(name, state, flowId))
  def reset(): Unit = FacadeBoundary.run(underlying.reset())

/** An isolated `_rift` flow-state space (correlated by `FlowId`) — its own stub set, recordings,
  * and verification, scoped away from the imposter's default space.
  *
  * No `recordedPage`/`recordedSince` here: the facade's `Space` interface exposes only
  * `recorded()`/`recorded(matching)`, not the cursor journal — a space-scoped tail is out of scope
  * for #4's gate (which only needs the imposter-level cursor).
  */
final class SpaceHandle private[bridge] (val flowId: FlowId, underlying: JSpace):
  def addStub(stub: Stub): StubHandle =
    FacadeBoundary.run(StubHandle(underlying.addStub(FacadeEncode.stub(stub))))
  def stubs: Vector[Stub] = FacadeBoundary.run(FacadeDecode.stubs(underlying.stubs()))
  def recorded(): Vector[RecordedRequest] =
    FacadeBoundary.run(FacadeDecode.recordedRequests(underlying.recorded()))
  def recorded(matching: RequestMatch): Vector[RecordedRequest] =
    FacadeBoundary.run(
      FacadeDecode.recordedRequests(underlying.recorded(FacadeEncode.requestMatch(matching)))
    )
  def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): Unit =
    FacadeBoundary.run(
      underlying.verify(FacadeEncode.requestMatch(matching), FacadeEncode.times(times))
    )
  def delete(): Unit = FacadeBoundary.run(underlying.delete())

/** Per-flow key/value state (`_rift.flowState`) — arbitrary JSON values keyed by string. */
final class FlowStateHandle private[bridge] (underlying: JFlowState):
  def get(key: String): Option[Json] =
    FacadeBoundary.run(underlying.get(key).toScala.map(FacadeDecode.json))
  def put(key: String, value: Json): Unit =
    FacadeBoundary.run(underlying.put(key, FacadeEncode.json(value)))
  def put(key: String, value: String): Unit = FacadeBoundary.run(underlying.put(key, value))
  def delete(key: String): Unit = FacadeBoundary.run(underlying.delete(key))
