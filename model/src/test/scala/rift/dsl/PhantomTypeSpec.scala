package rift.dsl

import scala.compiletime.testing.typeChecks

/** AC6 — the phantom-typed flagship (DESIGN.md §5.1.3): "a stub without a response does not
  * compile".
  *
  * `typeChecks` runs the typer at compile time and needs a literal argument, so each snippet is
  * spelled out in full rather than composed from a shared prelude `val`.
  */
class PhantomTypeSpec extends munit.FunSuite:

  test("a stub WITH a response compiles"):
    assert(
      typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").reply(ok).build
      """)
    )

  test("a stub WITHOUT a response does not compile"):
    assert(
      !typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").build
      """)
    )

  test("a Matching-phase stub is not accepted by imposter.stub(...)"):
    assert(
      !typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        imposter("i").stub(on(GET, "/x"))
      """)
    )
    assert(
      typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        imposter("i").stub(on(GET, "/x").reply(ok))
      """)
    )

  test("thenReply requires an existing response"):
    assert(
      !typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").thenReply(ok)
      """)
    )
    assert(
      typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").reply(ok).thenReply(ok)
      """)
    )

  test("where keeps the phase — it does not make a stub buildable"):
    assert(
      typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").where(header("A").exists).reply(ok).build
      """)
    )
    assert(
      !typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").where(header("A").exists).build
      """)
    )

  test("named/route stay chainable after a response"):
    assert(
      typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        on(GET, "/x").reply(ok).named("n").route("/x/:id").build
      """)
    )

  test("a predicate-only stub is still usable as a RequestMatch"):
    assert(
      typeChecks("""
        import rift.dsl.*
        import rift.model.Method.*
        val m: RequestMatch = on(GET, "/x")
      """)
    )
