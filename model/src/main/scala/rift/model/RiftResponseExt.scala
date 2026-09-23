package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** The `_rift` extension block on a response: probabilistic faults, embedded scripts, and
  * `${request.*}` templating.
  *
  * `extra` carries every child the model does not know, verbatim and in order, so a decode ->
  * encode round trip never drops one. Engine 0.18.0 added `stateOps` (declarative flow-state
  * writes) and `dataset` (a lookup named by dataset, a carrier the engine itself does not read).
  */
final case class RiftResponseExt(
    fault: Option[FaultConfig] = None,
    script: Option[ScriptSource] = None,
    templated: Boolean = false,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json = buildObj(
    RiftResponseExt.knownKeys,
    Vector(
      fault.map(f => "fault" -> f.toJson),
      script.map(s => "script" -> s.toJson),
      if templated then Some("templated" -> Json.Bool(true)) else None
    ).flatten,
    extra
  )

object RiftResponseExt:
  private val knownKeys = Set("fault", "script", "templated")

  def fromJson(json: Json): Either[JsonError.Decode, RiftResponseExt] =
    for
      fields <- asObj(json, "_rift")
      fault <- fields.field("fault") match
        case Some(f) => FaultConfig.fromJson(f).map(Some(_)).left.map(_.under("fault"))
        case None => Right(None)
      script <- fields.field("script") match
        case Some(s) => ScriptSource.fromJson(s).map(Some(_)).left.map(_.under("script"))
        case None => Right(None)
      templated <- optBool(fields, "templated", false)
    yield RiftResponseExt(fault, script, templated, fields.remainder(knownKeys))
