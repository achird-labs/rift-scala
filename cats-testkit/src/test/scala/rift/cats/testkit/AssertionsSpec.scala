package rift.cats.testkit

import java.time.Instant

import _root_.cats.effect.IO
import _root_.munit.CatsEffectSuite

import rift.RiftError
import rift.dsl.get
import rift.json.Json
import rift.model.{Fields, Headers, Method, PredicateOp, RecordedRequest, Times}
import rift.model.matching.{MissedRequest, PredicateFailure, VerificationReport}

/** Covers the shared assertion core (`internal`) WITHOUT a live engine (DESIGN.md §5.8): a
  * hand-rolled `ImposterHandle[IO]` (`FakeImposterHandle`) fakes `verify`/`verifyNoInteractions`,
  * exercising only the fold from `RiftError` to a result at the assertion boundary — the contract
  * this module owns, and the one both the munit glue's `RiftSuite.assertReceived` and the weaver
  * glue's `expectations.expectReceived` are built on (`internal.foldVerification`).
  *
  * `onFailure` here is `msg => IO.raiseError(new RuntimeException(msg))`, standing in for the real
  * glue's `msg => IO(fail(msg))` (munit) / `msg => IO.pure(Expectations.Helpers.failure(msg))`
  * (weaver) — it exercises the exact same fold `internal.foldVerification` performs, without
  * pulling in either framework's own suite machinery. `RiftSuite`'s fixture and the weaver
  * `GlobalResource` wiring both need a live engine and are covered by the conformance corpus (#13)
  * instead.
  */
class AssertionsSpec extends CatsEffectSuite:

  private def recordedRequest(path: String): RecordedRequest =
    RecordedRequest(
      method = Method.GET,
      path = path,
      query = Map.empty,
      headers = Headers.empty,
      body = None,
      bodyText = None,
      timestamp = Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )

  private val someMatch = get("/api/users/1")

  /** One missed request with one failed predicate — the shape `RiftError.fromThrowable` builds from
    * a real engine's `VerificationException`, hand-built here so the gate doesn't need a live
    * engine to produce it.
    */
  private val report = VerificationReport(
    matched = Vector.empty,
    missed = Vector(
      MissedRequest(
        recordedRequest("/api/users/2"),
        Vector(
          PredicateFailure(
            PredicateOp.Equals(Fields.empty),
            field = "path",
            expected = "/api/users/1",
            actual = Some("/api/users/2")
          )
        )
      )
    )
  )

  private def failing(msg: String): IO[Unit] = IO.raiseError(new RuntimeException(msg))

  test("renderVerificationFailure carries the missed request's field/expected/got text") {
    val message = internal.renderVerificationFailure(someMatch, Times.atLeastOnce, report)
    assert(message.contains("path"), message)
    assert(message.contains("/api/users/1"), message)
    assert(message.contains("/api/users/2"), message)
  }

  test("foldVerification: verify succeeding runs onSuccess, not onFailure") {
    val handle = new FakeImposterHandle(verifyOutcome = IO.unit)
    internal.foldVerification(handle, someMatch, Times.atLeastOnce)(IO.unit)(failing)
  }

  test("foldVerification: VerificationFailed fails with the rendered near-miss, not swallowed") {
    val handle =
      new FakeImposterHandle(verifyOutcome = IO.raiseError(RiftError.VerificationFailed(report)))
    internal
      .foldVerification(handle, someMatch, Times.atLeastOnce)(IO.unit)(failing)
      .attempt
      .map {
        case Left(e) =>
          assert(e.getMessage.contains("path"), e.getMessage)
          assert(e.getMessage.contains("/api/users/2"), e.getMessage)
        case Right(()) => fail("expected foldVerification to run onFailure, not onSuccess")
      }
  }

  test("foldVerification: a non-VerificationFailed RiftError still fails, not swallowed") {
    val handle =
      new FakeImposterHandle(verifyOutcome =
        IO.raiseError(RiftError.EngineUnavailable("boom", None))
      )
    internal
      .foldVerification(handle, someMatch, Times.atLeastOnce)(IO.unit)(failing)
      .attempt
      .map {
        case Left(e) => assert(e.getMessage.contains("boom"), e.getMessage)
        case Right(()) => fail("expected foldVerification to run onFailure, not onSuccess")
      }
  }

  test("foldNoInteractions: verifyNoInteractions succeeding runs onSuccess") {
    val handle = new FakeImposterHandle(verifyNoInteractionsResult = IO.unit)
    internal.foldNoInteractions(handle)(IO.unit)(failing)
  }

  test("foldNoInteractions: verifyNoInteractions failing runs onFailure, not swallowed") {
    val handle = new FakeImposterHandle(verifyNoInteractionsResult =
      IO.raiseError(RiftError.VerificationFailed(report))
    )
    internal
      .foldNoInteractions(handle)(IO.unit)(failing)
      .attempt
      .map {
        case Left(e) => assert(e.getMessage.contains("expected no interactions"), e.getMessage)
        case Right(()) => fail("expected foldNoInteractions to run onFailure, not onSuccess")
      }
  }
