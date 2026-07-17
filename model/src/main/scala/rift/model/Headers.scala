package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under

final case class Headers(entries: Vector[(String, String)]):
  def get(name: String): Option[String] =
    entries.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  /** A key seen once stays a bare string; a key repeated (e.g. multiple `Set-Cookie`) collapses to
    * a single wire key holding all its values as an array, in first-seen order. The engine never
    * wraps a lone value in a 1-element array, so doing so here would fail `semanticEquals` against
    * a real fixture.
    */
  def toJson: Json =
    val order = entries.map(_._1).distinct
    Json.Obj(order.map { k =>
      val values = entries.collect { case (`k`, v) => v }
      k -> (if values.size == 1 then Json.Str(values.head) else Json.Arr(values.map(Json.Str(_))))
    })

object Headers:
  val empty: Headers = Headers(Vector.empty)

  /** Convenience overload for callers holding an unordered header map (e.g. test fixtures) — order
    * doesn't matter for header semantics, unlike the wire-preserving `Vector` constructor.
    */
  def apply(entries: Map[String, String]): Headers = Headers(entries.toVector)

  /** A header value is a string, or (for multi-value headers like `Set-Cookie`) an array of strings
    * — each array element becomes its own `(key, value)` entry under the same key, which
    * `Vector[(String, String)]` already supports repeating.
    */
  def fromJson(json: Json): Either[JsonError.Decode, Headers] =
    json.asObject match
      case None => Left(JsonError.Decode("expected an object", Vector.empty))
      case Some(fields) =>
        fields
          .foldLeft[Either[JsonError.Decode, Vector[(String, String)]]](Right(Vector.empty)) {
            case (acc, (k, Json.Str(v))) => acc.map(_ :+ (k -> v))
            case (acc, (k, Json.Arr(items))) if items.nonEmpty =>
              items.zipWithIndex.foldLeft(acc) {
                case (a, (Json.Str(v), _)) => a.map(_ :+ (k -> v))
                case (Right(_), (_, i)) =>
                  Left(JsonError.Decode("expected a string", Vector.empty).under(s"[$i]").under(k))
                case (left, _) => left
              }
            case (_, (k, _)) =>
              Left(
                JsonError
                  .Decode("expected a string or a non-empty array of strings", Vector.empty)
                  .under(k)
              )
          }
          .map(Headers(_))
