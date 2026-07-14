package rift.model.matching

import rift.model.PredicateOp

/** One predicate that did not match a recorded request: which operator, which field (e.g.
  * `"headers.Accept"`, `"query.page"`, `"method"`, `"path"`), what was expected, and what the
  * request actually carried (`None` when the field was absent).
  */
final case class PredicateFailure(
    predicate: PredicateOp,
    field: String,
    expected: String,
    actual: Option[String]
)
