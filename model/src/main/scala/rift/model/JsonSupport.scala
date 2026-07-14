package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under

/** Shared decode/encode plumbing for the wire types in this package. Not part of the public API. */
private[model] object JsonSupport:

  extension (fields: Vector[(String, Json)])
    def field(key: String): Option[Json] = fields.collectFirst { case (k, v) if k == key => v }
    def remainder(consumed: Set[String]): Vector[(String, Json)] =
      fields.filterNot((k, _) => consumed(k))

  def asObj(json: Json, what: String): Either[JsonError.Decode, Vector[(String, Json)]] =
    json.asObject.toRight[JsonError.Decode](
      JsonError.Decode(s"expected a JSON object for $what", Vector.empty)
    )

  def reqString(fields: Vector[(String, Json)], key: String): Either[JsonError.Decode, String] =
    fields.field(key) match
      case Some(Json.Str(s)) => Right(s)
      case Some(_) => Left(JsonError.Decode("expected a string", Vector.empty).under(key))
      case None => Left(JsonError.Decode("missing required field", Vector.empty).under(key))

  def optString(
      fields: Vector[(String, Json)],
      key: String
  ): Either[JsonError.Decode, Option[String]] =
    fields.field(key) match
      case Some(Json.Str(s)) => Right(Some(s))
      case Some(_) => Left(JsonError.Decode("expected a string", Vector.empty).under(key))
      case None => Right(None)

  def optBool(
      fields: Vector[(String, Json)],
      key: String,
      default: Boolean
  ): Either[JsonError.Decode, Boolean] =
    fields.field(key) match
      case Some(Json.Bool(b)) => Right(b)
      case Some(_) => Left(JsonError.Decode("expected a boolean", Vector.empty).under(key))
      case None => Right(default)

  def optInt(fields: Vector[(String, Json)], key: String): Either[JsonError.Decode, Option[Int]] =
    fields.field(key) match
      case Some(Json.Num(n)) if n.isValidInt => Right(Some(n.toInt))
      case Some(Json.Num(_)) =>
        Left(JsonError.Decode("expected an integer", Vector.empty).under(key))
      case Some(_) => Left(JsonError.Decode("expected a number", Vector.empty).under(key))
      case None => Right(None)

  def optLong(fields: Vector[(String, Json)], key: String): Either[JsonError.Decode, Option[Long]] =
    fields.field(key) match
      case Some(Json.Num(n)) if n.isValidLong => Right(Some(n.toLong))
      case Some(Json.Num(_)) =>
        Left(JsonError.Decode("expected an integer", Vector.empty).under(key))
      case Some(_) => Left(JsonError.Decode("expected a number", Vector.empty).under(key))
      case None => Right(None)

  def optDouble(
      fields: Vector[(String, Json)],
      key: String
  ): Either[JsonError.Decode, Option[Double]] =
    fields.field(key) match
      case Some(Json.Num(n)) => Right(Some(n.toDouble))
      case Some(_) => Left(JsonError.Decode("expected a number", Vector.empty).under(key))
      case None => Right(None)

  def decodeArray[A](
      json: Json,
      decodeOne: Json => Either[JsonError.Decode, A]
  ): Either[JsonError.Decode, Vector[A]] =
    json.asArray match
      case None => Left(JsonError.Decode("expected an array", Vector.empty))
      case Some(items) =>
        items.zipWithIndex.foldLeft[Either[JsonError.Decode, Vector[A]]](Right(Vector.empty)) {
          case (acc, (item, idx)) =>
            for
              xs <- acc
              a <- decodeOne(item).left.map(_.under(idx.toString))
            yield xs :+ a
        }

  /** Merges the present modeled fields with `extra`, rejecting a modeled key smuggled through
    * `extra` and rejecting duplicate `extra` keys — both are construction errors, not decode
    * errors, because they only arise when a caller builds the value programmatically.
    */
  def buildObj(
      modeledKeys: Set[String],
      present: Vector[(String, Json)],
      extra: Vector[(String, Json)]
  ): Json =
    val extraKeys = extra.map(_._1)
    val overlap = extraKeys.filter(modeledKeys.contains).distinct
    require(overlap.isEmpty, s"modeled key(s) present in extra: ${overlap.mkString(", ")}")
    val dupes = extraKeys.groupBy(identity).collect { case (k, ks) if ks.size > 1 => k }
    require(dupes.isEmpty, s"duplicate keys in extra: ${dupes.mkString(", ")}")
    Json.Obj(present ++ extra)
