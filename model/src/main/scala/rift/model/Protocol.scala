package rift.model

import rift.json.{Json, JsonError}

/** Closed at `http`/`https` deliberately, even though the facade's `ImposterSpec.protocol` takes an
  * arbitrary `String`. The engine validates the value on create and rejects anything else
  * (`InvalidProtocol`), so a `Custom(name)` case would make states representable that no engine
  * accepts, for no capability gained. The loud decode failure below is therefore correct: no
  * engine-produced document can carry another value.
  */
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
