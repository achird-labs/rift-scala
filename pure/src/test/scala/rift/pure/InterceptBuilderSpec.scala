package rift.pure

import munit.FunSuite

import rift.bridge.InterceptGate
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
  // against the facade's own `parsePort` instead.
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
