package rift.fs2

import java.net.URI
import java.time.Instant

import scala.concurrent.duration.*

import _root_.cats.effect.{IO, Ref, Resource}
import munit.CatsEffectSuite

import rift.bridge.{ImposterDefinition, RecordSpec, RecordedPage, TailFilter}
import rift.cats.{FlowStateHandle, ImposterHandle, RecordingHandle, Scenarios, SpaceHandle, StubRef}
import rift.dsl.*
import rift.json.Json
import rift.model.Method.GET
import rift.model.{
  FlowId,
  Headers,
  Port,
  RecordedRequest,
  Stub,
  StubId,
  Times,
  VerificationResult,
  VerifyDetail
}

/** The `filters` passed to `requestStream`/`requestEvents` (issue #37, Part 1) must reach the
  * underlying cursor reads unchanged, on **both** the baseline (`recordedPage`) and the cursor
  * (`recordedSince`) paths — the two take `filters` as separate parameters, an easy place to drop
  * or swap one. A capturing `ImposterHandle[IO]` records what it was actually called with.
  */
class FilterThreadingSpec extends CatsEffectSuite:

  private def rr(path: String): RecordedRequest =
    RecordedRequest(
      GET,
      path,
      Map.empty,
      Headers.empty,
      None,
      None,
      Instant.EPOCH,
      None,
      None,
      Map.empty,
      Json.Null
    )

  private def unused: IO[Nothing] = IO.raiseError(new NotImplementedError("unused in this spec"))

  /** Records the filter list each cursor read is called with; every page carries one request so the
    * tail advances (baseline → recordedPage, then cursor → recordedSince).
    */
  private final class CapturingHandle(calls: Ref[IO, Vector[(String, Vector[TailFilter])]])
      extends ImposterHandle[IO]:
    def recordedPage(filters: TailFilter*): IO[RecordedPage] =
      calls.update(_ :+ ("page" -> filters.toVector)) *>
        IO.pure(RecordedPage(Vector(rr("/a")), Some(1L), truncated = false))
    def recordedSince(cursor: Long, filters: TailFilter*): IO[RecordedPage] =
      calls.update(_ :+ ("since" -> filters.toVector)) *>
        IO.pure(RecordedPage(Vector(rr("/b")), Some(cursor + 1), truncated = false))

    def port: Port = ???
    def uri: URI = ???
    def definition: IO[ImposterDefinition] = unused
    def addStub(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = unused
    def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[StubRef[IO]] = unused
    def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = unused
    def replaceStubs(stubs: Vector[Stub]): IO[Unit] = unused
    def stubs: IO[Vector[Stub]] = unused
    def stub(id: StubId): IO[StubRef[IO]] = unused
    def recorded: IO[Vector[RecordedRequest]] = unused
    def recorded(matching: RequestMatch): IO[Vector[RecordedRequest]] = unused
    def clearRecorded: IO[Unit] = unused
    def clearRecorded(filters: rift.bridge.TailFilter*): IO[Unit] = unused
    def clearProxyResponses: IO[Unit] = unused
    def verify(matching: RequestMatch, times: Times): IO[Unit] = unused
    def verify(matching: RequestMatch, times: Int): IO[Unit] = unused
    def verifyResult(matching: RequestMatch, details: VerifyDetail*): IO[VerificationResult] =
      unused
    def verifyResult(
        matching: RequestMatch,
        times: Times,
        details: VerifyDetail*
    ): IO[VerificationResult] = unused
    def verifyNoInteractions: IO[Unit] = unused
    def startRecording(origin: URI, spec: RecordSpec): Resource[IO, RecordingHandle[IO]] =
      Resource.eval(unused)
    def scenarios: Scenarios[IO] = ???
    def space(flowId: FlowId): SpaceHandle[IO] = ???
    def flowState(flowId: FlowId): FlowStateHandle[IO] = ???
    def enable: IO[Unit] = unused
    def disable: IO[Unit] = unused
    def delete: IO[Unit] = unused

  /** The space-scoped counterpart (#129): the space tail takes the same two cursor reads, so the
    * same drop-a-filter mistake is possible on it and needs the same gate.
    */
  private final class CapturingSpace(calls: Ref[IO, Vector[(String, Vector[TailFilter])]])
      extends SpaceHandle[IO]:
    def recordedPage(filters: TailFilter*): IO[RecordedPage] =
      calls.update(_ :+ ("page" -> filters.toVector)) *>
        IO.pure(RecordedPage(Vector(rr("/a")), Some(1L), truncated = false))
    def recordedSince(cursor: Long, filters: TailFilter*): IO[RecordedPage] =
      calls.update(_ :+ ("since" -> filters.toVector)) *>
        IO.pure(RecordedPage(Vector(rr("/b")), Some(cursor + 1), truncated = false))

    def flowId: FlowId = ???
    def addStub(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = unused
    def stubs: IO[Vector[Stub]] = unused
    def recorded: IO[Vector[RecordedRequest]] = unused
    def verify(matching: RequestMatch, times: Times): IO[Unit] = unused
    def verifyResult(matching: RequestMatch, details: VerifyDetail*): IO[VerificationResult] =
      unused
    def verifyResult(
        matching: RequestMatch,
        times: Times,
        details: VerifyDetail*
    ): IO[VerificationResult] = unused
    def delete: IO[Unit] = unused

  private val flow = FlowId.from("flow-1").fold(e => fail(s"bad flow id: $e"), identity)
  private val filters = Vector(TailFilter.Header("h", "v"), TailFilter.Flow(flow))

  test(
    "requestStream threads filters unchanged to recordedPage (baseline) and recordedSince (cursor)"
  ) {
    import syntax.requestStream
    for
      calls <- Ref.of[IO, Vector[(String, Vector[TailFilter])]](Vector.empty)
      handle = new CapturingHandle(calls)
      out <- handle.requestStream(1.milli, filters).take(2).compile.toList
      seen <- calls.get
    yield
      assertEquals(out.map(_.path), List("/a", "/b"))
      assertEquals(seen.take(2), Vector("page" -> filters, "since" -> filters))
  }

  test("requestEvents threads filters unchanged on both paths too") {
    import syntax.requestEvents
    import rift.bridge.TailEvent
    for
      calls <- Ref.of[IO, Vector[(String, Vector[TailFilter])]](Vector.empty)
      handle = new CapturingHandle(calls)
      out <- handle.requestEvents(1.milli, filters).take(2).compile.toList
      seen <- calls.get
    yield
      assertEquals(out, List(TailEvent.Received(rr("/a")), TailEvent.Received(rr("/b"))))
      assertEquals(seen.take(2), Vector("page" -> filters, "since" -> filters))
  }

  // A space read is already scoped to its own flow, and the facade rejects a second `flowId`
  // clause — so unlike the imposter cases above, these must thread a Flow-free list. Using
  // `filters` here would pin a call shape that always throws against a real space.
  private val spaceFilters = Vector(TailFilter.Header("h", "v"), TailFilter.Path("/a"))

  test("the space tail threads filters unchanged on both paths (#129)") {
    import syntax.requestStream
    for
      calls <- Ref.of[IO, Vector[(String, Vector[TailFilter])]](Vector.empty)
      space = new CapturingSpace(calls)
      out <- space.requestStream(1.milli, spaceFilters).take(2).compile.toList
      seen <- calls.get
    yield
      assertEquals(out.map(_.path), List("/a", "/b"))
      assertEquals(seen.take(2), Vector("page" -> spaceFilters, "since" -> spaceFilters))
  }

  test("the space requestEvents threads filters unchanged on both paths (#129)") {
    import syntax.requestEvents
    import rift.bridge.TailEvent
    for
      calls <- Ref.of[IO, Vector[(String, Vector[TailFilter])]](Vector.empty)
      space = new CapturingSpace(calls)
      out <- space.requestEvents(1.milli, spaceFilters).take(2).compile.toList
      seen <- calls.get
    yield
      assertEquals(out, List(TailEvent.Received(rr("/a")), TailEvent.Received(rr("/b"))))
      assertEquals(seen.take(2), Vector("page" -> spaceFilters, "since" -> spaceFilters))
  }
