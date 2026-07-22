package rift.zio

import java.net.URI

import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import rift.RiftError
import rift.dsl.*
import rift.model.{FlowId, Port, RecordedRequest, Stub, StubId, Times}
import rift.bridge.{ImposterDefinition, RecordSpec, TailEvent, TailFilter}

/** Pure-logic gate for the ZIO intercept rule builder (issue #34). The facade round-trip needs a
  * live engine (the bridge `EmbeddedSmokeSpec` covers that, skipped in CI), but the deferred
  * builder's accumulation — and the `redirectTo` cross-engine reject (issue #52, mirroring the cats
  * `InterceptBuilderSpec`) — are engine-free, and are exactly where a dropped `.when` or a
  * mis-typed handle would hide, so they get direct regression tests.
  */
object InterceptBuilderSpec extends ZIOSpecDefault:

  private def matchesOf(builder: InterceptRuleBuilder): Vector[RequestMatch] =
    builder match
      case live: InterceptRuleBuilderLive => live.matches
      case _ => Vector.empty

  private def hostOf(builder: InterceptRuleBuilder): Option[String] =
    builder match
      case live: InterceptRuleBuilderLive => live.host
      case other => throw new AssertionError(s"expected InterceptRuleBuilderLive, got $other")

  def spec = suite("InterceptRuleBuilder (pure accumulation)")(
    test("when accumulates every match in order — no earlier when is dropped"):
      val first = get("/a")
      val second = get("/b")
      // connector is never touched by `when` (it only copies), so a null is safe for this unit.
      val builder =
        InterceptRuleBuilderLive(null, Some("api.example.com")).when(first).when(second)
      assertTrue(matchesOf(builder) == Vector(first, second))
    ,
    test("a fresh builder starts with no matches"):
      assertTrue(matchesOf(InterceptRuleBuilderLive(null, Some("api.example.com"))).isEmpty)
    ,
    test("redirectTo rejects an ImposterHandle that isn't this engine's ImposterHandleLive"):
      // The reject arm never forces `built` and never touches the connector, so a null connector is
      // safe; any non-`ImposterHandleLive` handle drives it.
      for exit <- InterceptRuleBuilderLive(null, Some("api.example.com"))
          .redirectTo(ForeignHandle)
          .exit
      yield assert(exit)(fails(isSubtype[RiftError.InvalidDefinition](anything)))
    ,
    // issue #80 — `rule()` is the facade's catch-all form (host left unset), `rule(host)` the
    // scoped one. Both only construct the deferred builder, so the connector stays untouched.
    test("rule() seeds an all-hosts builder and rule(host) seeds a host-scoped one"):
      val handle = InterceptHandleLive(null)
      assertTrue(
        hostOf(handle.rule()).isEmpty,
        hostOf(handle.rule("api.example.com")) == Some("api.example.com")
      )
    ,
    test("an all-hosts builder accumulates every match in order, like the host-scoped form"):
      val first = get("/a")
      val second = get("/b")
      val builder = InterceptRuleBuilderLive(null, None).when(first).when(second)
      assertTrue(matchesOf(builder) == Vector(first, second), hostOf(builder).isEmpty)
  )

  /** A foreign `ImposterHandle` — not this engine's `ImposterHandleLive` — used only to reach
    * `redirectTo`'s reject arm. Every member dies loudly; none is exercised.
    */
  private object ForeignHandle extends ImposterHandle:
    private def nope[A]: A = throw new NotImplementedError(
      "ForeignHandle: only tests redirectTo reject"
    )
    private def die[A]: IO[RiftError, A] = ZIO.die(new NotImplementedError)
    def port: Port = nope
    def uri: URI = nope
    def definition: IO[RiftError, ImposterDefinition] = die
    def addStub(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] = die
    def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[RiftError, StubRef] = die
    def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[RiftError, StubRef] = die
    def replaceStubs(stubs: Chunk[Stub]): IO[RiftError, Unit] = die
    def stubs: IO[RiftError, Chunk[Stub]] = die
    def stub(id: StubId): IO[RiftError, StubRef] = die
    def recorded: IO[RiftError, Chunk[RecordedRequest]] = die
    def recorded(matching: RequestMatch): IO[RiftError, Chunk[RecordedRequest]] = die
    def clearRecorded: IO[RiftError, Unit] = die
    def verify(matching: RequestMatch, times: Times): IO[RiftError, Unit] = die
    def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] = die
    def verifyNoInteractions: IO[RiftError, Unit] = die
    def requests: ZStream[Any, RiftError, RecordedRequest] = ZStream.die(new NotImplementedError)
    def requests(pollEvery: Duration): ZStream[Any, RiftError, RecordedRequest] =
      ZStream.die(new NotImplementedError)
    def requests(
        pollEvery: Duration,
        filters: Chunk[TailFilter]
    ): ZStream[Any, RiftError, RecordedRequest] = ZStream.die(new NotImplementedError)
    def requestEvents(
        pollEvery: Duration,
        filters: Chunk[TailFilter]
    ): ZStream[Any, RiftError, TailEvent] = ZStream.die(new NotImplementedError)
    def scenarios: Scenarios = nope
    def space(flowId: FlowId): SpaceHandle = nope
    def flowState(flowId: FlowId): FlowStateHandle = nope
    def startRecording(origin: URI, spec: RecordSpec): ZIO[Scope, RiftError, RecordingHandle] = die
    def enable: IO[RiftError, Unit] = die
    def disable: IO[RiftError, Unit] = die
    def delete: IO[RiftError, Unit] = die
