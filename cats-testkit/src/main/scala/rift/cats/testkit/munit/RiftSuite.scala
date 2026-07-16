package rift.cats.testkit.munit

import _root_.cats.effect.{IO, Resource}

import _root_.munit.AnyFixture
import _root_.munit.catseffect.IOFixture

import rift.dsl.{ImposterBuilder, RequestMatch}
import rift.model.Times
import rift.cats.{ImposterHandle, Rift}
import rift.cats.testkit.internal

/** One embedded engine per suite, imposters per test (DESIGN.md §5.8). `rift` is a
  * `ResourceSuiteLocalFixture`, so the engine boots once before the suite's first test and tears
  * down once after its last — not once per test.
  *
  * `munit-cats-effect`'s `ResourceSuiteLocalFixture` returns an `IOFixture[A]` (a subtype of
  * `munit.AnyFixture[A]`, not of `munit.Fixture[A]` — that's a plain `Fixture` alias for a
  * *different*, non-cats-effect fixture shape); `munitFixtures` is typed at `AnyFixture` to match.
  *
  * Library references in this file are `_root_`-qualified (`_root_.munit...`): this package is
  * itself named `rift.cats.testkit.munit` (mirroring the real `munit` library's own package name
  * per DESIGN.md §5.8), and Scala resolves an unqualified `munit.X` from sibling code in the
  * enclosing `rift.cats.testkit` package against *that* nested package first — silently shadowing
  * the real one — unless qualified from `_root_`.
  */
trait RiftSuite extends _root_.munit.CatsEffectSuite:

  /** Override to switch transport (e.g. connecting to a CI-shared engine instead of embedding). */
  protected def riftResource: Resource[IO, Rift[IO]] = Rift.embedded[IO]

  protected val rift: IOFixture[Rift[IO]] = ResourceSuiteLocalFixture("rift", riftResource)

  override def munitFixtures: Seq[AnyFixture[?]] = List(rift)

  /** Test-scoped imposter: created before `body` runs, deleted after — even when `body` fails, so a
    * failing test never leaks an imposter into the next one.
    */
  def withImposter(builder: ImposterBuilder)(body: ImposterHandle[IO] => IO[Unit]): IO[Unit] =
    rift().create(builder).flatMap(handle => body(handle).guarantee(handle.delete.void))

  /** Fails the test (via munit's `fail`) with the rendered near-miss diff on `VerificationFailed`;
    * any other `RiftError` still fails the test with its own message rather than passing silently
    * (`internal.foldVerification` owns that fold — see its scaladoc).
    */
  def assertReceived(
      handle: ImposterHandle[IO],
      matching: RequestMatch,
      times: Times = Times.atLeastOnce
  ): IO[Unit] =
    internal.foldVerification(handle, matching, times)(IO.unit)(msg => IO(fail(msg)))

  def assertNoInteractions(handle: ImposterHandle[IO]): IO[Unit] =
    internal.foldNoInteractions(handle)(IO.unit)(msg => IO(fail(msg)))
