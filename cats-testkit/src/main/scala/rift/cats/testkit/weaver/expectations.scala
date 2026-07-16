package rift.cats.testkit.weaver

import _root_.cats.effect.IO

import _root_.weaver.Expectations

import rift.dsl.RequestMatch
import rift.model.Times
import rift.cats.ImposterHandle
import rift.cats.testkit.internal

/** weaver-flavoured readable assertions over `ImposterHandle`'s server-side verification (DESIGN.md
  * §5.8) — the `Expectations`-returning counterpart to the munit glue's `assertReceived`/
  * `assertNoInteractions`, built on the same `internal.foldVerification`/`foldNoInteractions` so
  * the near-miss diff renders identically either way.
  */
object expectations:

  def expectReceived(
      handle: ImposterHandle[IO],
      matching: RequestMatch,
      times: Times = Times.atLeastOnce
  ): IO[Expectations] =
    internal.foldVerification(handle, matching, times)(IO.pure(Expectations.Helpers.success))(msg =>
      IO.pure(Expectations.Helpers.failure(msg))
    )

  def expectNoInteractions(handle: ImposterHandle[IO]): IO[Expectations] =
    internal.foldNoInteractions(handle)(IO.pure(Expectations.Helpers.success))(msg =>
      IO.pure(Expectations.Helpers.failure(msg))
    )
