package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

enum Response:
  case Is(
      response: IsResponse,
      behaviors: Behaviors = Behaviors.empty,
      rift: Option[RiftResponseExt] = None,
      extra: Vector[(String, Json)] = Vector.empty
  )
  case Proxy(proxy: ProxyResponse, extra: Vector[(String, Json)] = Vector.empty)
  case Inject(script: String, extra: Vector[(String, Json)] = Vector.empty)
  case Fault(fault: TcpFaultKind, extra: Vector[(String, Json)] = Vector.empty)
  case RiftScript(rift: RiftResponseExt, extra: Vector[(String, Json)] = Vector.empty)

  def toJson: Json = this match
    case Response.Is(response, behaviors, rift, extra) =>
      val known = Vector(
        Some("is" -> response.toJson),
        if !behaviors.isEmpty then Some("_behaviors" -> behaviors.toJson) else None,
        rift.map(r => "_rift" -> r.toJson)
      ).flatten
      buildObj(Response.isKeys, known, extra)
    case Response.Proxy(proxy, extra) =>
      buildObj(Set("proxy"), Vector("proxy" -> proxy.toJson), extra)
    case Response.Inject(script, extra) =>
      buildObj(Set("inject"), Vector("inject" -> Json.Str(script)), extra)
    case Response.Fault(fault, extra) =>
      buildObj(Set("fault"), Vector("fault" -> fault.toJson), extra)
    case Response.RiftScript(rift, extra) =>
      buildObj(Set("_rift"), Vector("_rift" -> rift.toJson), extra)

object Response:
  private val isKeys = Set("is", "_behaviors", "_rift")

  def fromJson(json: Json): Either[JsonError.Decode, Response] =
    for
      fields <- asObj(json, "response")
      result <- decodeVariant(fields)
    yield result

  private def decodeVariant(fields: Vector[(String, Json)]): Either[JsonError.Decode, Response] =
    fields.field("is") match
      case Some(isJson) => decodeIs(fields, isJson)
      case None => decodeProxy(fields)

  private def decodeProxy(fields: Vector[(String, Json)]): Either[JsonError.Decode, Response] =
    fields.field("proxy") match
      case Some(proxyJson) =>
        ProxyResponse
          .fromJson(proxyJson)
          .left
          .map(_.under("proxy"))
          .map(p => Proxy(p, fields.remainder(Set("proxy"))))
      case None => decodeInject(fields)

  private def decodeInject(fields: Vector[(String, Json)]): Either[JsonError.Decode, Response] =
    fields.field("inject") match
      case Some(Json.Str(script)) => Right(Inject(script, fields.remainder(Set("inject"))))
      case Some(_) =>
        Left(JsonError.Decode("expected a script string", Vector.empty).under("inject"))
      case None => decodeFault(fields)

  private def decodeFault(fields: Vector[(String, Json)]): Either[JsonError.Decode, Response] =
    fields.field("fault") match
      case Some(faultJson) =>
        TcpFaultKind
          .fromJson(faultJson)
          .left
          .map(_.under("fault"))
          .map(f => Fault(f, fields.remainder(Set("fault"))))
      case None => decodeRiftScript(fields)

  private def decodeRiftScript(fields: Vector[(String, Json)]): Either[JsonError.Decode, Response] =
    fields.field("_rift") match
      case Some(riftJson) =>
        RiftResponseExt
          .fromJson(riftJson)
          .left
          .map(_.under("_rift"))
          .map(r => RiftScript(r, fields.remainder(Set("_rift"))))
      case None =>
        Left(
          JsonError.Decode(
            "response must contain one of 'is', 'proxy', 'inject', 'fault', '_rift'",
            Vector.empty
          )
        )

  private def decodeIs(
      fields: Vector[(String, Json)],
      isJson: Json
  ): Either[JsonError.Decode, Response] =
    for
      is <- IsResponse.fromJson(isJson).left.map(_.under("is"))
      behaviors <- fields.field("_behaviors") match
        case Some(b) => Behaviors.fromJson(b).left.map(_.under("_behaviors"))
        case None => Right(Behaviors.empty)
      rift <- fields.field("_rift") match
        case Some(r) => RiftResponseExt.fromJson(r).map(Some(_)).left.map(_.under("_rift"))
        case None => Right(None)
    yield Is(is, behaviors, rift, fields.remainder(isKeys))
