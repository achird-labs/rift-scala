package rift.zio.testkit

import zio.*
import zio.test.*

import rift.RiftError
import rift.dsl.ImposterBuilder
import rift.model.{FaultConfig, LatencyFault, Response, RiftResponseExt, Stub}
import rift.zio.{ImposterHandle, Rift}

/** zio-test aspects over a shared `Rift` engine (DESIGN.md §5.4): provisioning fixtures, chaos
  * injection, and shared-imposter hygiene between tests.
  *
  * `TestAspectAtLeastR[Rift] = TestAspect[Nothing, Rift, Nothing, Any]` fixes the error channel to
  * `Nothing` — a provisioning failure here is a broken test fixture, not something a test author
  * can meaningfully recover from, so every fallible step is `.orDie`'d rather than faked into a
  * typed error the alias has no room for.
  */
object aspects:

  /** `FiberRef` rather than a service/layer: the aspect wraps arbitrary specs the way zio-test's
    * own fixture tags do, and a `FiberRef` is what lets `RiftTest.imposter` read back a value set
    * by a `before` hook running earlier in the *same* fiber, with no extra environment wiring for
    * users. Each test forks its own fiber, so concurrent tests never observe one another's
    * registrations.
    */
  private[testkit] val imposterRegistry: FiberRef[Map[String, ImposterHandle]] =
    Unsafe.unsafely(FiberRef.unsafe.make(Map.empty))

  /** Provisions `builder` before every test in the enclosing suite and deletes it after. The handle
    * is available inside the test via `RiftTest.imposter(name)`.
    */
  def withImposter(name: String)(builder: ImposterBuilder): TestAspectAtLeastR[Rift] =
    TestAspect.aroundWith(
      Rift.create(builder).orDie.tap(handle => imposterRegistry.update(_ + (name -> handle)))
    )(handle => handle.delete.orDie *> imposterRegistry.update(_ - name))

  /** Wraps every `is` response of every imposter currently known to the engine with a latency fault
    * before each test — a chaos aspect for exercising a SUT's timeout/retry handling.
    *
    * Only `is` responses carry the `_rift.fault` extension the engine reads (`Response.Is.rift:
    * Option[RiftResponseExt]`); `proxy`/`inject`/`fault`/`_rift`-script responses have no such slot
    * on the wire, so they are left untouched here rather than faked onto a shape that does not
    * support it.
    *
    * '''Parallel execution:''' this reaches every imposter the shared engine knows about, not just
    * ones the annotated test owns. In a suite that shares one engine (`provideShared`) and runs
    * tests in parallel (zio-test's default), the latency injected by one test is visible to every
    * other test racing it against the same engine. Pair with `TestAspect.sequential`, or give each
    * test its own imposter, to avoid this cross-test interference.
    */
  def withLatency(
      min: Duration,
      max: Duration,
      probability: Double = 1.0
  ): TestAspectAtLeastR[Rift] =
    TestAspect.before(injectLatencyEverywhere(min, max, probability))

  /** Resets scenarios and clears recorded requests on every imposter known to the engine after each
    * test — hygiene for imposters shared across many tests (e.g. via `withImposter`), so state from
    * one test never leaks into the next.
    *
    * '''Parallel execution:''' this reaches every imposter the shared engine knows about, not just
    * ones the annotated test owns. In a suite that shares one engine (`provideShared`) and runs
    * tests in parallel (zio-test's default), one test's reset can fire while another test is still
    * mid-flight against the same engine. Pair with `TestAspect.sequential`, or give each test its
    * own imposter, to avoid this cross-test interference.
    */
  val resetAfterEach: TestAspectAtLeastR[Rift] =
    TestAspect.after(resetAllImposters)

  private def injectLatencyEverywhere(
      min: Duration,
      max: Duration,
      probability: Double
  ): URIO[Rift, Unit] =
    (for
      imposters <- Rift.imposters
      _ <- ZIO.foreachDiscard(imposters)(injectLatency(_, min, max, probability))
    yield ()).orDie

  private def injectLatency(
      handle: ImposterHandle,
      min: Duration,
      max: Duration,
      probability: Double
  ): IO[RiftError, Unit] =
    for
      stubs <- handle.stubs
      _ <- handle.replaceStubs(stubs.map(withLatencyFault(_, min, max, probability)))
    yield ()

  /** Pure fault-merge: overlays a `LatencyFault` onto every `is` response of `stub`, preserving any
    * existing `behaviors`/`extra` and any pre-existing `fault.error`/`fault.tcp`.
    * `private[testkit]` so `AspectsSpec` can exercise it directly rather than only indirectly via
    * `withLatency`.
    */
  private[testkit] def withLatencyFault(
      stub: Stub,
      min: Duration,
      max: Duration,
      probability: Double
  ): Stub =
    stub.copy(responses = stub.responses.map {
      case Response.Is(is, behaviors, riftExt, extra) =>
        val fault =
          LatencyFault(probability, minMs = Some(min.toMillis), maxMs = Some(max.toMillis))
        val ext = riftExt.getOrElse(RiftResponseExt())
        val faultConfig = ext.fault.getOrElse(FaultConfig()).copy(latency = Some(fault))
        Response.Is(is, behaviors, Some(ext.copy(fault = Some(faultConfig))), extra)
      case other => other
    })

  private def resetAllImposters: URIO[Rift, Unit] =
    (for
      imposters <- Rift.imposters
      _ <- ZIO.foreachDiscard(imposters)(h => h.scenarios.reset *> h.clearRecorded)
    yield ()).orDie

/** Reads fixtures published by `aspects.withImposter` — mirrors the fixture-tag ergonomics zio-bdd
  * users know (DESIGN.md §5.4).
  */
object RiftTest:
  def imposter(name: String): IO[RiftError, ImposterHandle] =
    aspects.imposterRegistry.get.flatMap { registered =>
      ZIO
        .fromOption(registered.get(name))
        .orElseFail(
          RiftError.InvalidDefinition(
            s"no imposter registered under name '$name' -- add @@ aspects.withImposter(\"$name\")(...) to this suite",
            None
          )
        )
    }
