package rift.zio

import zio.test.*

import rift.dsl.*

/** Pure-logic gate for the ZIO intercept rule builder (issue #34). The facade round-trip needs a
  * live engine (the bridge `EmbeddedSmokeSpec` covers that, skipped in CI), but the deferred
  * builder's accumulation is engine-free — and is exactly where a dropped `.when` would hide, so it
  * gets a direct regression test.
  */
object InterceptBuilderSpec extends ZIOSpecDefault:

  private def matchesOf(builder: InterceptRuleBuilder): Vector[RequestMatch] =
    builder match
      case live: InterceptRuleBuilderLive => live.matches
      case _ => Vector.empty

  def spec = suite("InterceptRuleBuilder (pure accumulation)")(
    test("when accumulates every match in order — no earlier when is dropped"):
      val first = get("/a")
      val second = get("/b")
      // connector is never touched by `when` (it only copies), so a null is safe for this unit.
      val builder = InterceptRuleBuilderLive(null, "api.example.com").when(first).when(second)
      assertTrue(matchesOf(builder) == Vector(first, second))
    ,
    test("a fresh builder starts with no matches"):
      assertTrue(matchesOf(InterceptRuleBuilderLive(null, "api.example.com")).isEmpty)
  )
