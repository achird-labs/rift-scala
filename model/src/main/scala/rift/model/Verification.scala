package rift.model

import rift.json.Json

/** Which extra detail a `verifyResult` call should attach to its answer — mirrors rift-java's
  * `verify.VerifyDetail` enum case for case (DESIGN.md, issue #88).
  */
enum VerifyDetail:
  case Requests
  case Closest

/** One predicate a near-miss candidate failed, alongside the actual value it produced. Lossless (a
  * real `Predicate` plus the raw `Json` actual) — deliberately not the flattened `String` summary
  * `matching.PredicateFailure` uses, since that shape exists for the best-effort
  * `VerificationReport` error-path rendering, not for response data.
  */
final case class FailedPredicate(predicate: Predicate, actual: Json)

/** The closest non-matching request to a verification's `matching`, and every predicate it failed.
  */
final case class ClosestMiss(request: RecordedRequest, failedPredicates: Vector[FailedPredicate])

/** The facade's non-throwing verification outcome (`verifyResult`, issue #88) — `verify`'s value
  * counterpart, so "how many requests matched?" can be asked without catching an exception.
  *
  * `satisfied` is copied verbatim from the facade record (computed there via
  * `VerificationTimes.matches`) rather than recomputed here, so there is exactly one authority for
  * the pass/fail verdict.
  *
  * `requests` and `closest` are populated **only** when the call asked for them —
  * `VerifyDetail.Requests` and `VerifyDetail.Closest` respectively. Without the flag the facade
  * returns an empty list / absent optional, which is indistinguishable here from "asked for, and
  * there genuinely were none": `matched`/`total`/`satisfied` are the fields that always mean
  * something.
  */
final case class VerificationResult(
    matched: Int,
    total: Int,
    satisfied: Boolean,
    requests: Vector[RecordedRequest],
    closest: Option[ClosestMiss]
)
