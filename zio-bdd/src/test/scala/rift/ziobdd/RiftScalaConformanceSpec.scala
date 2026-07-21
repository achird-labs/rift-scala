package rift.ziobdd

import zio.*
import zio.test.*

import zio.bdd.mock.*
import zio.bdd.mock.conformance.*

import io.github.achirdlabs.rift.Rift as JRift

/** Runs zio-bdd's OWN published conformance suite (`zio-bdd-mock-conformance`, zio-bdd #332)
  * against `RiftScalaBackend` — the SPI-defined compliance bar, not a hand-written stand-in. The
  * scenario catalogues program only against `MockControl`, so this is a pure backend swap: the same
  * scenarios that certify zio-bdd's own embedded/WireMock/container adapters, run against ours.
  *
  * Guarded on the embedded engine exactly like `RiftScalaBackendLiveSpec`: a JVM without the engine
  * (JDK < 22) skips; the JDK 22+ job runs the full matrix.
  */
object RiftScalaConformanceSpec extends ZIOSpecDefault:

  private def asThrowable(e: MockError): Throwable = new RuntimeException(s"MockError: $e")

  private val riftScala =
    MockBackendUnderTest(
      name = "rift-scala",
      layer = RiftScalaBackend.embedded().mapError(asThrowable),
      capabilities = Capability.values.toSet,
      isolation = Isolation.PerInstance,
      available = JRift.isEmbeddedAvailable()
    )

  private val scenarios: List[ConformanceScenario] =
    CoreConformanceScenarios.all ++
      NegotiationErrorScenarios.all ++
      FaultScenarios.all ++
      ScriptingScenarios.all ++
      TemplatingScenarios.all ++
      CapStatefulScenarios.all

  def spec = suite("RiftScalaBackend conformance (zio-bdd #332 suite, guarded)")(
    test("passes every published conformance scenario its capabilities cover") {
      if !JRift.isEmbeddedAvailable() then
        ZIO.logWarning("conformance skipped: no embedded engine on this JVM") *>
          ZIO.succeed(assertCompletes)
      else
        for
          matrix <- ConformanceHarness.run(List(riftScala), scenarios)
          _ <- ZIO.logInfo(s"rift-scala conformance matrix:\n${matrix.render}")
          fails = scenarios.flatMap(s =>
            matrix
              .cell(s.name, "rift-scala")
              .filter(_.outcome == Outcome.Fail)
              .map(c => s"${c.scenario} — ${c.detail.getOrElse("(no detail)")}")
          )
          _ <- ZIO
            .logInfo(s"rift-scala conformance FAILURES (${fails.size}):\n${fails.mkString("\n")}")
            .when(fails.nonEmpty)
        // `conformant` gates the whole column (covered scenarios pass, uncovered skip); `fails`
        // renders WHICH scenarios failed if it doesn't.
        yield assert(fails)(Assertion.isEmpty) && assertTrue(matrix.conformant(riftScala))
    }
    // The delay/latency scenarios measure real elapsed time via `Clock.nanoTime`; the default
    // zio-test `TestClock` is frozen, so without the live clock every timing read is 0ms and those
    // two scenarios false-fail. zio-bdd's own `EmbeddedConformanceSpec` uses the same aspect.
  ) @@ TestAspect.withLiveClock
