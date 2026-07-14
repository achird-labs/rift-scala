package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

final case class LatencyFault(
    probability: Double,
    ms: Option[Long] = None,
    minMs: Option[Long] = None,
    maxMs: Option[Long] = None
):
  def toJson: Json = Json.Obj(
    Vector(
      Some("probability" -> Json.Num(BigDecimal(probability))),
      ms.map(v => "ms" -> Json.Num(BigDecimal(v))),
      minMs.map(v => "minMs" -> Json.Num(BigDecimal(v))),
      maxMs.map(v => "maxMs" -> Json.Num(BigDecimal(v)))
    ).flatten
  )

object LatencyFault:
  def fromJson(json: Json): Either[JsonError.Decode, LatencyFault] =
    for
      fields <- asObj(json, "latency")
      probability <- optDouble(fields, "probability").map(_.getOrElse(1.0))
      ms <- optLong(fields, "ms")
      minMs <- optLong(fields, "minMs")
      maxMs <- optLong(fields, "maxMs")
    yield LatencyFault(probability, ms, minMs, maxMs)

final case class ErrorFault(
    probability: Double,
    status: Int,
    // types.rs:1184-1185 — `RiftErrorFault.body` is `Option<String>`, not a typed JSON value: the
    // engine writes this string verbatim as the response body, it never parses it.
    body: Option[String] = None,
    headers: Headers = Headers.empty
):
  def toJson: Json = Json.Obj(
    Vector(
      Some("probability" -> Json.Num(BigDecimal(probability))),
      Some("status" -> Json.Num(BigDecimal(status))),
      body.map(b => "body" -> Json.Str(b)),
      if headers.entries.nonEmpty then Some("headers" -> headers.toJson) else None
    ).flatten
  )

object ErrorFault:
  def fromJson(json: Json): Either[JsonError.Decode, ErrorFault] =
    for
      fields <- asObj(json, "error")
      probability <- optDouble(fields, "probability").map(_.getOrElse(1.0))
      status <- optInt(fields, "status").flatMap(
        _.toRight[JsonError.Decode](
          JsonError.Decode("missing required field", Vector.empty).under("status")
        )
      )
      body <- optString(fields, "body")
      headers <- fields.field("headers") match
        case Some(h) => Headers.fromJson(h).left.map(_.under("headers"))
        case None => Right(Headers.empty)
    yield ErrorFault(probability, status, body, headers)

/** `_rift.fault.tcp` (rift#531). Two mutually-exclusive wire forms, hand-rolled since they aren't a
  * normal tagged union: a bare fault-kind string that always fires (probability 1.0), or the object
  * form `{"probability": <required>, "type": <kind>}` — a Rift extension that exists solely to
  * carry `probability`, so it is required (no default) rather than merely optional. Serialized back
  * in exactly the form it was parsed from (string stays string, object stays object) so `GET
  * /imposters` round-trips. Proof: rift `RiftTcpFault`
  * (crates/rift-mock-core/src/imposter/types.rs:1070-1129); `RiftTcpFault.java`; zio-bdd
  * `RiftProtocol.scala:140`.
  */
final case class TcpFault(kind: TcpFaultKind, probability: Option[Double] = None):
  def toJson: Json = probability match
    case None => kind.toJson
    case Some(p) =>
      Json.obj("probability" -> Json.Num(BigDecimal(p)), "type" -> kind.toJson)

object TcpFault:
  def fromJson(json: Json): Either[JsonError.Decode, TcpFault] = json match
    case Json.Str(_) => TcpFaultKind.fromJson(json).map(TcpFault(_, None))
    case Json.Obj(fields) =>
      for
        probability <- optDouble(fields, "probability").flatMap(
          _.toRight[JsonError.Decode](
            JsonError.Decode("missing required field", Vector.empty).under("probability")
          )
        )
        typeJson <- fields
          .field("type")
          .toRight[JsonError.Decode](
            JsonError.Decode("missing required field", Vector.empty).under("type")
          )
        kind <- TcpFaultKind.fromJson(typeJson).left.map(_.under("type"))
      yield TcpFault(kind, Some(probability))
    case _ =>
      Left(
        JsonError.Decode(
          "expected a fault-type string or an object { probability, type }",
          Vector.empty
        )
      )

/** `_rift.fault` — probabilistic latency/error/tcp fault injection. */
final case class FaultConfig(
    latency: Option[LatencyFault] = None,
    error: Option[ErrorFault] = None,
    tcp: Option[TcpFault] = None
):
  def toJson: Json = Json.Obj(
    Vector(
      latency.map(l => "latency" -> l.toJson),
      error.map(e => "error" -> e.toJson),
      tcp.map(t => "tcp" -> t.toJson)
    ).flatten
  )

object FaultConfig:
  def fromJson(json: Json): Either[JsonError.Decode, FaultConfig] =
    for
      fields <- asObj(json, "fault")
      latency <- fields.field("latency") match
        case Some(l) => LatencyFault.fromJson(l).map(Some(_)).left.map(_.under("latency"))
        case None => Right(None)
      error <- fields.field("error") match
        case Some(e) => ErrorFault.fromJson(e).map(Some(_)).left.map(_.under("error"))
        case None => Right(None)
      tcp <- fields.field("tcp") match
        case Some(t) => TcpFault.fromJson(t).map(Some(_)).left.map(_.under("tcp"))
        case None => Right(None)
    yield FaultConfig(latency, error, tcp)
