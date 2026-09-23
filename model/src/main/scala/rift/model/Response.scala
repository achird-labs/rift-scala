package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** A stub response. Every kind carries a [[Behaviors]] block (issue #173): engine 0.18.0 runs
  * behaviors on `is`, `proxy` (on the upstream response, before it is recorded) and `inject`
  * responses, and honours `repeat` on every kind, `fault` and `_rift`-only included. The block is
  * read and written by the same rules on all five, so they cannot drift apart.
  */
enum Response:
  case Is(
      response: IsResponse,
      behaviors: Behaviors = Behaviors.empty,
      rift: Option[RiftResponseExt] = None,
      extra: Vector[(String, Json)] = Vector.empty
  )
  case Proxy(
      proxy: ProxyResponse,
      behaviors: Behaviors = Behaviors.empty,
      extra: Vector[(String, Json)] = Vector.empty
  )
  case Inject(
      script: String,
      behaviors: Behaviors = Behaviors.empty,
      extra: Vector[(String, Json)] = Vector.empty
  )
  case Fault(
      fault: TcpFaultKind,
      behaviors: Behaviors = Behaviors.empty,
      extra: Vector[(String, Json)] = Vector.empty
  )
  case RiftScript(
      rift: RiftResponseExt,
      behaviors: Behaviors = Behaviors.empty,
      extra: Vector[(String, Json)] = Vector.empty
  )

  def toJson: Json = this match
    case Response.Is(response, behaviors, rift, extra) =>
      Response.write(
        Vector("is" -> response.toJson),
        behaviors,
        rift.map(r => "_rift" -> r.toJson).toVector,
        extra
      )
    case Response.Proxy(proxy, behaviors, extra) =>
      Response.write(Vector("proxy" -> proxy.toJson), behaviors, Vector.empty, extra)
    case Response.Inject(script, behaviors, extra) =>
      Response.write(Vector("inject" -> Json.Str(script)), behaviors, Vector.empty, extra)
    case Response.Fault(fault, behaviors, extra) =>
      Response.write(Vector("fault" -> fault.toJson), behaviors, Vector.empty, extra)
    case Response.RiftScript(rift, behaviors, extra) =>
      Response.write(Vector("_rift" -> rift.toJson), behaviors, Vector.empty, extra)

object Response:

  /** Writes `lead`, then the behaviors block and a response-level `repeat`, then `trail`, then
    * `extra`. Only the keys actually written are modeled: the others may legitimately ride in
    * `extra` — a `behaviors` array that lost to `_behaviors` on decode, or a `"repeat": null`.
    */
  private def write(
      lead: Vector[(String, Json)],
      behaviors: Behaviors,
      trail: Vector[(String, Json)],
      extra: Vector[(String, Json)]
  ): Json =
    val key = blockKey(behaviors, extra)
    val block = Option.when(behaviors.block.nonEmpty)(key -> behaviors.toJson)
    val repeat =
      behaviors.responseLevelRepeat.map(r => "repeat" -> Json.Num(BigDecimal(r.count)))
    val known = lead ++ block ++ repeat ++ trail
    buildObj(known.map(_._1).toSet, known, extra)

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

  /** A response's behaviors — its block plus a response-level `repeat` — and the keys they
    * consumed.
    */
  private def decodeBehaviors(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, (Behaviors, Set[String])] =
    val source = blockSource(fields)
    for
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
    yield
      val consumed =
        source.collect { case (key, _) if !block.isEmpty => key }.toSet ++
          responseRepeat.map(_ => "repeat")
      (block.copy(entries = block.entries ++ responseRepeat), consumed)

  def fromJson(json: Json): Either[JsonError.Decode, Response] =
    for
      fields <- asObj(json, "response")
      decoded <- decodeBehaviors(fields)
      (behaviors, consumed) = decoded
      result <- decodeVariant(fields, behaviors, consumed)
    yield result

  private def decodeVariant(
      fields: Vector[(String, Json)],
      behaviors: Behaviors,
      consumed: Set[String]
  ): Either[JsonError.Decode, Response] =
    def rest(primary: String*): Vector[(String, Json)] =
      fields.remainder(consumed ++ primary)

    (
      fields.field("is"),
      fields.field("proxy"),
      fields.field("inject"),
      fields.field("fault"),
      fields.field("_rift")
    ) match
      case (Some(isJson), _, _, _, riftJson) =>
        for
          is <- IsResponse.fromJson(isJson).left.map(_.under("is"))
          rift <- riftJson match
            case Some(r) => RiftResponseExt.fromJson(r).map(Some(_)).left.map(_.under("_rift"))
            case None => Right(None)
        yield Is(is, behaviors, rift, rest("is", "_rift"))
      case (None, Some(proxyJson), _, _, _) =>
        ProxyResponse
          .fromJson(proxyJson)
          .left
          .map(_.under("proxy"))
          .map(p => Proxy(p, behaviors, rest("proxy")))
      case (None, None, Some(Json.Str(script)), _, _) =>
        Right(Inject(script, behaviors, rest("inject")))
      case (None, None, Some(_), _, _) =>
        Left(JsonError.Decode("expected a script string", Vector.empty).under("inject"))
      case (None, None, None, Some(faultJson), _) =>
        TcpFaultKind
          .fromJson(faultJson)
          .left
          .map(_.under("fault"))
          .map(f => Fault(f, behaviors, rest("fault")))
      case (None, None, None, None, Some(riftJson)) =>
        RiftResponseExt
          .fromJson(riftJson)
          .left
          .map(_.under("_rift"))
          .map(r => RiftScript(r, behaviors, rest("_rift")))
      case _ =>
        Left(
          JsonError.Decode(
            "response must contain one of 'is', 'proxy', 'inject', 'fault', '_rift'",
            Vector.empty
          )
        )
