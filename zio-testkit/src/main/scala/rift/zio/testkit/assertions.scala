package rift.zio.testkit

import zio.*
import zio.test.*

import rift.RiftError
import rift.dsl.RequestMatch
import rift.model.{Fields, Predicate, PredicateOp, Times}
import rift.model.matching.VerificationReport
import rift.zio.ImposterHandle

/** Readable zio-test assertions over `ImposterHandle`'s server-side verification (DESIGN.md §5.4).
  *
  * These fold `RiftError` into a `TestResult` at the assertion boundary — the one place doing so is
  * correct rather than a swallow, since `assertReceived(...)` is meant to be used directly inside a
  * zio-test `test { }` body, which needs a `TestResult`, not a leaked typed error. A
  * `VerificationFailed` renders the near-miss diff; any *other* `RiftError` (a communication
  * failure, an unknown imposter, ...) still fails the assertion loudly with its own message rather
  * than being silently treated as a pass.
  */
object assertions:

  def assertReceived(
      handle: ImposterHandle,
      matching: RequestMatch,
      times: Times = Times.atLeastOnce
  ): UIO[TestResult] =
    handle
      .verify(matching, times)
      .as(assertCompletes)
      .catchAll {
        case RiftError.VerificationFailed(report) =>
          ZIO.succeed(assertNever(renderVerificationFailure(matching, times, report)))
        case other =>
          ZIO.succeed(assertNever(other.message))
      }

  def assertNoInteractions(handle: ImposterHandle): UIO[TestResult] =
    handle.verifyNoInteractions
      .as(assertCompletes)
      .catchAll {
        case RiftError.VerificationFailed(report) =>
          ZIO.succeed(assertNever(s"✗ expected no interactions\n${report.render}"))
        case other =>
          ZIO.succeed(assertNever(other.message))
      }

  /** Polls `assertReceived` until it passes or `timeout` elapses — for async SUTs where the request
    * a test verifies may not have landed on the imposter yet. Returns the *last observed* result
    * either way, so a timeout still reports the final near-miss diff instead of a bare "timed out".
    */
  def eventuallyReceived(
      handle: ImposterHandle,
      matching: RequestMatch,
      times: Times = Times.atLeastOnce,
      timeout: Duration = 5.seconds
  ): UIO[TestResult] =
    for
      last <- Ref.make(assertNever("eventuallyReceived: no attempt completed within the timeout"))
      poll = assertReceived(handle, matching, times).tap(last.set)
      // interrupting the loop on timeout discards whatever attempt is in flight, which is why the
      // last *completed* attempt is tracked separately in `last` rather than read off `repeat`'s
      // own return value.
      _ <- poll
        .repeat(Schedule.spaced(100.millis).untilInput[TestResult](_.isSuccess))
        .timeout(timeout)
      result <- last.get
    yield result

  /** `private[testkit]` rather than fully private so the gate test (`AssertionsSpec`) can assert on
    * the rendered near-miss text directly — introspecting a `TestResult`'s internal `TestTrace` to
    * recover a label is not a stable public API, so this pure function is the intended seam.
    */
  private[testkit] def renderVerificationFailure(
      matching: RequestMatch,
      times: Times,
      report: VerificationReport
  ): String =
    s"${renderExpectation(matching, times)}\n${report.render}"

  private def renderExpectation(matching: RequestMatch, times: Times): String =
    val predicateSummary = matching.predicates.map(renderPredicate).mkString(", ")
    val what = if predicateSummary.isEmpty then "<any request>" else predicateSummary
    s"expected $what — ${renderTimes(times)}"

  private def renderPredicate(p: Predicate): String =
    def fields(label: String, fs: Fields): String =
      fs.entries.map((k, v) => s"$k $label ${v.render}").mkString(" ")
    p.op match
      case PredicateOp.Equals(fs) => fields("=", fs)
      case PredicateOp.DeepEquals(fs) => fields("deepEquals", fs)
      case PredicateOp.Contains(fs) => fields("contains", fs)
      case PredicateOp.StartsWith(fs) => fields("startsWith", fs)
      case PredicateOp.EndsWith(fs) => fields("endsWith", fs)
      case PredicateOp.Matches(fs) => fields("matches", fs)
      case PredicateOp.Exists(fs) => fs.entries.map((k, _) => s"$k exists").mkString(" ")
      case PredicateOp.And(ps) => ps.map(renderPredicate).mkString(" and ")
      case PredicateOp.Or(ps) => ps.map(renderPredicate).mkString(" or ")
      case PredicateOp.Not(inner) => s"not(${renderPredicate(inner)})"
      case PredicateOp.Inject(script) => s"inject($script)"

  private def renderTimes(times: Times): String = times match
    case Times.Exactly(n) => s"exactly $n time(s)"
    case Times.AtLeast(n) => s"at least $n time(s)"
    case Times.AtMost(n) => s"at most $n time(s)"
    case Times.Between(lo, hi) => s"between $lo and $hi times"
