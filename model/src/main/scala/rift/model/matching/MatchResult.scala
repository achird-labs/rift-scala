package rift.model.matching

enum MatchResult:
  case Matched
  case Missed(failures: Vector[PredicateFailure])
