package rift.zio

import java.net.URI

import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import rift.RiftError
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
import rift.bridge.{ImposterDefinition, InterceptGate, RecordSpec, TailEvent, TailFilter}

/** Pure-logic gate for the ZIO intercept rule builder (issue #34). The facade round-trip needs a
  * live engine (the bridge `EmbeddedSmokeSpec` covers that, on the JDK 22 job since #99), but the
  * deferred builder's accumulation — and the `redirectTo` cross-engine reject (issue #52, mirroring
  * the cats `InterceptBuilderSpec`) — are engine-free, and are exactly where a dropped `.when` or a
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
    ,
    // ── issue #101: force the replay fold ─────────────────────────────────────────────────────
    // The tests above only read the Scala-side `matches` accessor, so `built` — the foldLeft that
    // replays those matches onto the bridge builder — was never executed by any test. That is the
    // vacuous shape #82 shipped through: a test named "no earlier when is dropped" passing while
    // the facade-facing behaviour was wrong. These drive a terminal and read what the FACADE got.
    suite("replay fold (forced through a terminal)")(
      test("serve replays every accumulated when onto the facade, in order"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        val first = get("/admin")
        val second = onRequest.where(header("X-Env").is("prod"))
        for exit <- handle.rule("api.example.com").when(first).when(second).serve(ok).exit
        yield
          val sent = InterceptGate.facadePredicates(fake.lastBuilder)
          val rendered = sent.toString
          // blockingIO is attemptBlocking(...).refineToOrDie[RiftError], so the null engine's NPE
          // arrives as a defect. It is a liveness signal, not the proof: the facade evaluates its
          // receiver before its argument, so an NPE from elsewhere would also land here. The
          // predicate and host reads below are what actually gate the fold.
          assert(exit)(dies(isSubtype[NullPointerException](anything))) &&
          assertTrue(
            sent.size == (first.predicates ++ second.predicates).size,
            InterceptGate.facadeHost(fake.lastBuilder).contains("api.example.com"),
            // Position, not just presence: a reversed fold would keep the size and flip these.
            rendered.indexOf("/admin") >= 0,
            rendered.indexOf("X-Env") > rendered.indexOf("/admin")
          )
      ,
      test("forward replays the clauses too"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        val first = get("/admin")
        val second = onRequest.where(header("X-Env").is("prod"))
        // The target must carry a port: `parsePort` runs before the engine call, so a
        // scheme-carrying URL would throw there instead of at the null engine (#100).
        for exit <- handle
            .rule("api.example.com")
            .when(first)
            .when(second)
            .forward("real.example.com:443")
            .exit
        yield
          val sent = InterceptGate.facadePredicates(fake.lastBuilder)
          assert(exit)(dies(isSubtype[NullPointerException](anything))) &&
          assertTrue(
            fake.ruleCalls == 1,
            sent.size == (first.predicates ++ second.predicates).size
          )
      ,
      // #120: the typed overload must take the same per-terminal replay path as the string form —
      // the fold is where a dropped clause would silently widen the rule (#82).
      test("forward(port) replays the clauses too"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        val first = get("/admin")
        val second = onRequest.where(header("X-Env").is("prod"))
        for exit <- handle
            .rule("api.example.com")
            .when(first)
            .when(second)
            .forward(Port.from(4545).toOption.getOrElse(sys.error("4545 is a valid port")))
            .exit
        yield
          val sent = InterceptGate.facadePredicates(fake.lastBuilder)
          assert(exit)(dies(isSubtype[NullPointerException](anything))) &&
          assertTrue(
            fake.ruleCalls == 1,
            sent.size == (first.predicates ++ second.predicates).size
          )
      ,
      // The all-hosts seed is `host.fold(connector.rule())(...)` — its None branch is unreachable
      // from the accessor tests, and dropping it would silently scope a catch-all to a host.
      test("the all-hosts seed takes the no-host branch and still replays every clause"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        val first = get("/admin")
        val second = onRequest.where(header("X-Env").is("prod"))
        for exit <- handle.rule().when(first).when(second).serve(ok).exit
        yield
          val sent = InterceptGate.facadePredicates(fake.lastBuilder)
          assert(exit)(dies(isSubtype[NullPointerException](anything))) &&
          assertTrue(
            fake.ruleCalls == 1,
            // The discriminating assertion: both branches call `rule()`, so only the facade's own
            // `host` field distinguishes a catch-all from a host-scoped rule.
            InterceptGate.facadeHost(fake.lastBuilder).isEmpty,
            sent.size == (first.predicates ++ second.predicates).size
          )
      ,
      test("a terminal with no when sends the facade an empty predicate list"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        for exit <- handle.rule("api.example.com").serve(ok).exit
        // The gate seeds a sentinel predicate into every fresh facade builder, so this observes
        // the bridge's unconditional assignment OVERWRITING it — the facade's own constructor
        // already leaves `predicates` empty, which would satisfy a naive emptiness check.
        yield assert(exit)(dies(isSubtype[NullPointerException](anything))) &&
          assertTrue(fake.ruleCalls == 1, InterceptGate.facadePredicates(fake.lastBuilder).isEmpty)
      ,
      // `built` is a `def`, so every terminal mints a FRESH facade builder. That is the whole
      // reason InterceptConnector's scaladoc calls the zio/cats surfaces immune to the cross-fork
      // predicate bleed its own single-threaded assignment is vulnerable to. Nothing pinned it:
      // making `built` a `lazy val` — or hoisting the builder into the case class — leaves every
      // other test green while silently reintroducing the bleed.
      test("each terminal mints its own facade builder — the same builder value twice, twice"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        val first = get("/admin")
        // Two terminals on the SAME builder value. `when` returns a copy, so forking off it would
        // give each terminal its own instance and memoising `built` would go unnoticed — only
        // re-terminating one value forces the question of whether `built` is recomputed.
        val shared = handle.rule("api.example.com").when(first)
        for
          _ <- shared.serve(ok).exit
          _ <- shared.serve(ok).exit
        yield assertTrue(
          fake.ruleCalls == 2,
          InterceptGate.facadePredicates(fake.lastBuilder).size == first.predicates.size
        )
      ,
      test("a fork off a shared prefix does not inherit the sibling's later clauses"):
        val fake = new InterceptGate.BuilderRecordingIntercept
        val handle = InterceptHandleLive(InterceptGate.connector(fake))
        val first = get("/admin")
        val second = onRequest.where(header("X-Env").is("prod"))
        val shared = handle.rule("api.example.com").when(first)
        for
          _ <- shared.when(second).serve(ok).exit
          _ <- shared.serve(ok).exit // the prefix alone — must not carry `second`
        yield
          val rendered = InterceptGate.facadePredicates(fake.lastBuilder).toString
          assertTrue(
            fake.ruleCalls == 2,
            rendered.contains("/admin"),
            !rendered.contains("X-Env")
          )
    )
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
    def clearRecorded(filters: Chunk[TailFilter]): IO[RiftError, Unit] = die
    def clearProxyResponses: IO[RiftError, Unit] = die
    def verify(matching: RequestMatch, times: Times): IO[RiftError, Unit] = die
    def verify(matching: RequestMatch, times: Int): IO[RiftError, Unit] = die
    def verifyResult(
        matching: RequestMatch,
        details: VerifyDetail*
    ): IO[RiftError, VerificationResult] =
      die
    def verifyResult(
        matching: RequestMatch,
        times: Times,
        details: VerifyDetail*
    ): IO[RiftError, VerificationResult] = die
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
