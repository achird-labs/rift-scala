package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

final case class Stub(
    predicates: Vector[Predicate],
    responses: Vector[Response],
    name: Option[String] = None,
    id: Option[StubId] = None,
    routePattern: Option[String] = None,
    space: Option[FlowId] = None,
    scenario: Option[ScenarioRef] = None,
    extra: Vector[(String, Json)] = Vector.empty,
    /** Whether `predicates` was spelled `rules` on the wire — a Mountebank-migration-tool spelling
      * some real imposters still use. Re-encodes under whichever key it was read from, same as
      * every other alternate-spelling field in this package (e.g. `WaitBehavior`).
      */
    predicatesAsRules: Boolean = false
):
  def toJson: Json =
    val known = Vector(
      // Always emitted, even when empty, mirroring rift-java (Stub.java:103-104), which decodes both
      // as optional but always writes them. An empty array and an absent key mean the same thing to
      // the engine, and parity with the reference implementation (D2) beats a private convention.
      Some(
        (if predicatesAsRules then "rules" else "predicates") -> Json.Arr(predicates.map(_.toJson))
      ),
      Some("responses" -> Json.Arr(responses.map(_.toJson))),
      name.map(n => "name" -> Json.Str(n)),
      id.map(i => "id" -> Json.Str(StubId.value(i))),
      routePattern.map(r => "routePattern" -> Json.Str(r)),
      space.map(s => "space" -> Json.Str(FlowId.value(s))),
      scenario.map(s => "scenarioName" -> Json.Str(s.name)),
      scenario.flatMap(_.requiredState).map(s => "requiredScenarioState" -> Json.Str(s)),
      scenario.flatMap(_.newState).map(s => "newScenarioState" -> Json.Str(s))
    ).flatten
    buildObj(Stub.modeledKeys, known, extra)

object Stub:
  private val modeledKeys = Set(
    "predicates",
    "rules",
    "responses",
    "name",
    "id",
    "routePattern",
    "space",
    "scenarioName",
    "requiredScenarioState",
    "newScenarioState"
  )

  /** `rules` is a migration-tool spelling of `predicates` some real imposters still carry; the two
    * are mutually exclusive on the wire, matching `jsonpath`/`xpath` elsewhere in this package.
    */
  private def decodePredicates(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, (Vector[Predicate], Boolean)] =
    (fields.field("predicates"), fields.field("rules")) match
      case (Some(p), None) =>
        decodeArray(p, Predicate.fromJson).left.map(_.under("predicates")).map((_, false))
      case (None, Some(r)) =>
        decodeArray(r, Predicate.fromJson).left.map(_.under("rules")).map((_, true))
      case (None, None) => Right((Vector.empty, false))
      case (Some(_), Some(_)) =>
        Left(JsonError.Decode("both 'predicates' and 'rules' present", Vector.empty))

  def fromJson(json: Json): Either[JsonError.Decode, Stub] =
    for
      fields <- asObj(json, "stub")
      predicatesResult <- decodePredicates(fields)
      (predicates, predicatesAsRules) = predicatesResult
      responses <- fields.field("responses") match
        case Some(r) => decodeArray(r, Response.fromJson).left.map(_.under("responses"))
        case None => Right(Vector.empty)
      name <- optString(fields, "name")
      id <- optString(fields, "id")
      routePattern <- optString(fields, "routePattern")
      space <- optString(fields, "space").flatMap {
        case Some(s) =>
          FlowId
            .from(s)
            .map(Some(_))
            .left
            .map(msg => JsonError.Decode(msg, Vector.empty).under("space"))
        case None => Right(None)
      }
      scenarioName <- optString(fields, "scenarioName")
      requiredState <- optString(fields, "requiredScenarioState")
      newState <- optString(fields, "newScenarioState")
      scenario <- scenarioRef(scenarioName, requiredState, newState)
    yield Stub(
      predicates,
      responses,
      name,
      id.map(StubId(_)),
      routePattern,
      space,
      scenario,
      fields.remainder(modeledKeys),
      predicatesAsRules
    )

  /** The scenario states are meaningless without the name that scopes them, and they are modeled
    * keys so they would not survive in `extra` either — losing them silently would change what the
    * stub means. Reject instead.
    */
  private def scenarioRef(
      name: Option[String],
      requiredState: Option[String],
      newState: Option[String]
  ): Either[JsonError.Decode, Option[ScenarioRef]] =
    name match
      case Some(n) => Right(Some(ScenarioRef(n, requiredState, newState)))
      case None if requiredState.isDefined || newState.isDefined =>
        Left(
          JsonError.Decode(
            "requiredScenarioState/newScenarioState require a scenarioName",
            Vector.empty
          )
        )
      case None => Right(None)
