package rift.fs2

import scala.concurrent.duration.*

import _root_.cats.effect.Temporal
import _root_.cats.effect.syntax.temporal.*

import rift.cats.ImposterHandle
import rift.dsl.RequestMatch
import rift.model.RecordedRequest

import syntax.requestStream

/** Complete when `count` matching requests have been seen, fail on timeout — the await-n-requests
  * idiom for async SUTs (DESIGN.md §5.7, issue #9).
  */
def awaitRequests[F[_]: Temporal](
    handle: ImposterHandle[F],
    matching: RequestMatch,
    count: Int,
    timeout: FiniteDuration = 5.seconds
): F[Vector[RecordedRequest]] =
  handle
    .requestStream()
    .through(pipes.matching(matching))
    .take(count.toLong)
    .compile
    .toVector
    .timeout(timeout)
