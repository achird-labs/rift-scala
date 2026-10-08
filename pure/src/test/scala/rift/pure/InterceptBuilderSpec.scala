package rift.pure

import munit.FunSuite

import rift.RiftError
import rift.bridge.{ForwardTarget, InterceptGate, InterceptRule, RuleKind}
import rift.dsl.*
import rift.model.Port

/** Engine-free gate for `pure`'s intercept rule builder (#120).
  *
  * `pure` is a straight delegate over the bridge builder — unlike zio/cats it has no per-terminal
  * replay fold — so what needs proving here is narrower: that a terminal reaches the facade at all,
  * carrying its clauses. The shared `InterceptGate` supplies the reflective null-engine builder;
  * the NPE it raises is the signal a call got all the way through translation.
  */
class InterceptBuilderSpec extends FunSuite:

  private def port(value: Int): Port =
    Port.from(value).toOption.getOrElse(fail(s"not a valid port: $value"))

  private def newIntercept(fake: InterceptGate.BuilderRecordingIntercept): Intercept =
    new Intercept(InterceptGate.connector(fake))

  // Port equivalence is NOT asserted here: `InterceptGate` can observe the facade builder's host
  // and predicates but not a forward target (the null engine NPEs first), so any such check at
  // this level would pass whatever the rendering did. It is proven in `InterceptTranslationSpec`
  // against the facade's own `ForwardTarget.parse` instead.
  test("forward(port) reaches the facade carrying every buffered clause"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    // catchRiftError maps only RiftError, so the null engine's NPE propagates rather than
    // becoming a Left — an unreachable engine is a defect, not a typed failure.
    intercept[NullPointerException](
      newIntercept(fake).rule("api.example.com").when(first).when(second).forward(port(4545))
    )
    assertEquals(fake.ruleCalls, 1)
    val sent = InterceptGate.facadePredicates(fake.lastBuilder)
    assertEquals(sent.size, (first.predicates ++ second.predicates).size)

  // ── issue #206: atomic replace, typed forward target ─────────────────────────────────────────
  test("a staged rule carries every chained clause through replaceRules"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val first = get("/admin")
    val second = onRequest.where(header("X-Env").is("prod"))
    intercept[NullPointerException](
      newIntercept(fake).replaceRules { set =>
        set.rule("api.example.com").when(first).when(second).serve(ok)
        ()
      }
    )
    assertEquals(fake.replaceCalls, 1)
    assertEquals(InterceptGate.facadeHost(fake.lastBuilder), Some("api.example.com"))
    assertEquals(
      InterceptGate.facadePredicates(fake.lastBuilder).size,
      (first.predicates ++ second.predicates).size
    )

  test("a serve refusal inside declare is a Left, not a throw"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val outcome = newIntercept(fake).replaceRules { set =>
      set.rule().serve(ok.conditional)
      ()
    }
    assert(outcome.left.exists(_.isInstanceOf[RiftError.InvalidDefinition]), outcome.toString)

  test("forward(ForwardTarget) hands the facade the rendered target and its clauses"):
    val fake = new InterceptGate.CapturingIntercept
    val first = get("/admin")
    val target = ForwardTarget.from("mock-svc", port(4600)).fold(e => fail(e), identity)
    val ic = new Intercept(InterceptGate.connector(fake))
    assert(ic.rule("api.example.com").when(first).forward(target).isRight)
    assertEquals(fake.lastForward, Some("""{"port":4600,"host":"mock-svc"}"""))
    assertEquals(fake.lastPredicateCount, first.predicates.size)

  test("replaceRules(rules) and removeRule hand the facade the rules"):
    val fake = new InterceptGate.BuilderRecordingIntercept
    val ic = newIntercept(fake)
    val r = InterceptRule(Some("a.example"), RuleKind.Forward, rift.json.Json.obj())
    assertEquals(ic.replaceRules(Vector(r, r)), Right(Vector.empty))
    assertEquals(fake.replacedWith.map(_.size), Some(2))
    assertEquals(ic.removeRule(r), Right(false))
    assertEquals(fake.removed.map(_.host()), Some("a.example"))
