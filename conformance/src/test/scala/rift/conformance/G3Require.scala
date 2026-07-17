package rift.conformance

/** Backstop for the guarded live G3 suites (issue #63): `RIFT_G3_REQUIRE` names the lane a CI job
  * MUST run, so a wrongly-skipped guard (a native-load regression, a matrix JDK that owns neither
  * lane) becomes a **red build** instead of the silent green skip the #56 floor assertion cannot
  * catch (it only fires once a lane is already running).
  *
  * **Fails closed.** An unset flag is the local-dev skip default, but a non-empty value that is not
  * a known lane — a typo (`embeded`), stray whitespace, or a future lane name this code doesn't
  * recognise — FAILS loudly rather than degrading to "not required". A backstop that silently skips
  * on misconfiguration would reopen the very hole it exists to close.
  *
  * Duplicated in the `zio-bdd` module's test scope (`rift.ziobdd.G3Require`, embedded lane only) —
  * sharing would force an awkward cross-module `test->test` dependency for ~20 lines; both copies
  * are unit-tested.
  */
object G3Require:

  enum Decision:
    case Run
    case Skip
    case Fail(reason: String)

  private val knownLanes: Set[String] = Set("embedded", "spawn")

  /** The raw `RIFT_G3_REQUIRE` value; the `decide*` functions normalise whitespace/case/blank. */
  def required: Option[String] = sys.env.get("RIFT_G3_REQUIRE")

  private def laneOf(required: Option[String]): Option[String] =
    required.map(_.trim).filter(_.nonEmpty)

  private def unknownLaneFail(value: String): Decision =
    Decision.Fail(
      s"RIFT_G3_REQUIRE=$value is not a known G3 lane (embedded|spawn) — failing closed rather than " +
        "silently skipping (issue #63). Fix the CI env expression or this value."
    )

  /** Embedded lane (has an availability probe): run when the engine is present; else fail iff this
    * job required the embedded lane, skip for a known other lane or unset, fail on an unknown
    * value.
    */
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
        case Some(v) => unknownLaneFail(v)
        case None => Decision.Skip

  /** Spawn lane (no availability probe — a failed launch fails the replay loudly): run iff this job
    * required it, skip for a known other lane or unset, fail on an unknown value.
    */
  def decideSpawn(required: Option[String]): Decision =
    laneOf(required) match
      case Some(v) if v.equalsIgnoreCase("spawn") => Decision.Run
      case Some(v) if knownLanes.exists(v.equalsIgnoreCase) => Decision.Skip
      case Some(v) => unknownLaneFail(v)
      case None => Decision.Skip
