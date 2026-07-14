package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** The `_rift` extension block on a response: probabilistic faults, embedded scripts, and
  * `${request.*}` templating.
  */
final case class RiftResponseExt(
    fault: Option[FaultConfig] = None,
    script: Option[ScriptSource] = None,
    templated: Boolean = false
):
  def toJson: Json = Json.Obj(
    Vector(
      fault.map(f => "fault" -> f.toJson),
      script.map(s => "script" -> s.toJson),
      if templated then Some("templated" -> Json.Bool(true)) else None
    ).flatten
  )

object RiftResponseExt:
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
    yield RiftResponseExt(fault, script, templated)
