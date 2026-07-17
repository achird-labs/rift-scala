package rift.ziobdd

import zio.test.*

import G3Require.Decision.*

/** Gate for the #63 backstop decision logic in this module's embedded-lane copy — pure,
  * engine-free, including the fail-closed cases.
  */
object G3RequireSpec extends ZIOSpecDefault:

  private def isFail(d: G3Require.Decision): Boolean = d match
    case G3Require.Decision.Fail(_) => true
    case _ => false

  def spec = suite("G3Require (issue #63 backstop, zio-bdd copy)")(
    test(
      "available runs; required=embedded when unavailable fails; unknown fails; unset/other skips"
    ) {
      assertTrue(
        G3Require.decideEmbedded(true, Some("embedded")) == Run,
        isFail(G3Require.decideEmbedded(false, Some("embedded"))),
        isFail(G3Require.decideEmbedded(false, Some("EMBEDDED"))),
        isFail(G3Require.decideEmbedded(false, Some("  embedded  "))),
        isFail(G3Require.decideEmbedded(false, Some("embeded"))),
        G3Require.decideEmbedded(false, None) == Skip,
        G3Require.decideEmbedded(false, Some("")) == Skip,
        G3Require.decideEmbedded(false, Some("spawn")) == Skip
      )
    }
  )
