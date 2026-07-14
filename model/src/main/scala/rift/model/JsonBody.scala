package rift.model

import rift.json.Json

/** Response/request body codec used by `ok.json(a)`, `RecordedRequest.bodyAs[A]`, and predicate
  * builders. `model` provides instances only for `Json`, `String`, and primitives — richer codecs
  * (zio-json, circe) ship as side-car artifacts.
  */
trait JsonBody[A]:
  def encode(a: A): Json
  def decode(json: Json): Either[String, A]

object JsonBody:
  given JsonBody[Json] with
    def encode(a: Json): Json = a
    def decode(json: Json): Either[String, Json] = Right(json)

  given JsonBody[String] with
    def encode(a: String): Json = Json.Str(a)
    def decode(json: Json): Either[String, String] =
      json.asString.toRight(s"expected a JSON string, got: ${json.render}")

  given JsonBody[Boolean] with
    def encode(a: Boolean): Json = Json.Bool(a)
    def decode(json: Json): Either[String, Boolean] = json match
      case Json.Bool(b) => Right(b)
      case other => Left(s"expected a JSON boolean, got: ${other.render}")

  given JsonBody[Int] with
    def encode(a: Int): Json = Json.Num(BigDecimal(a))
    def decode(json: Json): Either[String, Int] = json match
      case Json.Num(n) if n.isValidInt => Right(n.toInt)
      case other => Left(s"expected a JSON integer, got: ${other.render}")

  given JsonBody[Long] with
    def encode(a: Long): Json = Json.Num(BigDecimal(a))
    def decode(json: Json): Either[String, Long] = json match
      case Json.Num(n) if n.isValidLong => Right(n.toLong)
      case other => Left(s"expected a JSON integer, got: ${other.render}")

  given JsonBody[Double] with
    def encode(a: Double): Json = Json.Num(BigDecimal(a))
    def decode(json: Json): Either[String, Double] = json match
      case Json.Num(n) => Right(n.toDouble)
      case other => Left(s"expected a JSON number, got: ${other.render}")

  given JsonBody[BigDecimal] with
    def encode(a: BigDecimal): Json = Json.Num(a)
    def decode(json: Json): Either[String, BigDecimal] = json match
      case Json.Num(n) => Right(n)
      case other => Left(s"expected a JSON number, got: ${other.render}")
