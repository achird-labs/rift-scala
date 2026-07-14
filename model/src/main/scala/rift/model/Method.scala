package rift.model

import rift.json.{Json, JsonError}

enum Method:
  case GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
  case Custom(name: String)

  def toJson: Json = this match
    case Method.Custom(name) => Json.Str(name)
    case known => Json.Str(known.toString)

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
