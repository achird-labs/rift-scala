package rift.conformance

import zio.test.*

import G3Require.Decision.*

/** Gate for the #63 backstop decision logic — pure, engine-free. Locks in the **fail-closed**
  * behaviour (an unknown/typo'd `RIFT_G3_REQUIRE` must fail, never skip) so a future edit can't
  * silently reopen the silent-skip hole.
  */
object G3RequireSpec extends ZIOSpecDefault:

  private def isFail(d: G3Require.Decision): Boolean = d match
    case G3Require.Decision.Fail(_) => true
    case _ => false

  def spec = suite("G3Require (issue #63 matrix-drift backstop)")(
    test("embedded: an available engine always runs, whatever the flag says") {
      assertTrue(
        G3Require.decideEmbedded(true, None) == Run,
        G3Require.decideEmbedded(true, Some("embedded")) == Run,
        G3Require.decideEmbedded(true, Some("spawn")) == Run,
        G3Require.decideEmbedded(true, Some("typo")) == Run
      )
    },
    test(
      "embedded: unavailable + required=embedded FAILS (case-insensitive, whitespace-tolerant)"
    ) {
      assertTrue(
        isFail(G3Require.decideEmbedded(false, Some("embedded"))),
        isFail(G3Require.decideEmbedded(false, Some("EMBEDDED"))),
        isFail(G3Require.decideEmbedded(false, Some("  embedded  ")))
      )
    },
    test(
      "embedded: unavailable + unset or a known other lane SKIPS (local dev / JDK 21 spawn job)"
    ) {
      assertTrue(
        G3Require.decideEmbedded(false, None) == Skip,
        G3Require.decideEmbedded(false, Some("")) == Skip,
        G3Require.decideEmbedded(false, Some("spawn")) == Skip
      )
    },
    test("fail-closed: an unknown non-empty RIFT_G3_REQUIRE FAILS, never skips (both lanes)") {
      assertTrue(
        isFail(G3Require.decideEmbedded(false, Some("embeded"))),
        isFail(G3Require.decideEmbedded(false, Some("bogus"))),
        isFail(G3Require.decideSpawn(Some("spwan"))),
        isFail(G3Require.decideSpawn(Some("bogus")))
      )
    },
    test("spawn: runs only when required; unset/known-other skips; blank is unset") {
      assertTrue(
        G3Require.decideSpawn(Some("spawn")) == Run,
        G3Require.decideSpawn(Some("SPAWN")) == Run,
        G3Require.decideSpawn(None) == Skip,
        G3Require.decideSpawn(Some("  ")) == Skip,
        G3Require.decideSpawn(Some("embedded")) == Skip
      )
    }
  )
