package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** The field/value pairs carried by a predicate operator (`equals`, `contains`, ...). Values stay
  * raw `Json` — an `exists` operator carries booleans (`{"body": true}`) while `equals` carries
  * real values, so a typed scalar per field can't represent both.
  */
final case class Fields(entries: Vector[(String, Json)]):
  def method: Option[Json] = entries.field("method")
  def path: Option[Json] = entries.field("path")
  def query: Option[Json] = entries.field("query")
  def headers: Option[Json] = entries.field("headers")
  def body: Option[Json] = entries.field("body")

  def toJson: Json = Json.Obj(entries)

object Fields:
  val empty: Fields = Fields(Vector.empty)

  def fromJson(json: Json): Either[JsonError.Decode, Fields] =
    asObj(json, "predicate fields").map(Fields(_))

enum PredicateSelector:
  case JsonPath(selector: String)
  case XPath(selector: String, namespaces: Map[String, String] = Map.empty)

  def toJson: Json = this match
    case PredicateSelector.JsonPath(selector) => Json.obj("selector" -> Json.Str(selector))
    case PredicateSelector.XPath(selector, namespaces) =>
      val ns =
        if namespaces.nonEmpty then
          Vector("ns" -> Json.Obj(namespaces.toVector.map((k, v) => k -> Json.Str(v))))
        else Vector.empty
      Json.Obj(Vector("selector" -> Json.Str(selector)) ++ ns)

object PredicateSelector:
  def jsonPathFromJson(json: Json): Either[JsonError.Decode, PredicateSelector] =
    for
      fields <- asObj(json, "jsonpath")
      selector <- reqString(fields, "selector")
    yield JsonPath(selector)

  def xPathFromJson(json: Json): Either[JsonError.Decode, PredicateSelector] =
    for
      fields <- asObj(json, "xpath")
      selector <- reqString(fields, "selector")
      ns <- fields.field("ns") match
        case Some(Json.Obj(nsFields)) =>
          nsFields.foldLeft[Either[JsonError.Decode, Map[String, String]]](Right(Map.empty)) {
            case (acc, (k, Json.Str(v))) => acc.map(_ + (k -> v))
            case (_, (k, _)) =>
              Left(JsonError.Decode("expected a string namespace uri", Vector.empty).under(k))
          }
        case Some(_) => Left(JsonError.Decode("expected an object", Vector.empty).under("ns"))
        case None => Right(Map.empty)
    yield XPath(selector, ns)

final case class PredicateParams(
    caseSensitive: Boolean = false,
    except: Option[String] = None,
    selector: Option[PredicateSelector] = None,
    /** Whether `caseSensitive` was present on the wire, independent of its value. An explicit
      * `"caseSensitive": false` must re-emit as `false`, not vanish — `semanticEquals` is strict
      * about object size, so dropping a key the source had is a round-trip bug, not a no-op.
      */
    caseSensitiveExplicit: Boolean = false
):
  def toJsonFields: Vector[(String, Json)] =
    Vector(
      selector.map {
        case s: PredicateSelector.JsonPath => "jsonpath" -> s.toJson
        case s: PredicateSelector.XPath => "xpath" -> s.toJson
      },
      if caseSensitive || caseSensitiveExplicit then
        Some("caseSensitive" -> Json.Bool(caseSensitive))
      else None,
      except.map(e => "except" -> Json.Str(e))
    ).flatten

object PredicateParams:
  val empty: PredicateParams = PredicateParams()

  def fromJson(fields: Vector[(String, Json)]): Either[JsonError.Decode, PredicateParams] =
    for
      caseSensitive <- optBool(fields, "caseSensitive", false)
      except <- optString(fields, "except")
      selector <- decodeSelector(fields)
    yield PredicateParams(caseSensitive, except, selector, fields.field("caseSensitive").isDefined)

  private def decodeSelector(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, Option[PredicateSelector]] =
    (fields.field("jsonpath"), fields.field("xpath")) match
      case (Some(j), None) => PredicateSelector.jsonPathFromJson(j).map(Some(_))
      case (None, Some(x)) => PredicateSelector.xPathFromJson(x).map(Some(_))
      case (None, None) => Right(None)
      case (Some(_), Some(_)) =>
        Left(JsonError.Decode("both 'jsonpath' and 'xpath' present", Vector.empty))

enum PredicateOp:
  case Equals(fields: Fields)
  case DeepEquals(fields: Fields)
  case Contains(fields: Fields)
  case StartsWith(fields: Fields)
  case EndsWith(fields: Fields)
  case Matches(fields: Fields)
  case Exists(fields: Fields)
  case And(predicates: Vector[Predicate])
  case Or(predicates: Vector[Predicate])
  case Not(predicate: Predicate)
  case Inject(script: String)

final case class Predicate(op: PredicateOp, params: PredicateParams = PredicateParams.empty):
  def toJson: Json =
    val (opKey, opValue): (String, Json) = op match
      case PredicateOp.Equals(f) => "equals" -> f.toJson
      case PredicateOp.DeepEquals(f) => "deepEquals" -> f.toJson
      case PredicateOp.Contains(f) => "contains" -> f.toJson
      case PredicateOp.StartsWith(f) => "startsWith" -> f.toJson
      case PredicateOp.EndsWith(f) => "endsWith" -> f.toJson
      case PredicateOp.Matches(f) => "matches" -> f.toJson
      case PredicateOp.Exists(f) => "exists" -> f.toJson
      case PredicateOp.And(ps) => "and" -> Json.Arr(ps.map(_.toJson))
      case PredicateOp.Or(ps) => "or" -> Json.Arr(ps.map(_.toJson))
      case PredicateOp.Not(p) => "not" -> p.toJson
      case PredicateOp.Inject(s) => "inject" -> Json.Str(s)
    Json.Obj(params.toJsonFields :+ (opKey -> opValue))

object Predicate:
  private val paramKeys = Set("jsonpath", "xpath", "caseSensitive", "except")

  def fromJson(json: Json): Either[JsonError.Decode, Predicate] =
    for
      fields <- asObj(json, "predicate")
      params <- PredicateParams.fromJson(fields)
      opFields = fields.filterNot((k, _) => paramKeys(k))
      op <- decodeSingleOp(opFields)
    yield Predicate(op, params)

  private def decodeSingleOp(
      opFields: Vector[(String, Json)]
  ): Either[JsonError.Decode, PredicateOp] =
    opFields match
      case Vector((key, value)) => decodeOp(key, value)
      case Vector() => Left(JsonError.Decode("missing predicate operator", Vector.empty))
      case multiple =>
        Left(
          JsonError.Decode(
            s"multiple predicate operators: ${multiple.map(_._1).mkString(", ")}",
            Vector.empty
          )
        )

  private def decodeOp(key: String, value: Json): Either[JsonError.Decode, PredicateOp] =
    key match
      case "equals" => Fields.fromJson(value).map(PredicateOp.Equals(_)).left.map(_.under(key))
      case "deepEquals" =>
        Fields.fromJson(value).map(PredicateOp.DeepEquals(_)).left.map(_.under(key))
      case "contains" => Fields.fromJson(value).map(PredicateOp.Contains(_)).left.map(_.under(key))
      case "startsWith" =>
        Fields.fromJson(value).map(PredicateOp.StartsWith(_)).left.map(_.under(key))
      case "endsWith" => Fields.fromJson(value).map(PredicateOp.EndsWith(_)).left.map(_.under(key))
      case "matches" => Fields.fromJson(value).map(PredicateOp.Matches(_)).left.map(_.under(key))
      case "exists" => Fields.fromJson(value).map(PredicateOp.Exists(_)).left.map(_.under(key))
      case "and" =>
        decodeArray(value, Predicate.fromJson).map(PredicateOp.And(_)).left.map(_.under(key))
      case "or" =>
        decodeArray(value, Predicate.fromJson).map(PredicateOp.Or(_)).left.map(_.under(key))
      case "not" => Predicate.fromJson(value).map(PredicateOp.Not(_)).left.map(_.under(key))
      case "inject" =>
        value.asString
          .toRight[JsonError.Decode](
            JsonError.Decode("expected a script string", Vector.empty).under(key)
          )
          .map(PredicateOp.Inject(_))
      case other => Left(JsonError.Decode(s"unknown predicate operator: $other", Vector.empty))
