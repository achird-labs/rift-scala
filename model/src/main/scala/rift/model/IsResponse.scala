package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

final case class IsResponse(
    statusCode: Option[Int] = None,
    headers: Headers = Headers.empty,
    body: Option[Json] = None,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json =
    val known = Vector(
      statusCode.map(s => "statusCode" -> Json.Num(BigDecimal(s))),
      if headers.entries.nonEmpty then Some("headers" -> headers.toJson) else None,
      body.map(b => "body" -> b)
    ).flatten
    buildObj(IsResponse.modeledKeys, known, extra)

object IsResponse:
  private val modeledKeys = Set("statusCode", "headers", "body")

  def fromJson(json: Json): Either[JsonError.Decode, IsResponse] =
    for
      fields <- asObj(json, "is")
      statusCode <- optInt(fields, "statusCode")
      headers <- fields.field("headers") match
        case Some(h) => Headers.fromJson(h).left.map(_.under("headers"))
        case None => Right(Headers.empty)
    yield IsResponse(statusCode, headers, fields.field("body"), fields.remainder(modeledKeys))
