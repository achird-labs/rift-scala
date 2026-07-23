package rift.cats

import java.net.URI

import _root_.cats.effect.{IO, Resource}
import _root_.cats.effect.unsafe.implicits.global

import munit.FunSuite

import rift.RiftError
import rift.bridge.{ImposterDefinition, InterceptGate, RecordSpec, RecordedPage, TailFilter}
import rift.dsl.*
import rift.model.{
  FlowId,
  Port,
  RecordedRequest,
  Stub,
  StubId,
  Times,
  VerificationResult,
  VerifyDetail
}

/** Pure-logic gate for the Cats intercept rule builder (issue #45, mirrors the ZIO
  * `InterceptBuilderSpec` for #34). The facade round-trip needs a live engine (the bridge
  * `EmbeddedSmokeSpec` covers that, on the JDK 22 job since #99), but the deferred builder's
  * accumulation — and the `redirectTo` cross-engine reject — are engine-free, and are exactly where
  * a dropped `.when` or a mis-typed handle would hide, so they get direct regression tests.
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

  // ── issue #101: force the replay fold ────────────────────────────────────────────────────────
  // The tests above only read the Scala-side `matches` accessor, so `built` — the foldLeft that
  // replays those matches onto the bridge builder — was never executed by any test. That is the
  // vacuous shape #82 shipped through. These drive a terminal and assert on what the FACADE got.
  //
  // `blockingF` is `Sync[F].blocking`, so unlike the ZIO surface the null engine's NPE arrives as
  // a *raised error*, not a defect. It is a liveness signal, not the proof: the facade evaluates
  // its receiver before its argument, so an NPE from elsewhere would also land here. The predicate
  // and host reads are what actually gate the fold.
  private def runToNpe[A](fa: IO[A]): Unit =
    fa.attempt.unsafeRunSync() match
      case Left(_: NullPointerException) => ()
      case other => fail(s"expected the null engine's NullPointerException, got $other")

  test("serve replays every accumulated when onto the facade, in order"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val handle = new InterceptHandleLive[IO](InterceptGate.connector(fake))
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    runToNpe(handle.rule("api.example.com").when(first).when(second).serve(ok))
    val sent = InterceptGate.facadePredicates(fake.lastBuilder)
    assertEquals(sent.size, (first.predicates ++ second.predicates).size)
    assertEquals(InterceptGate.facadeHost(fake.lastBuilder), Some("api.example.com"))
    // Position, not just presence: a reversed fold would keep the size and flip these.
    val rendered = sent.toString
    assert(rendered.indexOf("/admin") >= 0, rendered)
    assert(rendered.indexOf("X-Env") > rendered.indexOf("/admin"), rendered)

  test("forward replays the clauses too"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val handle = new InterceptHandleLive[IO](InterceptGate.connector(fake))
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    // The target must carry a port: `parsePort` runs before the engine call, so a scheme-carrying
    // URL would throw there instead of at the null engine (#100).
    runToNpe(
      handle
        .rule("api.example.com")
        .when(first)
        .when(second)
        .forward("real.example.com:443")
    )
    assertEquals(fake.ruleCalls, 1)
    val sent = InterceptGate.facadePredicates(fake.lastBuilder)
    assertEquals(sent.size, (first.predicates ++ second.predicates).size)

  // The all-hosts seed is `host.fold(connector.rule())(...)` — its None branch is unreachable from
  // the accessor tests, and dropping it would silently scope a catch-all to a host.
  test("the all-hosts seed takes the no-host branch and still replays every clause"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val handle = new InterceptHandleLive[IO](InterceptGate.connector(fake))
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    runToNpe(handle.rule().when(first).when(second).serve(ok))
    assertEquals(fake.ruleCalls, 1)
    // The discriminating assertion: both branches call `rule()`, so only the facade's own `host`
    // field distinguishes a catch-all from a host-scoped rule.
    assertEquals(InterceptGate.facadeHost(fake.lastBuilder), None)
    assertEquals(
      InterceptGate.facadePredicates(fake.lastBuilder).size,
      (first.predicates ++ second.predicates).size
    )

  test("a terminal with no when sends the facade an empty predicate list"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val handle = new InterceptHandleLive[IO](InterceptGate.connector(fake))
    runToNpe(handle.rule("api.example.com").serve(ok))
    assertEquals(fake.ruleCalls, 1)
    // The gate seeds a sentinel predicate into every fresh facade builder, so this observes the
    // bridge's unconditional assignment OVERWRITING it — the facade's own constructor already
    // leaves `predicates` empty, which would satisfy a naive emptiness check.
    assert(InterceptGate.facadePredicates(fake.lastBuilder).isEmpty)

  // `built` is a `def`, so every terminal mints a FRESH facade builder — the reason
  // InterceptConnector's scaladoc calls the zio/cats surfaces immune to the cross-fork predicate
  // bleed its own single-threaded assignment is vulnerable to. Nothing pinned it: making `built` a
  // `lazy val` leaves every other test green while silently reintroducing the bleed.
  test("each terminal mints its own facade builder — the same builder value twice, twice"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val handle = new InterceptHandleLive[IO](InterceptGate.connector(fake))
    val first = get("/admin")
    // Two terminals on the SAME builder value. `when` returns a copy, so forking off it would give
    // each terminal its own instance and memoising `built` would go unnoticed — only re-terminating
    // one value forces the question of whether `built` is recomputed.
    val shared = handle.rule("api.example.com").when(first)
    runToNpe(shared.serve(ok))
    runToNpe(shared.serve(ok))
    assertEquals(fake.ruleCalls, 2)
    assertEquals(InterceptGate.facadePredicates(fake.lastBuilder).size, first.predicates.size)

  test("a fork off a shared prefix does not inherit the sibling's later clauses"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val handle = new InterceptHandleLive[IO](InterceptGate.connector(fake))
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    val shared = handle.rule("api.example.com").when(first)
    runToNpe(shared.when(second).serve(ok))
    runToNpe(shared.serve(ok)) // the prefix alone — must not carry `second`
    assertEquals(fake.ruleCalls, 2)
    val rendered = InterceptGate.facadePredicates(fake.lastBuilder).toString
    assert(rendered.contains("/admin"), rendered)
    assert(!rendered.contains("X-Env"), s"fork leaked into the sibling: $rendered")

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
    def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[StubRef[IO]] = raise
    def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = raise
    def replaceStubs(stubs: Vector[Stub]): IO[Unit] = raise
    def stubs: IO[Vector[Stub]] = raise
    def stub(id: StubId): IO[StubRef[IO]] = raise
    def recorded: IO[Vector[RecordedRequest]] = raise
    def recorded(matching: RequestMatch): IO[Vector[RecordedRequest]] = raise
    def recordedPage(filters: TailFilter*): IO[RecordedPage] = raise
    def recordedSince(cursor: Long, filters: TailFilter*): IO[RecordedPage] = raise
    def clearRecorded: IO[Unit] = raise
    def clearRecorded(filters: rift.bridge.TailFilter*): IO[Unit] = raise
    def clearProxyResponses: IO[Unit] = raise
    def verify(matching: RequestMatch, times: Times): IO[Unit] = raise
    def verify(matching: RequestMatch, times: Int): IO[Unit] = raise
    def verifyResult(matching: RequestMatch, details: VerifyDetail*): IO[VerificationResult] = raise
    def verifyResult(
        matching: RequestMatch,
        times: Times,
        details: VerifyDetail*
    ): IO[VerificationResult] = raise
    def verifyNoInteractions: IO[Unit] = raise
    def startRecording(origin: URI, spec: RecordSpec): Resource[IO, RecordingHandle[IO]] =
      Resource.eval(raise)
    def scenarios: Scenarios[IO] = nope
    def space(flowId: FlowId): SpaceHandle[IO] = nope
    def flowState(flowId: FlowId): FlowStateHandle[IO] = nope
    def enable: IO[Unit] = raise
    def disable: IO[Unit] = raise
    def delete: IO[Unit] = raise
