package rift.ziobdd

/** Backstop for the guarded live engine suite (issue #63): `RIFT_G3_REQUIRE` names the lane a CI
  * job MUST run, so a silently-unavailable embedded engine on the job that requires it becomes a
  * **red build** instead of an `ignore`-skip that stays green having run nothing.
  *
  * **Fails closed** — a non-empty value that is not a known lane (a typo, stray whitespace) FAILS
  * rather than degrading to "not required". Duplicates the embedded-lane decision of
  * `rift.conformance.G3Require` (whose spawn-only `decideSpawn` doesn't apply here); this module
  * does not depend on `conformance`, so a ~20-line test helper is copied rather than forced through
  * an awkward cross-module `test->test` dependency. Both copies are unit-tested.
  */
object G3Require:

  enum Decision:
    case Run
    case Skip
    case Fail(reason: String)

  private val knownLanes: Set[String] = Set("embedded", "spawn")

  def required: Option[String] = sys.env.get("RIFT_G3_REQUIRE")

  private def laneOf(required: Option[String]): Option[String] =
    required.map(_.trim).filter(_.nonEmpty)

  def decideEmbedded(available: Boolean, required: Option[String]): Decision =
    if available then Decision.Run
    else
      laneOf(required) match
        case Some(v) if v.equalsIgnoreCase("embedded") =>
          Decision.Fail(
            s"G3 embedded lane REQUIRED (RIFT_G3_REQUIRE=$v) but no embedded engine is on this JVM " +
              "— the rift-java-embedded jars / --enable-native-access are missing (issue #63 " +
              "backstop). A green skip here would mean the lane never ran on a job that requires it."
          )
        case Some(v) if knownLanes.exists(v.equalsIgnoreCase) => Decision.Skip
        case Some(v) =>
          Decision.Fail(
            s"RIFT_G3_REQUIRE=$v is not a known G3 lane (embedded|spawn) — failing closed rather " +
              "than silently skipping (issue #63). Fix the CI env expression or this value."
          )
        case None => Decision.Skip
