package rift.bridge

import java.net.URI

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.dsl.RequestMatch
import rift.json.Json
import rift.model.{FlowId, Port, RecordedRequest, Stub, Times}

import io.github.etacassiopeia.rift.Imposter as JImposter
import io.github.etacassiopeia.rift.StubRef as JStubRef
import io.github.etacassiopeia.rift.Scenarios as JScenarios
import io.github.etacassiopeia.rift.Space as JSpace
import io.github.etacassiopeia.rift.FlowState as JFlowState

/** Blocking, throwing (`RiftError`) handle on one imposter — mirrors `rift.zio.ImposterHandle`
  * (DESIGN.md §5.2/§5.3) 1:1 but blocking.
  *
  * `startRecording(origin, spec)` is not yet implemented: the `RecordSpec`-driven overload needs
  * `dsl.RequestField`/`RecordMode` modeled in `rift-scala-model`, which doesn't exist yet, and even
  * the simple overload's `Recording` handle (stop/snapshot/persist) was left for that same
  * follow-up rather than shipped as a half-mirrored surface. See the bridge implementation report
  * (issue #3).
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
    * silently loses entries).
    *
    * The `matching`-filtered overload (`underlying.recordedPage(MatchClause...)`) is deferred: the
    * facade's `MatchClause` only expresses `header`/`flowId` filters, not the full `RequestMatch`
    * predicate vocabulary this module's `verify`/`recorded(matching)` use, so translating one into
    * the other would be a shaky, lossy mapping rather than a faithful one. Not needed by #4's gate.
    */
  def recordedPage(): RecordedPage =
    FacadeBoundary.run(FacadeDecode.recordedPage(underlying.recordedPage()))

  /** Strictly-newer page since `cursor` (the value a prior `recordedPage()`/`recordedSince()` call
    * returned as `nextIndex`). See `recordedPage()`'s scaladoc for why the `matching`-filtered
    * overload is deferred.
    */
  def recordedSince(cursor: Long): RecordedPage =
    FacadeBoundary.run(FacadeDecode.recordedPage(underlying.recordedSince(cursor)))

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

  def enable(): Unit = FacadeBoundary.run(underlying.enable())
  def disable(): Unit = FacadeBoundary.run(underlying.disable())
  def delete(): Unit = FacadeBoundary.run(underlying.delete())

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
