package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** The `_rift` extension block on a response: probabilistic faults, embedded scripts, and
  * `${request.*}` templating.
  *
  * `extra` carries every child the model does not know, verbatim and in order, so a decode ->
  * encode round trip never drops one — engine 0.18.0's `dataset`, for instance (a lookup named by
  * dataset, a carrier the engine itself does not read). `stateOps` is typed ([[StateOp]]); an empty
  * list is not written, since the engine runs nothing for it either way.
  */
final case class RiftResponseExt(
    fault: Option[FaultConfig] = None,
    script: Option[ScriptSource] = None,
    templated: Boolean = false,
    stateOps: Vector[StateOp] = Vector.empty,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json = buildObj(
    RiftResponseExt.knownKeys,
    Vector(
      fault.map(f => "fault" -> f.toJson),
      script.map(s => "script" -> s.toJson),
      if templated then Some("templated" -> Json.Bool(true)) else None,
      Option.when(stateOps.nonEmpty)("stateOps" -> Json.Arr(stateOps.map(_.toJson)))
    ).flatten,
    extra
  )

object RiftResponseExt:
  private val knownKeys = Set("fault", "script", "templated", "stateOps")

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
      stateOps <- fields.field("stateOps") match
        case Some(ops) => decodeArray(ops, StateOp.fromJson).left.map(_.under("stateOps"))
        case None => Right(Vector.empty)
    yield RiftResponseExt(fault, script, templated, stateOps, fields.remainder(knownKeys))
