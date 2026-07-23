package rift.ziobdd

import java.net.URI

import zio.*
import zio.stream.ZStream
import zio.test.*

import zio.bdd.mock as spi

import rift.RiftError
import rift.json.Json
import rift.model.{ApplyResult, EngineInfo, Port}
import rift.dsl.ImposterBuilder
import rift.bridge.{EventStreamConfig, ImposterDefinition, InterceptConfig, RiftEvent}
import rift.zio.{ImposterHandle, InterceptHandle, Rift}

/** Engine-free contract gate for the adapter surface (issue #18): everything here must hold before
  * any engine is touched, so it runs unconditionally on every JVM. The engine-dependent behavior is
  * covered by the guarded [[RiftScalaBackendLiveSpec]].
  */
object RiftScalaBackendContractSpec extends ZIOSpecDefault:

  /** A `Rift` whose every member dies loudly — the adapter operations under test must fail typed
    * BEFORE reaching the engine (unknown space, foreign native spec), so none of these is
    * reachable.
    */
  private object FailingRift extends Rift:
    private def die[A]: IO[RiftError, A] = ZIO.die(new NotImplementedError("FailingRift"))
    def create(definition: ImposterDefinition): IO[RiftError, ImposterHandle] = die
    def create(builder: ImposterBuilder): IO[RiftError, ImposterHandle] = die
    def createFromJson(json: String): IO[RiftError, ImposterHandle] = die
    def imposter(port: Port): IO[RiftError, ImposterHandle] = die
    def imposters: IO[RiftError, Chunk[ImposterHandle]] = die
    def deleteAll: IO[RiftError, Unit] = die
    def replaceAll(definitions: Chunk[ImposterDefinition]): IO[RiftError, Unit] = die
    def applyConfig(config: Json): IO[RiftError, ApplyResult] = die
    def info: IO[RiftError, EngineInfo] = die
    def adminUri: UIO[URI] = ZIO.die(new NotImplementedError("FailingRift"))
    def intercept(config: InterceptConfig): ZIO[Scope, RiftError, InterceptHandle] = die
    def interceptAttach(host: String, port: Int): ZIO[Scope, RiftError, InterceptHandle] = die
    def events(config: EventStreamConfig): ZStream[Any, RiftError, RiftEvent] =
      ZStream.die(new NotImplementedError("FailingRift"))

  private val layer: ULayer[spi.MockControl] =
    ZLayer.succeed[Rift](FailingRift) >>> RiftScalaBackend.fromService

  private val unknownSpace =
    spi.MockSpace("http://localhost:0", identity, spi.SpaceId("nope-0"))

  def spec = suite("RiftScalaBackend contract (engine-free, issue #18)")(
    test("advertises the full capability set and negotiates require() for every member") {
      for
        control <- ZIO.service[spi.MockControl]
        _ <- control.require(spi.Capability.values*)
      yield assertTrue(
        control.backendName == "rift-scala",
        control.capabilities == spi.Capability.values.toSet
      )
    },
    test("reports the isolation mode it was built with") {
      for
        perInstance <- ZIO.service[spi.MockControl]
        correlated <- ZIO
          .service[spi.MockControl]
          .provideLayer(
            ZLayer.succeed[Rift](FailingRift) >>>
              RiftScalaBackend.withIsolation(spi.Isolation.Correlated)
          )
      yield assertTrue(
        perInstance.isolation == spi.Isolation.PerInstance,
        correlated.isolation == spi.Isolation.Correlated
      )
    },
    test("every capability accessor returns an instance (full-set backend)") {
      for
        control <- ZIO.service[spi.MockControl]
        _ <- control.faults
        _ <- control.scenarios
        _ <- control.stateInspection
        _ <- control.scripting
        _ <- control.proxyRecord
        _ <- control.templating
        _ <- control.intercept
      yield assertCompletes
    },
    test("a WireMock native spec is rejected as InvalidDefinition, not attempted") {
      for
        control <- ZIO.service[spi.MockControl]
        exit <- control.provisionNative(spi.NativeSpec.WireMock("{}")).exit
      yield assert(exit)(
        Assertion.fails(Assertion.isSubtype[spi.MockError.InvalidDefinition](Assertion.anything))
      )
    },
    test("operations on an unknown space fail SpaceNotFound with that space's id") {
      for
        control <- ZIO.service[spi.MockControl]
        rule = spi.MockRule(spi.RequestMatch(), spi.ResponseDef())
        addExit <- control.addRule(unknownSpace, rule).exit
        removeExit <- control.removeRule(unknownSpace, spi.RuleId("r1")).exit
        destroyExit <- control.destroy(unknownSpace).exit
        receivedExit <- control.received(unknownSpace).exit
      yield assertTrue(
        List(addExit, removeExit, destroyExit, receivedExit).forall {
          case Exit.Failure(cause) =>
            cause.failureOption.contains(spi.MockError.SpaceNotFound(unknownSpace.id))
          case _ => false
        }
      )
    },
    test("a malformed raw provision source fails InvalidDefinition before the engine") {
      for
        control <- ZIO.service[spi.MockControl]
        exit <- control.provision(spi.MockSource.Json("not json")).exit
      yield assert(exit)(
        Assertion.fails(Assertion.isSubtype[spi.MockError.InvalidDefinition](Assertion.anything))
      )
    }
  ).provideShared(layer)
