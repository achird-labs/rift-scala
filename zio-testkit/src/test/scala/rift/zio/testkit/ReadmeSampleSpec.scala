package rift.zio.testkit

import java.net.URI

import zio.*
import zio.test.*

import rift.dsl.*
import rift.zio.Rift

/** Compile-gate for the README's headline sample (#74-style docs drift, applied to the README).
  *
  * The README is the first code a reader copies, and nothing was checking that it still typechecks
  * against the DSL — the previous version had drifted to an API shape that no longer existed
  * (`Rift.create(users)` over a bare builder, `api.baseUri`, `times = 1` as a named argument). A
  * sample that does not compile is worse than no sample, so the exact snippet lives here too and
  * moves with the API.
  *
  * Deliberately not executed: it needs a live engine, and the point is the *type* check. Running it
  * would add nothing the embedded specs do not already cover, and would make the README hostage to
  * engine availability.
  */
object ReadmeSampleSpec extends ZIOSpecDefault:

  private def callSut(uri: URI): UIO[Unit] = ZIO.unit

  /** Verbatim from README.md — keep the two in sync. */
  private val readmeSample =
    for
      users <- Rift.create(
        imposter("users").record.stub(
          get("/api/users/1").reply(ok.json("""{"id":1}"""))
        )
      )
      _ <- callSut(users.uri)
      _ <- users.verify(get("/api/users/1"), 1)
    yield assertCompletes

  def spec = suite("README sample")(
    test("the headline snippet typechecks against the current DSL"):
      // Referencing the value is enough: if the snippet stopped compiling, this file would not
      // build and CI fails before any engine is involved.
      assertTrue(readmeSample ne null)
  )
