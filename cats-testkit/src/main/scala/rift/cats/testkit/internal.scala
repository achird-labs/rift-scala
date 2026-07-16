package rift.cats.testkit

import _root_.cats.MonadThrow
import _root_.cats.syntax.all.*

import rift.RiftError
import rift.dsl.RequestMatch
import rift.cats.ImposterHandle
import rift.model.{Fields, Predicate, PredicateOp, Times}
import rift.model.matching.VerificationReport

/** The assertion core shared by the munit and weaver glue (DESIGN.md §5.8), so the near-miss diff
  * and the `RiftError`-folding behaviour are identical regardless of which test framework a user
  * picks. `private[testkit]`: not part of the public API, just the seam the gate test
  * (`AssertionsSpec`) exercises directly instead of only indirectly through a framework's own
  * assertion vocabulary.
  */
private[testkit] object internal:

  /** Runs `handle.verify` and folds its `F[Unit]` outcome into an `A` at the assertion boundary —
    * shared by `assertReceived` (munit) and `expectReceived` (weaver). `VerificationFailed` renders
    * the near-miss diff via `renderVerificationFailure`; any *other* `RiftError` still runs
    * `onFailure` with its own message rather than being silently treated as a pass. A
    * non-`RiftError` `Throwable` is re-raised rather than folded: `ImposterHandle`'s contract only
    * ever raises `RiftError` (DESIGN.md §5.2, D3), so seeing anything else here means something
    * upstream broke in a way this assertion boundary has no business masking.
    */
  def foldVerification[F[_], A](
      handle: ImposterHandle[F],
      matching: RequestMatch,
      times: Times
  )(onSuccess: F[A])(onFailure: String => F[A])(using F: MonadThrow[F]): F[A] =
    handle.verify(matching, times).flatMap(_ => onSuccess).handleErrorWith {
      case RiftError.VerificationFailed(report) =>
        onFailure(renderVerificationFailure(matching, times, report))
      case riftError: RiftError =>
        onFailure(riftError.message)
      case other =>
        F.raiseError(other)
    }

  /** Same fold as [[foldVerification]], over `verifyNoInteractions` instead — shared by
    * `assertNoInteractions` (munit) and `expectNoInteractions` (weaver).
    */
  def foldNoInteractions[F[_], A](
      handle: ImposterHandle[F]
  )(onSuccess: F[A])(onFailure: String => F[A])(using F: MonadThrow[F]): F[A] =
    handle.verifyNoInteractions.flatMap(_ => onSuccess).handleErrorWith {
      case RiftError.VerificationFailed(report) =>
        onFailure(s"expected no interactions\n${report.render}")
      case riftError: RiftError =>
        onFailure(riftError.message)
      case other =>
        F.raiseError(other)
    }

  /** The near-miss diff: an expectation header line + `report.render` (mirrors the zio-testkit
    * renderer so the two backends read identically).
    */
  def renderVerificationFailure(
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
