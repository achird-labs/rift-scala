package rift.fs2

import _root_.fs2.Pipe

import rift.dsl.RequestMatch
import rift.model.RecordedRequest
import rift.model.matching.{MatchResult, RequestMatcher}

/** Verification pipes over a `Stream[F, RecordedRequest]` (DESIGN.md §5.7, issue #9). */
object pipes:

  /** Keep only requests matching `m`, evaluated with the same client-side matcher
    * `rift.cats.ImposterHandle.recorded(matching)` implies server-side (DESIGN.md §5.1.4, D5).
    */
  def matching[F[_]](m: RequestMatch): Pipe[F, RecordedRequest, RecordedRequest] =
    _.filter(r => RequestMatcher.evaluate(m, r) == MatchResult.Matched)
