package rift.model

import rift.json.{Json, JsonError}
import JsonSupport.*

enum ScriptEngine:
  case Rhai, JavaScript

  // RiftScriptEngineConfig.java:8 / zio-bdd RiftProtocol.scala:178 — "javascript" is canonical; "js"
  // is an engine-accepted alias, not what it documents/emits, so we don't round-trip it on encode.
  def toJson: Json = Json.Str(this match
    case ScriptEngine.Rhai => "rhai"
    case ScriptEngine.JavaScript => "javascript"
  )

object ScriptEngine:
  def fromJson(json: Json): Either[JsonError.Decode, ScriptEngine] = json match
    case Json.Str("rhai") => Right(Rhai)
    case Json.Str("js") | Json.Str("javascript") => Right(JavaScript)
    case Json.Str(other) => Left(JsonError.Decode(s"unknown script engine: $other", Vector.empty))
    case _ => Left(JsonError.Decode("expected a script engine string", Vector.empty))

enum ScriptSource:
  case Inline(engine: ScriptEngine, code: String)
  case File(engine: ScriptEngine, path: String)
  case Ref(name: String)

  def toJson: Json = this match
    case ScriptSource.Inline(engine, code) =>
      Json.obj("engine" -> engine.toJson, "code" -> Json.Str(code))
    case ScriptSource.File(engine, path) =>
      // types.rs:1213-1217 / RiftScriptConfig.java:13,34,42 — the wire key is "file", not "path".
      Json.obj("engine" -> engine.toJson, "file" -> Json.Str(path))
    case ScriptSource.Ref(name) => Json.obj("ref" -> Json.Str(name))

object ScriptSource:
  def fromJson(json: Json): Either[JsonError.Decode, ScriptSource] =
    for
      fields <- asObj(json, "script")
      result <- decodeVariant(fields)
    yield result

  private def decodeVariant(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, ScriptSource] =
    fields.field("ref") match
      case Some(Json.Str(name)) => Right(Ref(name))
      case Some(_) => Left(JsonError.Decode("expected a string", Vector.empty))
      case None => decodeEngineSource(fields)

  private def decodeEngineSource(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, ScriptSource] =
    for
      engineJson <- fields
        .field("engine")
        .toRight[JsonError.Decode](JsonError.Decode("missing required field", Vector.empty))
      engine <- ScriptEngine.fromJson(engineJson)
      source <- decodeCodeOrPath(fields, engine)
    yield source

  private def decodeCodeOrPath(
      fields: Vector[(String, Json)],
      engine: ScriptEngine
  ): Either[JsonError.Decode, ScriptSource] =
    (fields.field("code"), fields.field("file")) match
      case (Some(Json.Str(code)), None) => Right(Inline(engine, code))
      case (None, Some(Json.Str(path))) => Right(File(engine, path))
      case _ => Left(JsonError.Decode("expected exactly one of 'code' or 'file'", Vector.empty))
