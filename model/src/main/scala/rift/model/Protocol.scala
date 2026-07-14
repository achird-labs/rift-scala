package rift.model

import rift.json.{Json, JsonError}

enum Protocol:
  case Http, Https

  def toJson: Json = this match
    case Protocol.Http => Json.Str("http")
    case Protocol.Https => Json.Str("https")

object Protocol:
  def fromJson(json: Json): Either[JsonError.Decode, Protocol] = json match
    case Json.Str("http") => Right(Http)
    case Json.Str("https") => Right(Https)
    case Json.Str(other) => Left(JsonError.Decode(s"unknown protocol: $other", Vector.empty))
    case _ => Left(JsonError.Decode("expected a protocol string", Vector.empty))
