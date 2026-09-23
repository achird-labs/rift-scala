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
    // The engine removed Lua (rift#450) and refuses it when the imposter is created, so say that
    // rather than calling a once-real engine merely unknown.
    case Json.Str("lua") =>
      Left(
        JsonError.Decode(
          "script engine \"lua\" was removed from the engine (rift#450); use \"rhai\" or \"javascript\"",
          Vector.empty
        )
      )
    case Json.Str(other) => Left(JsonError.Decode(s"unknown script engine: $other", Vector.empty))
    case _ => Left(JsonError.Decode("expected a script engine string", Vector.empty))

/** A `_rift` script: inline `code`, a `file` path, or a `ref` to the imposter's named registry.
  *
  * `engine = None` is legal wire input and means "let the engine resolve it" (the engine's
  * `RiftScriptConfig.engine` and rift-java's are both optional). An engine ≥ 0.18.0 resolves it
  * from the `file` extension (`.rhai` or `.js`), then the imposter's
  * `_rift.scriptEngine.defaultEngine`, then Rhai; 0.17.0 and earlier always ran it as Rhai. It only
  * reaches this model from raw JSON: the `rift.dsl.Script` factories always name their engine.
  *
  * The engine writes the resolved engine back, so `GET /imposters` returns it explicitly. `.lua` is
  * recognised only to be refused: the engine removed Lua (rift#450) and rejects `"engine": "lua"`
  * or a `.lua` file when the imposter is created, so no imposter with one is ever readable.
  */
enum ScriptSource:
  case Inline(engine: Option[ScriptEngine], code: String)
  case File(engine: Option[ScriptEngine], path: String)
  case Ref(name: String)

  def toJson: Json = this match
    case ScriptSource.Inline(engine, code) =>
      ScriptSource.sourceObj(engine, "code" -> Json.Str(code))
    case ScriptSource.File(engine, path) =>
      // types.rs:1213-1217 / RiftScriptConfig.java:13,34,42 — the wire key is "file", not "path".
      ScriptSource.sourceObj(engine, "file" -> Json.Str(path))
    case ScriptSource.Ref(name) => Json.obj("ref" -> Json.Str(name))

object ScriptSource:
  /** `engine` first when present and omitted (never `null`) when absent, as rift-java writes it. */
  private def sourceObj(engine: Option[ScriptEngine], source: (String, Json)): Json =
    Json.Obj(Vector(engine.map("engine" -> _.toJson), Some(source)).flatten)

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
      engine <- fields.field("engine") match
        case Some(engineJson) =>
          ScriptEngine.fromJson(engineJson).map(Some(_)).left.map(_.under("engine"))
        case None => Right(None)
      source <- decodeCodeOrPath(fields, engine)
    yield source

  private def decodeCodeOrPath(
      fields: Vector[(String, Json)],
      engine: Option[ScriptEngine]
  ): Either[JsonError.Decode, ScriptSource] =
    (fields.field("code"), fields.field("file")) match
      case (Some(Json.Str(code)), None) => Right(Inline(engine, code))
      case (None, Some(Json.Str(path))) => Right(File(engine, path))
      case _ => Left(JsonError.Decode("expected exactly one of 'code' or 'file'", Vector.empty))
