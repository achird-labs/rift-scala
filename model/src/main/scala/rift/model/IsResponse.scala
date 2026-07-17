package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

final case class IsResponse(
    statusCode: Option[Int] = None,
    headers: Headers = Headers.empty,
    body: Option[Json] = None,
    extra: Vector[(String, Json)] = Vector.empty,
    /** `statusCode` verbatim, for the rare wire form that isn't a JSON number (e.g. mimeo's string
      * `"200"`). Mutually exclusive with `statusCode` — `semanticEquals` is strict, so a string
      * can't be coerced to a number and re-emitted faithfully any other way.
      */
    rawStatusCode: Option[Json] = None
):
  def toJson: Json =
    val known = Vector(
      statusCode.map(s => "statusCode" -> Json.Num(BigDecimal(s))),
      rawStatusCode.map(r => "statusCode" -> r),
      if headers.entries.nonEmpty then Some("headers" -> headers.toJson) else None,
      body.map(b => "body" -> b)
    ).flatten
    buildObj(IsResponse.modeledKeys, known, extra)

object IsResponse:
  private val modeledKeys = Set("statusCode", "headers", "body")

  /** A number decodes to the typed field, as before. Anything else that isn't a plausible malformed
    * number (i.e. not a number at all) is preserved verbatim on `rawStatusCode` rather than
    * rejected — engines/SDKs in the wild emit `statusCode` as a string, and the model's job is to
    * round-trip what it was given, not to normalize it. A boolean, object, array, or null still
    * fails loudly: those aren't a status code under any known wire form.
    */
  private def decodeStatusCode(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, (Option[Int], Option[Json])] =
    fields.field("statusCode") match
      case None => Right((None, None))
      case Some(Json.Num(n)) if n.isValidInt => Right((Some(n.toInt), None))
      case Some(Json.Num(_)) =>
        Left(JsonError.Decode("expected an integer", Vector.empty).under("statusCode"))
      case Some(raw @ Json.Str(_)) => Right((None, Some(raw)))
      case Some(_) =>
        Left(JsonError.Decode("expected a number or string", Vector.empty).under("statusCode"))

  def fromJson(json: Json): Either[JsonError.Decode, IsResponse] =
    for
      fields <- asObj(json, "is")
      statusCodeResult <- decodeStatusCode(fields)
      (statusCode, rawStatusCode) = statusCodeResult
      headers <- fields.field("headers") match
        case Some(h) => Headers.fromJson(h).left.map(_.under("headers"))
        case None => Right(Headers.empty)
    yield IsResponse(
      statusCode,
      headers,
      fields.field("body"),
      fields.remainder(modeledKeys),
      rawStatusCode
    )
