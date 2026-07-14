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
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json =
    val known = Vector(
      // Always emitted, even when empty, mirroring rift-java (Stub.java:103-104), which decodes both
      // as optional but always writes them. An empty array and an absent key mean the same thing to
      // the engine, and parity with the reference implementation (D2) beats a private convention.
      Some("predicates" -> Json.Arr(predicates.map(_.toJson))),
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
    "responses",
    "name",
    "id",
    "routePattern",
    "space",
    "scenarioName",
    "requiredScenarioState",
    "newScenarioState"
  )

  def fromJson(json: Json): Either[JsonError.Decode, Stub] =
    for
      fields <- asObj(json, "stub")
      predicates <- fields.field("predicates") match
        case Some(p) => decodeArray(p, Predicate.fromJson).left.map(_.under("predicates"))
        case None => Right(Vector.empty)
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
      fields.remainder(modeledKeys)
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
