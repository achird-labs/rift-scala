package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under

final case class Headers(entries: Vector[(String, String)]):
  def get(name: String): Option[String] =
    entries.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  def toJson: Json = Json.Obj(entries.map((k, v) => k -> Json.Str(v)))

object Headers:
  val empty: Headers = Headers(Vector.empty)

  /** Convenience overload for callers holding an unordered header map (e.g. test fixtures) — order
    * doesn't matter for header semantics, unlike the wire-preserving `Vector` constructor.
    */
  def apply(entries: Map[String, String]): Headers = Headers(entries.toVector)

  def fromJson(json: Json): Either[JsonError.Decode, Headers] =
    json.asObject match
      case None => Left(JsonError.Decode("expected an object", Vector.empty))
      case Some(fields) =>
        fields
          .foldLeft[Either[JsonError.Decode, Vector[(String, String)]]](Right(Vector.empty)) {
            case (acc, (k, Json.Str(v))) => acc.map(_ :+ (k -> v))
            case (_, (k, _)) =>
              Left(JsonError.Decode("expected a string header value", Vector.empty).under(k))
          }
          .map(Headers(_))
