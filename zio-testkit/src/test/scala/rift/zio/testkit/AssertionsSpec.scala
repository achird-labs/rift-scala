package rift.zio.testkit

import java.time.Instant

import zio.*
import zio.test.*

import rift.RiftError
import rift.dsl.get
import rift.json.Json
import rift.model.{Fields, Headers, Method, PredicateOp, RecordedRequest, Times}
import rift.model.matching.{MissedRequest, PredicateFailure, VerificationReport}

/** Covers `assertions` WITHOUT a live engine (DESIGN.md §5.4): a hand-rolled `ImposterHandle`
  * (`FakeImposterHandle`) fakes `verify`/`verifyNoInteractions`, exercising only the fold from
  * `RiftError` to `TestResult` at the assertion boundary — the contract this module owns.
  *
  * Engine-dependent paths (the real `verify`/`verifyNoInteractions` wire calls, `RiftTestKit`'s
  * layers/`imposter`/`space`, and the `aspects`) are covered by the conformance corpus (#6) against
  * a live engine, not here.
  */
object AssertionsSpec extends ZIOSpecDefault:

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
    * a real engine's `VerificationException` (see `RiftError.translateReport`), hand-built here so
    * the gate doesn't need a live engine to produce it.
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

  def spec = suite("assertions")(
    suite("assertReceived")(
      test("verify succeeding yields a passing TestResult") {
        val handle = new FakeImposterHandle(verifyResult = ZIO.unit)
        assertions.assertReceived(handle, someMatch).map(r => assertTrue(r.isSuccess))
      },
      test("VerificationFailed yields a failing TestResult, not a leaked RiftError") {
        val handle =
          new FakeImposterHandle(verifyResult = ZIO.fail(RiftError.VerificationFailed(report)))
        // the return type is UIO[TestResult]: if RiftError leaked out of assertReceived, this
        // wouldn't even compile as a UIO.
        val result: UIO[TestResult] = assertions.assertReceived(handle, someMatch)
        result.map(r => assertTrue(r.isFailure))
      },
      test("the rendered near-miss diff carries the missed request's field/expected/got text") {
        val message = assertions.renderVerificationFailure(someMatch, Times.atLeastOnce, report)
        assertTrue(
          message.contains("path"),
          message.contains("/api/users/1"),
          message.contains("/api/users/2")
        )
      },
      test("a non-VerificationFailed RiftError still fails the assertion, not swallowed") {
        val handle =
          new FakeImposterHandle(verifyResult = ZIO.fail(RiftError.EngineUnavailable("boom", None)))
        assertions.assertReceived(handle, someMatch).map(r => assertTrue(r.isFailure))
      }
    ),
    suite("assertNoInteractions")(
      test("verifyNoInteractions succeeding yields a passing TestResult") {
        val handle = new FakeImposterHandle(verifyNoInteractionsResult = ZIO.unit)
        assertions.assertNoInteractions(handle).map(r => assertTrue(r.isSuccess))
      },
      test("verifyNoInteractions failing yields a failing TestResult") {
        val handle = new FakeImposterHandle(verifyNoInteractionsResult =
          ZIO.fail(RiftError.VerificationFailed(report))
        )
        assertions.assertNoInteractions(handle).map(r => assertTrue(r.isFailure))
      }
    )
  )
