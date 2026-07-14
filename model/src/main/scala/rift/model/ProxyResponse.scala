package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** The ergonomic DSL input for `.generateBy(...)` — NOT a wire type. The wire `predicateGenerators`
  * entries are raw, opaque predicate objects (see [[ProxyResponse.predicateGenerators]]); this enum
  * exists only so the DSL can name a field and have it rendered as one.
  */
enum RequestField:
  case Method, Path, Query, Headers, Body

  private def wireName: String = this match
    case RequestField.Method => "method"
    case RequestField.Path => "path"
    case RequestField.Query => "query"
    case RequestField.Headers => "headers"
    case RequestField.Body => "body"

  /** The raw predicate-generator object the engine expects for this field, e.g.
    * `{"matches":{"path":true}}` — types.rs:772-789 (`predicate_generators:
    * Vec<serde_json::Value>`).
    */
  def toGeneratorJson: Json = Json.obj("matches" -> Json.obj(wireName -> Json.Bool(true)))

enum ProxyMode:
  case ProxyOnce, ProxyAlways, ProxyTransparent

  def toJson: Json = Json.Str(this match
    case ProxyMode.ProxyOnce => "proxyOnce"
    case ProxyMode.ProxyAlways => "proxyAlways"
    case ProxyMode.ProxyTransparent => "proxyTransparent"
  )

object ProxyMode:
  def fromJson(json: Json): Either[JsonError.Decode, ProxyMode] = json match
    case Json.Str("proxyOnce") => Right(ProxyOnce)
    case Json.Str("proxyAlways") => Right(ProxyAlways)
    case Json.Str("proxyTransparent") => Right(ProxyTransparent)
    case Json.Str(other) => Left(JsonError.Decode(s"unknown proxy mode: $other", Vector.empty))
    case _ => Left(JsonError.Decode("expected a proxy mode string", Vector.empty))

/** A proxy response. `predicateGenerators` is raw, opaque JSON — types.rs:772-789 models it as
  * `Vec<serde_json::Value>`, so the engine never interprets it structurally, only passes it through
  * to the recorded-stub predicate builder. Proof: `ProxyResponse.java:30`,
  * docs/mountebank/proxy.md: 91-92, zio-bdd `RiftProtocol.scala:216`.
  */
final case class ProxyResponse(
    to: String,
    mode: ProxyMode = ProxyMode.ProxyAlways,
    predicateGenerators: Vector[Json] = Vector.empty,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json =
    val known = Vector(
      Some("to" -> Json.Str(to)),
      Some("mode" -> mode.toJson),
      if predicateGenerators.nonEmpty then
        Some("predicateGenerators" -> Json.Arr(predicateGenerators))
      else None
    ).flatten
    buildObj(ProxyResponse.modeledKeys, known, extra)

object ProxyResponse:
  private val modeledKeys = Set("to", "mode", "predicateGenerators")

  def fromJson(json: Json): Either[JsonError.Decode, ProxyResponse] =
    for
      fields <- asObj(json, "proxy")
      to <- reqString(fields, "to")
      mode <- fields.field("mode") match
        case Some(m) => ProxyMode.fromJson(m).left.map(_.under("mode"))
        case None => Right(ProxyMode.ProxyAlways)
      generators <- fields.field("predicateGenerators") match
        case Some(g) =>
          g.asArray.toRight[JsonError.Decode](
            JsonError.Decode("expected an array", Vector.empty).under("predicateGenerators")
          )
        case None => Right(Vector.empty)
    yield ProxyResponse(to, mode, generators, fields.remainder(modeledKeys))
