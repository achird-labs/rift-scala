package rift.model.matching

import rift.model.RecordedRequest

final case class MissedRequest(request: RecordedRequest, failures: Vector[PredicateFailure])

/** The near-miss table for a verification (DESIGN.md §5.1.4) — best-effort explanation only, the
  * engine's `verify` endpoint stays authoritative.
  */
final case class VerificationReport(
    matched: Vector[RecordedRequest],
    missed: Vector[MissedRequest]
):
  def render: String =
    if missed.isEmpty then s"All ${matched.size} recorded request(s) matched."
    else
      val rows = missed.map { m =>
        val header = s"  ${m.request.method} ${m.request.path}"
        val lines = m.failures.map { f =>
          s"    ${f.field}: expected ${f.expected}, got ${f.actual.getOrElse("<missing>")}"
        }
        (header +: lines).mkString("\n")
      }
      s"${matched.size} matched, ${missed.size} missed:\n" + rows.mkString("\n")
