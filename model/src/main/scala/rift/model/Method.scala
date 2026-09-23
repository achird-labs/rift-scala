package rift.model

import rift.json.{Json, JsonError}

enum Method:
  case GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
  case Custom(name: String)

  /** The method as it appears on the wire — `PURGE`, not `Custom(PURGE)`. */
  def wireName: String = this match
    case Method.Custom(name) => name
    case known => known.toString

  def toJson: Json = Json.Str(wireName)

object Method:
  def fromJson(json: Json): Either[JsonError.Decode, Method] = json match
    case Json.Str(s) => Right(fromWire(s))
    case _ => Left(JsonError.Decode("expected a method string", Vector.empty))

  private def fromWire(s: String): Method = s match
    case "GET" => GET
    case "POST" => POST
    case "PUT" => PUT
    case "DELETE" => DELETE
    case "PATCH" => PATCH
    case "HEAD" => HEAD
    case "OPTIONS" => OPTIONS
    case other => Custom(other)
