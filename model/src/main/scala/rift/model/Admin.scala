package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*
import java.time.Instant

/** A request the engine recorded. `raw` is the escape hatch — it is the exact document the engine
  * returned, so `toJson` simply re-emits it rather than re-deriving it from the typed fields.
  */
final case class RecordedRequest(
    method: Method,
    path: String,
    query: Map[String, Vector[String]],
    headers: Headers,
    body: Option[Json],
    bodyText: Option[String],
    timestamp: Instant,
    requestFrom: Option[String],
    flowId: Option[FlowId],
    pathParams: Map[String, String],
    raw: Json
):
  def toJson: Json = raw

object RecordedRequest:
  def fromJson(json: Json): Either[JsonError.Decode, RecordedRequest] =
    for
      fields <- asObj(json, "recorded request")
      methodJson <- fields
        .field("method")
        .toRight[JsonError.Decode](
          JsonError.Decode("missing required field", Vector.empty).under("method")
        )
      method <- Method.fromJson(methodJson).left.map(_.under("method"))
      path <- reqString(fields, "path")
      query <- fields.field("query") match
        case Some(q) => decodeQuery(q).left.map(_.under("query"))
        case None => Right(Map.empty[String, Vector[String]])
      headers <- fields.field("headers") match
        case Some(h) => Headers.fromJson(h).left.map(_.under("headers"))
        case None => Right(Headers.empty)
      bodyText <- optString(fields, "bodyText")
      timestamp <- reqString(fields, "timestamp").flatMap { s =>
        try Right(Instant.parse(s))
        catch
          case _: java.time.format.DateTimeParseException =>
            Left(JsonError.Decode(s"invalid timestamp: $s", Vector.empty).under("timestamp"))
      }
      requestFrom <- optString(fields, "requestFrom")
      flowId <- optString(fields, "flowId").flatMap {
        case Some(s) =>
          FlowId
            .from(s)
            .map(Some(_))
            .left
            .map(msg => JsonError.Decode(msg, Vector.empty).under("flowId"))
        case None => Right(None)
      }
      pathParams <- fields.field("pathParams") match
        case Some(p) => decodeStringMap(p).left.map(_.under("pathParams"))
        case None => Right(Map.empty[String, String])
    yield RecordedRequest(
      method,
      path,
      query,
      headers,
      fields.field("body"),
      bodyText,
      timestamp,
      requestFrom,
      flowId,
      pathParams,
      json
    )

  private def decodeQuery(json: Json): Either[JsonError.Decode, Map[String, Vector[String]]] =
    asObj(json, "query").flatMap { fields =>
      fields.foldLeft[Either[JsonError.Decode, Map[String, Vector[String]]]](Right(Map.empty)) {
        case (acc, (k, Json.Arr(items))) =>
          for
            xs <- acc
            values <- items.foldLeft[Either[JsonError.Decode, Vector[String]]](
              Right(Vector.empty)
            ) {
              case (a, Json.Str(s)) => a.map(_ :+ s)
              case (_, _) => Left(JsonError.Decode("expected a string", Vector.empty).under(k))
            }
          yield xs + (k -> values)
        case (acc, (k, Json.Str(s))) => acc.map(_ + (k -> Vector(s)))
        case (_, (k, _)) =>
          Left(JsonError.Decode("expected a string or array of strings", Vector.empty).under(k))
      }
    }

  private def decodeStringMap(json: Json): Either[JsonError.Decode, Map[String, String]] =
    asObj(json, "string map").flatMap { fields =>
      fields.foldLeft[Either[JsonError.Decode, Map[String, String]]](Right(Map.empty)) {
        case (acc, (k, Json.Str(v))) => acc.map(_ + (k -> v))
        case (_, (k, _)) => Left(JsonError.Decode("expected a string", Vector.empty).under(k))
      }
    }

final case class EngineInfo(version: String, commit: String, features: Set[String]):
  def toJson: Json = Json.obj(
    "version" -> Json.Str(version),
    "commit" -> Json.Str(commit),
    "features" -> Json.Arr(features.toVector.sorted.map(Json.Str(_)))
  )

object EngineInfo:
  def fromJson(json: Json): Either[JsonError.Decode, EngineInfo] =
    for
      fields <- asObj(json, "engine info")
      version <- reqString(fields, "version")
      commit <- reqString(fields, "commit")
      features <- fields.field("features") match
        case Some(f) =>
          decodeArray(
            f,
            (j: Json) =>
              j.asString.toRight[JsonError.Decode](
                JsonError.Decode("expected a string", Vector.empty)
              )
          ).left
            .map(_.under("features"))
        case None => Right(Vector.empty)
    yield EngineInfo(version, commit, features.toSet)

final case class ApplyResult(
    created: Int,
    replaced: Int,
    stubPatched: Int,
    deleted: Int,
    failed: Vector[Json]
):
  def toJson: Json = Json.obj(
    "created" -> Json.Num(BigDecimal(created)),
    "replaced" -> Json.Num(BigDecimal(replaced)),
    "stubPatched" -> Json.Num(BigDecimal(stubPatched)),
    "deleted" -> Json.Num(BigDecimal(deleted)),
    "failed" -> Json.Arr(failed)
  )

object ApplyResult:
  def fromJson(json: Json): Either[JsonError.Decode, ApplyResult] =
    for
      fields <- asObj(json, "apply result")
      created <- optInt(fields, "created").map(_.getOrElse(0))
      replaced <- optInt(fields, "replaced").map(_.getOrElse(0))
      stubPatched <- optInt(fields, "stubPatched").map(_.getOrElse(0))
      deleted <- optInt(fields, "deleted").map(_.getOrElse(0))
      failed <- fields.field("failed") match
        case Some(f) =>
          f.asArray.toRight[JsonError.Decode](
            JsonError.Decode("expected an array", Vector.empty).under("failed")
          )
        case None => Right(Vector.empty)
    yield ApplyResult(created, replaced, stubPatched, deleted, failed)

final case class ScenarioStatus(name: String, state: String):
  def toJson: Json = Json.obj("name" -> Json.Str(name), "state" -> Json.Str(state))

object ScenarioStatus:
  def fromJson(json: Json): Either[JsonError.Decode, ScenarioStatus] =
    for
      fields <- asObj(json, "scenario status")
      name <- reqString(fields, "name")
      state <- reqString(fields, "state")
    yield ScenarioStatus(name, state)

enum Times:
  case Exactly(n: Int)
  case AtLeast(n: Int)
  case AtMost(n: Int)
  case Between(min: Int, max: Int)

  def toJson: Json = this match
    case Times.Exactly(n) => Json.obj("exactly" -> Json.Num(BigDecimal(n)))
    case Times.AtLeast(n) => Json.obj("atLeast" -> Json.Num(BigDecimal(n)))
    case Times.AtMost(n) => Json.obj("atMost" -> Json.Num(BigDecimal(n)))
    case Times.Between(lo, hi) =>
      Json.obj(
        "between" -> Json.obj("min" -> Json.Num(BigDecimal(lo)), "max" -> Json.Num(BigDecimal(hi)))
      )

object Times:
  val once: Times = Exactly(1)
  val never: Times = Exactly(0)
  val atLeastOnce: Times = AtLeast(1)

  def fromJson(json: Json): Either[JsonError.Decode, Times] =
    for
      fields <- asObj(json, "times")
      result <- decodeVariant(fields)
    yield result

  private def decodeVariant(fields: Vector[(String, Json)]): Either[JsonError.Decode, Times] =
    fields match
      case Vector(("exactly", Json.Num(n))) => Right(Exactly(n.toInt))
      case Vector(("atLeast", Json.Num(n))) => Right(AtLeast(n.toInt))
      case Vector(("atMost", Json.Num(n))) => Right(AtMost(n.toInt))
      case Vector(("between", betweenJson)) => decodeBetween(betweenJson)
      case _ =>
        Left(JsonError.Decode("expected one of exactly/atLeast/atMost/between", Vector.empty))

  private def decodeBetween(betweenJson: Json): Either[JsonError.Decode, Times] =
    for
      betweenFields <- asObj(betweenJson, "between").left.map(_.under("between"))
      min <- optInt(betweenFields, "min").flatMap(
        _.toRight[JsonError.Decode](
          JsonError.Decode("missing required field", Vector.empty).under("min")
        )
      )
      max <- optInt(betweenFields, "max").flatMap(
        _.toRight[JsonError.Decode](
          JsonError.Decode("missing required field", Vector.empty).under("max")
        )
      )
    yield Between(min, max)
