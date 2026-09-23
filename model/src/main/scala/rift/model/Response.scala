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
      val blockKey = Response.blockKey(behaviors, extra)
      val known = Vector(
        Some("is" -> response.toJson),
        Option.when(behaviors.block.nonEmpty)(blockKey -> behaviors.toJson),
        behaviors.responseLevelRepeat.map(r => "repeat" -> Json.Num(BigDecimal(r.count))),
        rift.map(r => "_rift" -> r.toJson)
      ).flatten
      // Only the keys actually written are modeled: the others may legitimately ride in `extra`,
      // e.g. a `behaviors` array that lost to `_behaviors` on decode, or a `"repeat": null`.
      val modeled = Response.isKeys ++
        Option.when(behaviors.block.nonEmpty)(blockKey) ++
        behaviors.responseLevelRepeat.map(_ => "repeat")
      buildObj(modeled, known, extra)
    case Response.Proxy(proxy, extra) =>
      buildObj(Set("proxy"), Vector("proxy" -> proxy.toJson), extra)
    case Response.Inject(script, extra) =>
      buildObj(Set("inject"), Vector("inject" -> Json.Str(script)), extra)
    case Response.Fault(fault, extra) =>
      buildObj(Set("fault"), Vector("fault" -> fault.toJson), extra)
    case Response.RiftScript(rift, extra) =>
      buildObj(Set("_rift"), Vector("_rift" -> rift.toJson), extra)

object Response:
  private val isKeys = Set("is", "_rift")

  /** The engine takes the object form only under `_behaviors` and the array form only under
    * `behaviors`, and refuses the other pairings, so the key follows the shape being written.
    *
    * The one exception is a document that already carries the preferred key in `extra`: an array
    * read from `_behaviors` beside a losing `behaviors`. The block then goes back under the key it
    * was read from, which reproduces the document rather than colliding with it.
    */
  private def blockKey(behaviors: Behaviors, extra: Vector[(String, Json)]): String =
    val preferred = if behaviors.writesArray then "behaviors" else "_behaviors"
    val other = if preferred == "behaviors" then "_behaviors" else "behaviors"
    if extra.exists(_._1 == preferred) then other else preferred

  /** Which key holds the block, by the engine's rule: `_behaviors` wins when both are present, and
    * `"_behaviors": null` counts as absent. The key not read stays in `extra`, and so does a block
    * that decodes empty: an empty `_behaviors` still shadows a `behaviors` array in the engine, and
    * dropping it on write would switch that array on.
    */
  private def blockSource(fields: Vector[(String, Json)]): Option[(String, Json)] =
    fields.field("_behaviors") match
      case Some(b) if b != Json.Null => Some("_behaviors" -> b)
      case _ =>
        fields.field("behaviors") match
          case Some(b) if b != Json.Null => Some("behaviors" -> b)
          case _ => None

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
    val source = blockSource(fields)
    for
      is <- IsResponse.fromJson(isJson).left.map(_.under("is"))
      block <- source match
        case Some((key, b)) => Behaviors.fromJson(b).left.map(_.under(key))
        case None => Right(Behaviors.empty)
      // `"repeat": null` is absent to the engine; it stays in `extra` so it round-trips
      responseRepeat <- fields.field("repeat") match
        case Some(r) if r != Json.Null =>
          Behaviors
            .decodeRepeat(r)
            .map(n => Some(Behavior.Repeat(n, responseLevel = true)))
            .left
            .map(_.under("repeat"))
        case _ => Right(None)
      rift <- fields.field("_rift") match
        case Some(r) => RiftResponseExt.fromJson(r).map(Some(_)).left.map(_.under("_rift"))
        case None => Right(None)
    yield
      val behaviors = block.copy(entries = block.entries ++ responseRepeat)
      val consumed = isKeys ++
        source.collect { case (key, _) if !block.isEmpty => key } ++
        responseRepeat.map(_ => "repeat")
      Is(is, behaviors, rift, fields.remainder(consumed))
