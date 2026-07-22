package rift.cats

import java.net.URI

import _root_.cats.effect.{IO, Resource}
import _root_.cats.effect.unsafe.implicits.global

import munit.FunSuite

import rift.RiftError
import rift.bridge.{ImposterDefinition, RecordSpec, RecordedPage, TailFilter}
import rift.dsl.*
import rift.model.{FlowId, Port, RecordedRequest, Stub, StubId, Times}

/** Pure-logic gate for the Cats intercept rule builder (issue #45, mirrors the ZIO
  * `InterceptBuilderSpec` for #34). The facade round-trip needs a live engine (the bridge
  * `EmbeddedSmokeSpec` covers that, skipped in CI), but the deferred builder's accumulation — and
  * the `redirectTo` cross-engine reject — are engine-free, and are exactly where a dropped `.when`
  * or a mis-typed handle would hide, so they get direct regression tests.
  */
class InterceptBuilderSpec extends FunSuite:

  private def matchesOf(builder: InterceptRuleBuilder[IO]): Vector[RequestMatch] =
    builder match
      case live: InterceptRuleBuilderLive[?] => live.matches
      case other => fail(s"expected InterceptRuleBuilderLive, got $other")

  private def hostOf(builder: InterceptRuleBuilder[IO]): Option[String] =
    builder match
      case live: InterceptRuleBuilderLive[?] => live.host
      case other => fail(s"expected InterceptRuleBuilderLive, got $other")

  test("when accumulates every match in order — no earlier when is dropped"):
    val a = get("/a")
    val b = get("/b")
    // connector is never touched by `when` (it only copies), so a null is safe for this unit.
    val builder = InterceptRuleBuilderLive[IO](null, Some("api.example.com")).when(a).when(b)
    assertEquals(matchesOf(builder), Vector(a, b))

  test("a fresh builder starts with no matches"):
    assert(matchesOf(InterceptRuleBuilderLive[IO](null, Some("api.example.com"))).isEmpty)

  test("redirectTo rejects an ImposterHandle that isn't this engine's ImposterHandleLive"):
    // The reject arm never forces `built` and never touches the connector, so a null connector is
    // safe; any non-`ImposterHandleLive` handle drives it.
    val result =
      InterceptRuleBuilderLive[IO](null, Some("api.example.com"))
        .redirectTo(ForeignHandle)
        .attempt
        .unsafeRunSync()
    result match
      case Left(_: RiftError.InvalidDefinition) => ()
      case other => fail(s"expected InvalidDefinition, got $other")

  // issue #80 — `rule()` is the facade's catch-all form (host left unset), `rule(host)` the scoped
  // one. Both only construct the deferred builder, so the connector stays untouched.
  test("rule() seeds an all-hosts builder and rule(host) seeds a host-scoped one"):
    val handle = new InterceptHandleLive[IO](null)
    assert(hostOf(handle.rule()).isEmpty)
    assertEquals(hostOf(handle.rule("api.example.com")), Some("api.example.com"))

  test("an all-hosts builder accumulates every match in order, like the host-scoped form"):
    val a = get("/a")
    val b = get("/b")
    val builder = InterceptRuleBuilderLive[IO](null, None).when(a).when(b)
    assertEquals(matchesOf(builder), Vector(a, b))
    assert(hostOf(builder).isEmpty)

  /** A foreign `ImposterHandle[IO]` — not this engine's `ImposterHandleLive` — used only to reach
    * `redirectTo`'s reject arm. Every member dies loudly; none is exercised.
    */
  private object ForeignHandle extends ImposterHandle[IO]:
    private def nope[A]: A = throw new NotImplementedError(
      "ForeignHandle: only tests redirectTo reject"
    )
    private def raise[A]: IO[A] = IO.raiseError(new NotImplementedError)
    def port: Port = nope
    def uri: URI = nope
    def definition: IO[ImposterDefinition] = raise
    def addStub(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = raise
    def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = raise
    def replaceStubs(stubs: Vector[Stub]): IO[Unit] = raise
    def stubs: IO[Vector[Stub]] = raise
    def stub(id: StubId): IO[StubRef[IO]] = raise
    def recorded: IO[Vector[RecordedRequest]] = raise
    def recorded(matching: RequestMatch): IO[Vector[RecordedRequest]] = raise
    def recordedPage(filters: TailFilter*): IO[RecordedPage] = raise
    def recordedSince(cursor: Long, filters: TailFilter*): IO[RecordedPage] = raise
    def clearRecorded: IO[Unit] = raise
    def verify(matching: RequestMatch, times: Times): IO[Unit] = raise
    def verify(matching: RequestMatch, times: Int): IO[Unit] = raise
    def verifyNoInteractions: IO[Unit] = raise
    def startRecording(origin: URI, spec: RecordSpec): Resource[IO, RecordingHandle[IO]] =
      Resource.eval(raise)
    def scenarios: Scenarios[IO] = nope
    def space(flowId: FlowId): SpaceHandle[IO] = nope
    def flowState(flowId: FlowId): FlowStateHandle[IO] = nope
    def enable: IO[Unit] = raise
    def disable: IO[Unit] = raise
    def delete: IO[Unit] = raise
