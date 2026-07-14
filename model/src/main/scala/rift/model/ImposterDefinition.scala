package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

final case class ImposterDefinition(
    port: Option[Port] = None,
    protocol: Protocol = Protocol.Http,
    name: Option[String] = None,
    recordRequests: Boolean = false,
    recordMatches: Boolean = false,
    stubs: Vector[Stub] = Vector.empty,
    defaultResponse: Option[IsResponse] = None,
    defaultForward: Option[String] = None,
    allowCors: Boolean = false,
    strictBehaviors: Boolean = false,
    tls: Option[TlsMaterial] = None,
    rift: Option[RiftConfig] = None,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json =
    val fixed: Vector[Option[(String, Json)]] = Vector(
      port.map(p => "port" -> Json.Num(BigDecimal(Port.value(p)))),
      Some("protocol" -> protocol.toJson),
      name.map(n => "name" -> Json.Str(n)),
      if recordRequests then Some("recordRequests" -> Json.Bool(true)) else None,
      if recordMatches then Some("recordMatches" -> Json.Bool(true)) else None,
      if stubs.nonEmpty then Some("stubs" -> Json.Arr(stubs.map(_.toJson))) else None,
      defaultResponse.map(r => "defaultResponse" -> r.toJson),
      defaultForward.map(f => "defaultForward" -> Json.Str(f)),
      if allowCors then Some("allowCORS" -> Json.Bool(true)) else None,
      if strictBehaviors then Some("strictBehaviors" -> Json.Bool(true)) else None,
      rift.map(r => "_rift" -> r.toJson)
    )
    // types.rs:812-817 / ImposterDefinition.java:37-38,141-142 — no "tls" wrapper on the wire:
    // `cert`/`key` are flat top-level fields on the imposter object.
    val tlsFields: Vector[(String, Json)] =
      tls.toVector.flatMap(_.toJson.asObject.getOrElse(Vector.empty))
    buildObj(ImposterDefinition.modeledKeys, fixed.flatten ++ tlsFields, extra)

object ImposterDefinition:
  private val modeledKeys = Set(
    "port",
    "protocol",
    "name",
    "recordRequests",
    "recordMatches",
    "stubs",
    "defaultResponse",
    "defaultForward",
    "allowCORS",
    "strictBehaviors",
    "cert",
    "key",
    "_rift"
  )

  def fromJson(json: Json): Either[JsonError.Decode, ImposterDefinition] =
    for
      fields <- asObj(json, "imposter")
      port <- fields.field("port") match
        case None => Right(None)
        case Some(Json.Num(n)) if n.isValidInt =>
          Port
            .from(n.toInt)
            .map(Some(_))
            .left
            .map(msg => JsonError.Decode(msg, Vector.empty).under("port"))
        case Some(_) =>
          Left(JsonError.Decode("expected an integer port", Vector.empty).under("port"))
      protocol <- fields.field("protocol") match
        case Some(p) => Protocol.fromJson(p).left.map(_.under("protocol"))
        case None => Right(Protocol.Http)
      name <- optString(fields, "name")
      recordRequests <- optBool(fields, "recordRequests", false)
      recordMatches <- optBool(fields, "recordMatches", false)
      stubs <- fields.field("stubs") match
        case Some(s) => decodeArray(s, Stub.fromJson).left.map(_.under("stubs"))
        case None => Right(Vector.empty)
      defaultResponse <- fields.field("defaultResponse") match
        case Some(r) => IsResponse.fromJson(r).map(Some(_)).left.map(_.under("defaultResponse"))
        case None => Right(None)
      defaultForward <- optString(fields, "defaultForward")
      allowCors <- optBool(fields, "allowCORS", false)
      strictBehaviors <- optBool(fields, "strictBehaviors", false)
      // cert/key are flat on the wire — see the note on `toJson` above — so TlsMaterial is decoded
      // directly from the imposter's own fields, not from a nested "tls" object.
      tls <- (fields.field("cert"), fields.field("key")) match
        case (None, None) => Right(None)
        case _ => TlsMaterial.fromJson(json).map(Some(_)).left.map(_.under("cert/key"))
      rift <- fields.field("_rift") match
        case Some(r) => RiftConfig.fromJson(r).map(Some(_)).left.map(_.under("_rift"))
        case None => Right(None)
    yield ImposterDefinition(
      port,
      protocol,
      name,
      recordRequests,
      recordMatches,
      stubs,
      defaultResponse,
      defaultForward,
      allowCors,
      strictBehaviors,
      tls,
      rift,
      fields.remainder(modeledKeys)
    )
