package rift.model

import rift.json.{Json, JsonError}

enum TcpFaultKind:
  case ConnectionResetByPeer, EmptyResponse, RandomDataThenClose, MalformedResponseChunk

  def toJson: Json = Json.Str(this match
    case TcpFaultKind.ConnectionResetByPeer => "CONNECTION_RESET_BY_PEER"
    case TcpFaultKind.EmptyResponse => "EMPTY_RESPONSE"
    case TcpFaultKind.RandomDataThenClose => "RANDOM_DATA_THEN_CLOSE"
    case TcpFaultKind.MalformedResponseChunk => "MALFORMED_RESPONSE_CHUNK"
  )

object TcpFaultKind:
  def fromJson(json: Json): Either[JsonError.Decode, TcpFaultKind] = json match
    case Json.Str("CONNECTION_RESET_BY_PEER") => Right(ConnectionResetByPeer)
    case Json.Str("EMPTY_RESPONSE") => Right(EmptyResponse)
    case Json.Str("RANDOM_DATA_THEN_CLOSE") => Right(RandomDataThenClose)
    case Json.Str("MALFORMED_RESPONSE_CHUNK") => Right(MalformedResponseChunk)
    case Json.Str(other) => Left(JsonError.Decode(s"unknown fault kind: $other", Vector.empty))
    case _ => Left(JsonError.Decode("expected a fault kind string", Vector.empty))
