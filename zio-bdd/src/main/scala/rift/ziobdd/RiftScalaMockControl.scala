package rift.ziobdd

import zio.*

import zio.bdd.mock as spi

import rift.RiftError
import rift.dsl.{status, IsResponseBuilder, StubBuilder, StubPhase}
import rift.model.{FlowId, Port, Stub, StubId}
import rift.bridge.TruststoreFormat
import rift.zio.{ImposterHandle, InterceptHandle, Rift, SpaceHandle}

import RiftModelMapping as M

/** The `MockControl` adapter over `rift.zio.Rift` (issue #18, DESIGN §5.12).
  *
  * Space bookkeeping mirrors zio-bdd's engine-direct Rift adapter where the *semantics* must match
  * (first-match capability stubs, correlated-space rebuild-on-mutation, provision rollback, 404
  * unmatched default), but the mechanics ride rift-scala's typed surface instead of hand-rolled
  * admin HTTP:
  *
  *   - PerInstance: one imposter per space on an **engine-assigned ephemeral port** (no port
  *     allocator, no probe-then-bind race), rules addressed by **native stub ids**
  *     (`handle.stub(StubId)`) instead of client-tracked positional indexes.
  *   - Correlated: one shared imposter partitioned by a correlation header, each space a typed
  *     [[SpaceHandle]]. The space endpoint exposes only whole-space teardown, so any mutation
  *     beyond a Base append rebuilds the space (extras → faults → rules, first-registered wins) —
  *     which also clears its recorded requests and flow state; mutate at scenario boundaries.
  *
  * Correlated scenarios are full-service, matching the PerInstance path: `define` (raw
  * scenario-stub install per flow, including a custom initial state), `currentState` (per-flow
  * read), and the state *writes* `setState`/`reset` — all scoped to a single flow off the shared
  * imposter via the facade's per-flow `setState` (rift-java#152). A write to an undeclared scenario
  * is a typed `InvalidDefinition`, never a silent engine no-op. Scenario stubs and their declared
  * initials are tracked in `CorrState`, so a later mutation's rebuild re-registers them rather than
  * dropping the scenario — though, like every correlated rebuild, it resets the flow's scenario
  * state to its start.
  */
private[ziobdd] final case class RiftScalaMockControl(
    rift: Rift,
    provisioning: spi.Provisioning,
    mode: RiftScalaBackend.Mode,
    scope: Scope,
    spaces: Ref[Map[spi.SpaceId, SpaceRec]],
    shared: Ref.Synchronized[Option[ImposterHandle]],
    interceptCell: Ref.Synchronized[Option[InterceptHandle]],
    counter: Ref[Int]
) extends spi.MockControl:

  def backendName: String = "rift-scala"

  def capabilities: Set[spi.Capability] = spi.Capability.values.toSet

  override def isolation: spi.Isolation = mode match
    case RiftScalaBackend.Mode.PerInstance => spi.Isolation.PerInstance
    case RiftScalaBackend.Mode.Correlated(_) => spi.Isolation.Correlated

  // ── core port ────────────────────────────────────────────────────────────────────────────────

  def provision(source: spi.MockSource): IO[spi.MockError, List[spi.MockSpace]] =
    provisioning.normalize(source).flatMap { sources =>
      Ref.make(List.empty[spi.MockSpace]).flatMap { created =>
        ZIO
          .foreach(sources)(src => serveSpace(src).tap(s => created.update(s :: _)))
          .onError(_ =>
            // Roll back the spaces already stood up; the original failure propagates and a cleanup
            // failure is logged rather than masking it.
            created.get.flatMap(
              ZIO.foreachDiscard(_)(s =>
                destroy(s).catchAllCause(c =>
                  ZIO.logWarningCause(s"rollback: destroy ${s.id.value} failed", c)
                )
              )
            )
          )
      }
    }

  def provisionNative[B <: spi.Backend](
      spec: spi.NativeSpec[B]
  ): IO[spi.MockError, List[spi.MockSpace]] =
    spec match
      case spi.NativeSpec.Rift(imposterJson) =>
        for
          definition <- ZIO.fromEither(M.fromRaw(imposterJson, honourDocPort = true))
          handle <- rift.create(definition).mapError(M.toMockError(None))
          space <- registerPerInstance("native", handle)
        yield List(space)
      case spi.NativeSpec.WireMock(_) =>
        ZIO.fail(
          spi.MockError.InvalidDefinition(
            "the rift-scala adapter cannot provision a WireMock native spec"
          )
        )

  def addRule(
      space: spi.MockSpace,
      rule: spi.MockRule,
      priority: spi.Priority
  ): IO[spi.MockError, spi.RuleId] =
    freshRuleId.flatMap { id =>
      ZIO.fromEither(M.stubFor(rule, id)).flatMap { builder =>
        withSpace(space) {
          case rec: SpaceRec.PerInstance =>
            val add = priority match
              case spi.Priority.Overlay => rec.handle.addStubFirst(builder)
              case spi.Priority.Base => rec.handle.addStub(builder)
            add.mapError(M.toMockError(Some(space.id))) *>
              rec.ruleIds.update(_ + id).as(id)
          case rec: SpaceRec.Correlated =>
            rec.state.modifyZIO { st =>
              priority match
                case spi.Priority.Base =>
                  rec.space.addStub(builder).mapError(M.toMockError(Some(space.id))) *>
                    ZIO.succeed((id, st.copy(rules = st.rules :+ (id -> builder))))
                case spi.Priority.Overlay =>
                  // First-match needs the overlay ahead of everything; the space endpoint appends,
                  // so rebuild with the rule prepended (server-first, commit on success).
                  val next = st.copy(rules = (id -> builder) +: st.rules)
                  rebuildCorrelated(space.id, rec.space, next).as((id, next))
            }
        }
      }
    }

  def removeRule(space: spi.MockSpace, id: spi.RuleId): IO[spi.MockError, Unit] =
    withSpace(space) {
      case rec: SpaceRec.PerInstance =>
        rec.ruleIds.get.flatMap { known =>
          if !known.contains(id) then ZIO.fail(spi.MockError.RuleNotFound(space.id, id))
          else
            rec.handle
              .stub(StubId(id.value))
              .flatMap(_.delete)
              .mapError(M.toMockError(Some(space.id))) *>
              rec.ruleIds.update(_ - id)
        }
      case rec: SpaceRec.Correlated =>
        rec.state.updateZIO { st =>
          val next =
            if st.rules.exists(_._1 == id) then
              Some(st.copy(rules = st.rules.filterNot(_._1 == id)))
            else if st.faults.exists(_._1 == id) then
              Some(st.copy(faults = st.faults.filterNot(_._1 == id)))
            else if st.extras.exists(_._1 == id) then
              Some(st.copy(extras = st.extras.filterNot(_._1 == id)))
            else None
          next match
            case Some(target) => rebuildCorrelated(space.id, rec.space, target).as(target)
            case None => ZIO.fail(spi.MockError.RuleNotFound(space.id, id))
        }
    }

  def replaceRules(space: spi.MockSpace, rules: List[spi.MockRule]): IO[spi.MockError, Unit] =
    withSpace(space) {
      case rec: SpaceRec.PerInstance =>
        for
          tagged <- tagAll(rules)
          stubs = Chunk.fromIterable(tagged.map(_._2.build))
          _ <- rec.handle.replaceStubs(stubs).mapError(M.toMockError(Some(space.id)))
          _ <- rec.ruleIds.set(tagged.map(_._1).toSet)
          // The PUT replaced every stub — including scenario stubs — so tracked scenario names
          // would otherwise pass guardDefined for FSMs that no longer exist on the server.
          _ <- rec.scenarios.set(Map.empty)
        yield ()
      case rec: SpaceRec.Correlated =>
        tagAll(rules).flatMap { tagged =>
          rec.state.updateZIO { st =>
            val next = st.copy(rules = tagged)
            rebuildCorrelated(space.id, rec.space, next).as(next)
          }
        }
    }

  def destroy(space: spi.MockSpace): IO[spi.MockError, Unit] =
    withSpace(space) {
      case rec: SpaceRec.PerInstance =>
        rec.handle.delete
          .catchSome { case RiftError.ImposterNotFound(_) => ZIO.unit }
          .mapError(M.toMockError(Some(space.id))) *> spaces.update(_ - space.id)
      case rec: SpaceRec.Correlated =>
        rec.space.delete.mapError(M.toMockError(Some(space.id))) *> spaces.update(_ - space.id)
    }

  def received(space: spi.MockSpace): IO[spi.MockError, List[spi.RecordedRequest]] =
    withSpace(space) {
      case rec: SpaceRec.PerInstance =>
        rec.handle.recorded
          .mapBoth(M.toMockError(Some(space.id)), _.map(M.toRecorded).toList)
      case rec: SpaceRec.Correlated =>
        rec.space.recorded.mapBoth(M.toMockError(Some(space.id)), _.map(M.toRecorded).toList)
    }

  // ── capabilities ─────────────────────────────────────────────────────────────────────────────

  def faults: IO[spi.Unsupported, spi.Faults] = ZIO.succeed(faultsImpl)
  def scenarios: IO[spi.Unsupported, spi.StatefulScenarios] = ZIO.succeed(scenariosImpl)
  def stateInspection: IO[spi.Unsupported, spi.StateInspection] = ZIO.succeed(stateImpl)
  def scripting: IO[spi.Unsupported, spi.Scripting] = ZIO.succeed(scriptingImpl)
  def proxyRecord: IO[spi.Unsupported, spi.ProxyRecord] = ZIO.succeed(proxyImpl)
  def templating: IO[spi.Unsupported, spi.Templating] = ZIO.succeed(templatingImpl)
  override def intercept: IO[spi.Unsupported, spi.Intercept] = ZIO.succeed(interceptImpl)

  private val faultsImpl: spi.Faults = new spi.Faults:
    def inject(
        space: spi.MockSpace,
        m: spi.RequestMatch,
        fault: spi.FaultKind
    ): IO[spi.MockError, spi.RuleId] =
      injectFirstMatch(space, CorrTier.Faults)(id => M.faultStub(m, fault, id))

  private val scriptingImpl: spi.Scripting = new spi.Scripting:
    def inject(
        space: spi.MockSpace,
        m: spi.RequestMatch,
        script: spi.Script
    ): IO[spi.MockError, spi.RuleId] =
      injectFirstMatch(space, CorrTier.Extras)(id => M.scriptStub(m, script, id))

  private val proxyImpl: spi.ProxyRecord = new spi.ProxyRecord:
    def proxy(
        space: spi.MockSpace,
        m: spi.RequestMatch,
        upstream: String
    ): IO[spi.MockError, spi.RuleId] =
      injectFirstMatch(space, CorrTier.Extras)(id => M.proxyStub(m, upstream, id))

  private val templatingImpl: spi.Templating = new spi.Templating:
    def inject(
        space: spi.MockSpace,
        m: spi.RequestMatch,
        template: spi.ResponseTemplate
    ): IO[spi.MockError, spi.RuleId] =
      injectFirstMatch(space, CorrTier.Extras)(id => M.templateStub(m, template, id))

  /** A capability stub must win over any normal rule on the same match: first position
    * (`addStubFirst` / rebuilt ahead of the rules) and tracked so `removeRule` can lift it. On a
    * Correlated space the stub lands in its tier (extras for script/proxy/template, faults for
    * fault injection) so a rule mutation preserves it in first-match position.
    */
  private def injectFirstMatch(space: spi.MockSpace, tier: CorrTier)(
      build: spi.RuleId => Either[spi.MockError, StubBuilder[StubPhase.Complete]]
  ): IO[spi.MockError, spi.RuleId] =
    freshRuleId.flatMap { id =>
      ZIO.fromEither(build(id)).flatMap { builder =>
        withSpace(space) {
          case rec: SpaceRec.PerInstance =>
            rec.handle.addStubFirst(builder).mapError(M.toMockError(Some(space.id))) *>
              rec.ruleIds.update(_ + id).as(id)
          case rec: SpaceRec.Correlated =>
            rec.state.modifyZIO { st =>
              val next = tier match
                case CorrTier.Faults => st.copy(faults = (id -> builder) +: st.faults)
                case CorrTier.Extras => st.copy(extras = (id -> builder) +: st.extras)
              rebuildCorrelated(space.id, rec.space, next).as((id, next))
            }
        }
      }
    }

  private val scenariosImpl: spi.StatefulScenarios = new spi.StatefulScenarios:
    def define(space: spi.MockSpace, scenario: spi.ScenarioDef): IO[spi.MockError, Unit] =
      withSpace(space) {
        case rec: SpaceRec.PerInstance =>
          for
            ids <- ZIO.foreach(Vector.range(0, scenario.rules.size))(_ => freshRuleId)
            stubs <- ZIO.fromEither(M.scenarioStubs(scenario, ids))
            current <- rec.handle.stubs.mapError(M.toMockError(Some(space.id)))
            _ <- rec.handle
              .replaceStubs(current ++ Chunk.fromIterable(stubs))
              .mapError(M.toMockError(Some(space.id)))
            // Pin the declared initial state — it starts a re-defined scenario over and registers
            // an explicit flow-store entry (same contract as upstream's adapter).
            _ <- rec.handle.scenarios
              .setState(scenario.name, scenario.initial.value)
              .mapError(M.toMockError(Some(space.id)))
            _ <- rec.ruleIds.update(_ ++ ids)
            _ <- rec.scenarios.update(_ + (scenario.name -> scenario.initial))
          yield ()
        case rec: SpaceRec.Correlated =>
          // Register each FSM edge (a raw Stub carrying its ScenarioRef triplet) under this space's
          // flow so the shared imposter advances the scenario per-flow. Install then (for a
          // non-default initial) pin — the engine seeds every flow's scenario at "Started", so a
          // custom initial is forced with a per-flow setState. Both run inside the state cell and the
          // tracked tier commits (`.as`) only once install AND pin succeed, so a mid-define failure
          // leaves the tracking unchanged and the scenario untracked (mirroring the PerInstance path,
          // whose tracking also lands after its pin). Partial server stubs are reclaimed by `destroy`.
          for
            ids <- ZIO.foreach(Vector.range(0, scenario.rules.size))(_ => freshRuleId)
            stubs <- ZIO.fromEither(M.scenarioStubs(scenario, ids))
            tagged = ids.zip(stubs)
            _ <- rec.state.modifyZIO { st =>
              val install = ZIO.foreachDiscard(tagged)((_, s) => rec.space.addStub(s))
              val pin = ZIO.when(scenario.initial != spi.ScenarioState.Started)(
                rec.imposter.scenarios
                  .setState(scenario.name, scenario.initial.value, rec.space.flowId)
              )
              (install *> pin)
                .mapError(M.toMockError(Some(space.id)))
                .as(
                  (
                    (),
                    st.copy(
                      scenarios = st.scenarios ++ tagged,
                      initials = st.initials + (scenario.name -> scenario.initial)
                    )
                  )
                )
            }
          yield ()
      }

    def reset(space: spi.MockSpace, name: String): IO[spi.MockError, Unit] =
      withSpace(space) {
        case rec: SpaceRec.PerInstance =>
          rec.scenarios.get.flatMap(_.get(name) match
            case Some(initial) =>
              rec.handle.scenarios
                .setState(name, initial.value)
                .mapError(M.toMockError(Some(space.id)))
            case None => noScenario(space.id, name)
          )
        case rec: SpaceRec.Correlated =>
          // No per-flow reset on the facade, so return THIS flow to the scenario's declared initial
          // with a per-flow setState — leaving other flows sharing the imposter untouched.
          rec.state.get.flatMap(_.initials.get(name) match
            case Some(initial) =>
              rec.imposter.scenarios
                .setState(name, initial.value, rec.space.flowId)
                .mapError(M.toMockError(Some(space.id)))
            case None => noScenario(space.id, name)
          )
      }

  private val stateImpl: spi.StateInspection = new spi.StateInspection:
    def currentState(space: spi.MockSpace, name: String): IO[spi.MockError, spi.ScenarioState] =
      withSpace(space) {
        case rec: SpaceRec.PerInstance =>
          guardDefined(rec, space.id, name)(
            rec.handle.scenarios
              .state(name)
              .mapBoth(M.toMockError(Some(space.id)), spi.ScenarioState(_))
          )
        case rec: SpaceRec.Correlated =>
          // Read the scenario's state within THIS space's flow off the shared imposter; a scenario
          // never defined for the flow surfaces as a typed InvalidDefinition (from the per-flow read).
          rec.imposter.scenarios
            .state(name, rec.space.flowId)
            .mapBoth(M.toMockError(Some(space.id)), spi.ScenarioState(_))
      }

    def setState(
        space: spi.MockSpace,
        name: String,
        to: spi.ScenarioState
    ): IO[spi.MockError, Unit] =
      withSpace(space) {
        case rec: SpaceRec.PerInstance =>
          guardDefined(rec, space.id, name)(
            rec.handle.scenarios
              .setState(name, to.value)
              .mapError(M.toMockError(Some(space.id)))
          )
        case rec: SpaceRec.Correlated =>
          // Write scenario state within THIS space's flow off the shared imposter; an undeclared
          // scenario is a typed failure (as on the PerInstance path), never a silent engine no-op.
          rec.state.get.flatMap(st =>
            if st.initials.contains(name) then
              rec.imposter.scenarios
                .setState(name, to.value, rec.space.flowId)
                .mapError(M.toMockError(Some(space.id)))
            else noScenario(space.id, name)
          )
      }

  private def guardDefined[A](rec: SpaceRec.PerInstance, spaceId: spi.SpaceId, name: String)(
      action: IO[spi.MockError, A]
  ): IO[spi.MockError, A] =
    rec.scenarios.get.flatMap(s => if s.contains(name) then action else noScenario(spaceId, name))

  private def noScenario[A](spaceId: spi.SpaceId, name: String): IO[spi.MockError, A] =
    ZIO.fail(spi.MockError.InvalidDefinition(s"no scenario '$name' on space ${spaceId.value}"))

  private val interceptImpl: spi.Intercept = new spi.Intercept:
    def proxyPort: IO[spi.MockError, Int] =
      interceptHandle.flatMap(_.address.mapBoth(M.toMockError(None), _.getPort))

    def add(rule: spi.InterceptRule): IO[spi.MockError, Unit] = rule match
      case spi.InterceptRule.Redirect(host, to) =>
        for
          handle <- interceptHandle
          target <- imposterOf(to)
          _ <- handle.rule(host).redirectTo(target).mapError(M.toMockError(Some(to.id)))
        yield ()
      case spi.InterceptRule.Serve(host, stub) =>
        for
          handle <- interceptHandle
          _ <- handle.rule(host).serve(interceptResponse(stub)).mapError(M.toMockError(None))
        yield ()

    def trustStore(
        format: spi.TrustStoreFormat,
        to: Option[java.nio.file.Path]
    ): IO[spi.MockError, spi.TrustStore] =
      for
        handle <- interceptHandle
        path <- ZIO
          .attemptBlocking(spi.TrustStore.exportPath(to, format))
          .mapError(t =>
            spi.MockError.ProvisionFailed(s"resolving truststore path: ${t.getMessage}")
          )
        password <- Random.nextUUID.map(_.toString)
        _ <- handle
          .exportTruststore(truststoreFormatOf(format), password, path)
          .mapError(M.toMockError(None))
      yield spi.TrustStore(path, password, format)

  /** The intercept listener is opt-in and lazy (the SPI contract): acquired on first use into the
    * adapter layer's scope — memoized, torn down when the layer releases.
    */
  private def interceptHandle: IO[spi.MockError, InterceptHandle] =
    interceptCell.modifyZIO {
      case current @ Some(handle) => ZIO.succeed((handle, current))
      case None =>
        scope
          .extend(rift.intercept())
          .mapBoth(M.toMockError(None), handle => (handle, Some(handle)))
    }

  private def interceptResponse(stub: spi.InterceptStub): IsResponseBuilder =
    val withHeaders = stub.headers.toVector.sortBy(_._1).foldLeft(status(stub.status)) {
      case (b, (k, v)) => b.header(k, v)
    }
    stub.body.fold(withHeaders)(withHeaders.text)

  private def truststoreFormatOf(f: spi.TrustStoreFormat): TruststoreFormat = f match
    case spi.TrustStoreFormat.Pkcs12 => TruststoreFormat.Pkcs12
    case spi.TrustStoreFormat.Jks => TruststoreFormat.Jks

  /** The imposter behind a space (a Correlated space's is the shared one) — the redirect target for
    * an intercept rule. A space this adapter never provisioned is rejected rather than silently
    * forwarded to a bogus target.
    */
  private def imposterOf(space: spi.MockSpace): IO[spi.MockError, ImposterHandle] =
    spaces.get.flatMap(_.get(space.id) match
      case Some(rec: SpaceRec.PerInstance) => ZIO.succeed(rec.handle)
      case Some(rec: SpaceRec.Correlated) => ZIO.succeed(rec.imposter)
      case None =>
        ZIO.fail(
          spi.MockError.InvalidDefinition(
            s"intercept redirect target ${space.id.value} is not a known rift-scala space"
          )
        )
    )

  // ── provisioning internals ───────────────────────────────────────────────────────────────────

  private def serveSpace(src: spi.NormalizedSource): IO[spi.MockError, spi.MockSpace] =
    mode match
      case RiftScalaBackend.Mode.PerInstance => servePerInstance(src)
      case RiftScalaBackend.Mode.Correlated(header) => serveCorrelated(src, header)

  private def servePerInstance(src: spi.NormalizedSource): IO[spi.MockError, spi.MockSpace] =
    src.payload match
      case spi.SourcePayload.Rules(rules) =>
        for
          tagged <- tagAll(rules)
          builder <- ZIO
            .attempt(
              tagged.foldLeft(M.imposterShell(src.name, src.authoredPort, None))((b, t) =>
                b.stub(t._2)
              )
            )
            .mapError(t => spi.MockError.InvalidDefinition(t.getMessage))
          handle <- rift.create(builder).mapError(M.toMockError(None))
          space <- registerPerInstance(src.name, handle, tagged.map(_._1).toSet)
        yield space
      case spi.SourcePayload.Raw(text) =>
        for
          definition <- ZIO.fromEither(M.fromRaw(text, honourDocPort = false))
          handle <- rift.create(definition).mapError(M.toMockError(None))
          space <- registerPerInstance(src.name, handle)
        yield space

  private def registerPerInstance(
      name: String,
      handle: ImposterHandle,
      ruleIds: Set[spi.RuleId] = Set.empty
  ): IO[spi.MockError, spi.MockSpace] =
    for
      ids <- Ref.make(ruleIds)
      scen <- Ref.make(Map.empty[String, spi.ScenarioState])
      id = spi.SpaceId(s"$name-${Port.value(handle.port)}")
      space = spi.MockSpace(handle.uri.toString, identity, id)
      _ <- spaces.update(_.updated(id, SpaceRec.PerInstance(handle, ids, scen)))
    yield space

  private def serveCorrelated(
      src: spi.NormalizedSource,
      header: String
  ): IO[spi.MockError, spi.MockSpace] =
    src.payload match
      case spi.SourcePayload.Raw(_) =>
        ZIO.fail(
          spi.MockError.InvalidDefinition(
            "Correlated isolation needs portable rule sources; provision a raw Rift imposter " +
              "via provisionNative"
          )
        )
      case spi.SourcePayload.Rules(rules) =>
        for
          imposter <- ensureShared(header)
          n <- counter.updateAndGet(_ + 1)
          flowRaw = s"${src.name}-s$n"
          flowId <- ZIO.fromEither(
            FlowId.from(flowRaw).left.map(spi.MockError.InvalidDefinition(_))
          )
          spaceHandle = imposter.space(flowId)
          tagged <- tagAll(rules)
          _ <- ZIO
            .foreachDiscard(tagged)((_, b) => spaceHandle.addStub(b))
            .mapError(M.toMockError(None))
            // Mirror the per-instance rollback: a half-registered flow must not orphan stubs on
            // the shared imposter — and a failed teardown must be visible, not discarded.
            .onError(_ =>
              spaceHandle.delete.catchAllCause(c =>
                ZIO.logWarningCause(s"rollback: delete correlated space $flowRaw failed", c)
              )
            )
          state <- Ref.Synchronized.make(
            CorrState(tagged, Vector.empty, Vector.empty, Vector.empty)
          )
          id = spi.SpaceId(flowRaw)
          inject = (req: spi.HttpRequest) => req.copy(headers = req.headers.add(header, flowRaw))
          space = spi.MockSpace(imposter.uri.toString, inject, id)
          _ <- spaces.update(_.updated(id, SpaceRec.Correlated(imposter, spaceHandle, state)))
        yield space

  /** The one shared Correlated imposter, created on first provision into the layer scope (so it is
    * torn down on release) — race-safe via the synchronized cell.
    */
  private def ensureShared(header: String): IO[spi.MockError, ImposterHandle] =
    shared.modifyZIO {
      case current @ Some(handle) => ZIO.succeed((handle, current))
      case None =>
        scope
          .extend(
            ZIO.acquireRelease(
              rift
                .create(M.imposterShell("correlated", None, Some(header)))
                .mapError(M.toMockError(None))
            )(handle =>
              handle.delete.catchAllCause(c =>
                ZIO.logWarningCause("shared correlated imposter teardown failed", c)
              )
            )
          )
          .map(handle => (handle, Some(handle)))
    }

  /** rift's space endpoint has no per-stub delete: mutations beyond a Base append tear the space
    * down and re-register the target set, extras and faults ahead of the rules so they keep winning
    * first-match. Server-first — callers run this inside the space's synchronized state cell and
    * commit only on success. A mid-rebuild failure leaves the space's *server* stubs partial
    * (surfaced as the failing call's error) while the tracked state stays unchanged — the same
    * divergence window upstream's adapter documents; recovery is another mutation or `destroy`.
    */
  private def rebuildCorrelated(
      spaceId: spi.SpaceId,
      space: SpaceHandle,
      target: CorrState
  ): IO[spi.MockError, Unit] =
    for
      _ <- space.delete.mapError(M.toMockError(Some(spaceId)))
      _ <- ZIO
        .foreachDiscard(target.extras ++ target.faults ++ target.rules)((_, b) => space.addStub(b))
        .mapError(M.toMockError(Some(spaceId)))
      // Scenario stubs are raw `Stub`s (they carry a `ScenarioRef` the builder can't), so they
      // re-register through the other `addStub` overload; appended last, after the plain rules.
      _ <- ZIO
        .foreachDiscard(target.scenarios)((_, s) => space.addStub(s))
        .mapError(M.toMockError(Some(spaceId)))
    yield ()

  // ── shared plumbing ──────────────────────────────────────────────────────────────────────────

  private def withSpace[A](space: spi.MockSpace)(
      f: SpaceRec => IO[spi.MockError, A]
  ): IO[spi.MockError, A] =
    spaces.get.flatMap(_.get(space.id) match
      case Some(rec) => f(rec)
      case None => ZIO.fail(spi.MockError.SpaceNotFound(space.id))
    )

  private def freshRuleId: UIO[spi.RuleId] =
    counter.updateAndGet(_ + 1).map(n => spi.RuleId(s"r$n"))

  private def tagAll(
      rules: List[spi.MockRule]
  ): IO[spi.MockError, Vector[(spi.RuleId, StubBuilder[StubPhase.Complete])]] =
    ZIO
      .foreach(rules.toVector) { rule =>
        freshRuleId.flatMap(id => ZIO.fromEither(M.stubFor(rule, id)).map(b => (id, b)))
      }

private[ziobdd] enum SpaceRec:
  case PerInstance(
      handle: ImposterHandle,
      ruleIds: Ref[Set[spi.RuleId]],
      scenarios: Ref[Map[String, spi.ScenarioState]]
  )
  case Correlated(
      imposter: ImposterHandle,
      space: SpaceHandle,
      state: Ref.Synchronized[CorrState]
  )

/** A Correlated space's tracked stub sets. Held in ONE `Ref.Synchronized` cell so every mutation
  * (which reads the sets, rebuilds the space over the network, then commits) is serialized — two
  * racing mutations on plain `Ref`s could each rebuild from the same stale snapshot and the loser's
  * commit would silently drop the winner's rule.
  */
/** Which first-match tier a Correlated capability stub lands in (rebuild order: extras, faults,
  * rules — first-registered wins).
  */
private[ziobdd] enum CorrTier:
  case Faults, Extras

private[ziobdd] final case class CorrState(
    rules: Vector[(spi.RuleId, StubBuilder[StubPhase.Complete])],
    faults: Vector[(spi.RuleId, StubBuilder[StubPhase.Complete])],
    extras: Vector[(spi.RuleId, StubBuilder[StubPhase.Complete])],
    // Scenario-FSM stubs (raw `Stub`s carrying a `ScenarioRef`) installed by `define`. Tracked so a
    // later mutation's `rebuildCorrelated` re-registers them instead of silently dropping the
    // scenario — the space teardown still resets flow state, the pre-existing "mutate at scenario
    // boundaries" caveat.
    scenarios: Vector[(spi.RuleId, Stub)],
    // Declared initial state per scenario name (mirrors the PerInstance `scenarios` Ref). `reset`
    // reads it to return a flow to its initial state, and `setState`/`reset` gate on it so a write
    // to an undeclared scenario is a typed failure rather than an engine no-op.
    initials: Map[String, spi.ScenarioState] = Map.empty
)
