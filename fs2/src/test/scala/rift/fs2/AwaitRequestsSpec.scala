package rift.fs2

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeoutException

import scala.concurrent.duration.*

import _root_.cats.effect.{IO, Ref, Resource}
import munit.CatsEffectSuite

import rift.bridge.{ImposterDefinition, RecordSpec, RecordedPage}
import rift.cats.{FlowStateHandle, ImposterHandle, Scenarios, SpaceHandle, StubRef}
import rift.dsl.*
import rift.json.Json
import rift.model.Method.GET
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

/** `awaitRequests` — the await-n-requests idiom (DESIGN.md §5.7, issue #9) — layered directly on
  * `ImposterHandle[F]`, so it is exercised here against a scripted handle rather than against
  * `RequestStream`/`pipes` in isolation (already covered by `RequestStreamSpec`/`PipesSpec`).
  */
class AwaitRequestsSpec extends CatsEffectSuite:

  private def rr(path: String): RecordedRequest =
    RecordedRequest(
      method = GET,
      path = path,
      query = Map.empty,
      headers = rift.model.Headers.empty,
      body = None,
      bodyText = None,
      timestamp = Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )

  private def unimplemented: IO[Nothing] =
    IO.raiseError(new NotImplementedError("unused in AwaitRequestsSpec"))

  /** An `ImposterHandle[IO]` whose `recordedPage`/`recordedSince` pull from a fixed script of pages
    * in order (then idle on an empty, cursor-holding page, mirroring `RequestStream.build`'s own
    * hold semantics). Every other member is unused by `awaitRequests` and left unimplemented.
    */
  private def scriptedHandle(pages: List[RecordedPage]): IO[ImposterHandle[IO]] =
    Ref.of[IO, List[RecordedPage]](pages).map { remaining =>
      new ImposterHandle[IO]:
        def port: Port = ???
        def uri: URI = ???
        def definition: IO[ImposterDefinition] = unimplemented
        def addStub(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = unimplemented
        def addStub(stub: StubBuilder[StubPhase.Complete], index: Int): IO[StubRef[IO]] =
          unimplemented
        def addStubFirst(stub: StubBuilder[StubPhase.Complete]): IO[StubRef[IO]] = unimplemented
        def replaceStubs(stubs: Vector[Stub]): IO[Unit] = unimplemented
        def stubs: IO[Vector[Stub]] = unimplemented
        def stub(id: StubId): IO[StubRef[IO]] = unimplemented
        def recorded: IO[Vector[RecordedRequest]] = unimplemented
        def recorded(matching: RequestMatch): IO[Vector[RecordedRequest]] = unimplemented
        def startRecording(
            origin: URI,
            spec: RecordSpec
        ): Resource[IO, rift.cats.RecordingHandle[IO]] = Resource.eval(unimplemented)

        def recordedPage(filters: rift.bridge.TailFilter*): IO[RecordedPage] =
          remaining.modify {
            case head :: tail => (tail, head)
            case Nil => (Nil, RecordedPage(Vector.empty, None, truncated = false))
          }

        def recordedSince(cursor: Long, filters: rift.bridge.TailFilter*): IO[RecordedPage] =
          remaining.modify {
            case head :: tail => (tail, head)
            case Nil => (Nil, RecordedPage(Vector.empty, Some(cursor), truncated = false))
          }

        def clearRecorded: IO[Unit] = unimplemented

        def clearRecorded(filters: rift.bridge.TailFilter*): IO[Unit] = unimplemented

        def clearProxyResponses: IO[Unit] = unimplemented
        def verify(matching: RequestMatch, times: Times = Times.atLeastOnce): IO[Unit] =
          unimplemented
        def verify(matching: RequestMatch, times: Int): IO[Unit] = unimplemented
        def verifyResult(matching: RequestMatch, details: VerifyDetail*): IO[VerificationResult] =
          unimplemented
        def verifyResult(
            matching: RequestMatch,
            times: Times,
            details: VerifyDetail*
        ): IO[VerificationResult] = unimplemented
        def verifyNoInteractions: IO[Unit] = unimplemented
        def scenarios: Scenarios[IO] = ???
        def space(flowId: FlowId): SpaceHandle[IO] = ???
        def flowState(flowId: FlowId): FlowStateHandle[IO] = ???
        def enable: IO[Unit] = unimplemented
        def disable: IO[Unit] = unimplemented
        def delete: IO[Unit] = unimplemented
    }

  test("completion path: returns exactly `count` matching requests once enough have been seen") {
    val m = on(GET, "/x")
    val pages = List(
      RecordedPage(Vector(rr("/x"), rr("/y")), Some(1L), truncated = false),
      RecordedPage(Vector(rr("/y"), rr("/x"), rr("/x")), Some(2L), truncated = false)
    )
    for
      handle <- scriptedHandle(pages)
      out <- awaitRequests(handle, m, count = 3, timeout = 2.seconds)
    yield assertEquals(out.map(_.path), Vector("/x", "/x", "/x"))
  }

  test("timeout path: fails with a TimeoutException when `count` is never reached") {
    val m = on(GET, "/x")
    val pages = List(
      RecordedPage(Vector(rr("/y")), Some(1L), truncated = false)
    )
    for
      handle <- scriptedHandle(pages)
      _ <- interceptIO[TimeoutException](awaitRequests(handle, m, count = 5, timeout = 200.millis))
    yield ()
  }
