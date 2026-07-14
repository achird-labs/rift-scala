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

/** A `proxy.pathRewrite` — the recorded stub's path is rewritten from `from` to `to`. Proof:
  * `PathRewrite.java:7`; engine `types.rs:780-788`.
  */
final case class PathRewrite(from: String, to: String):
  def toJson: Json = Json.obj("from" -> Json.Str(from), "to" -> Json.Str(to))

object PathRewrite:
  def fromJson(json: Json): Either[JsonError.Decode, PathRewrite] =
    for
      fields <- asObj(json, "pathRewrite")
      from <- reqString(fields, "from")
      to <- reqString(fields, "to")
    yield PathRewrite(from, to)

/** A proxy response. `predicateGenerators` is raw, opaque JSON — types.rs:772-789 models it as
  * `Vec<serde_json::Value>`, so the engine never interprets it structurally, only passes it through
  * to the recorded-stub predicate builder. Proof: `ProxyResponse.java:30`,
  * docs/mountebank/proxy.md: 91-92, zio-bdd `RiftProtocol.scala:216`.
  *
  * `addWaitBehavior`/`injectHeaders`/`addDecorateBehavior`/`pathRewrite` shape what gets *recorded*
  * (or what is sent upstream), and are emitted only when set — see [[toJson]].
  */
final case class ProxyResponse(
    to: String,
    mode: ProxyMode = ProxyMode.ProxyAlways,
    predicateGenerators: Vector[Json] = Vector.empty,
    addWaitBehavior: Boolean = false,
    injectHeaders: Vector[(String, String)] = Vector.empty,
    addDecorateBehavior: Option[String] = None,
    pathRewrite: Option[PathRewrite] = None,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json =
    val known = Vector(
      Some("to" -> Json.Str(to)),
      Some("mode" -> mode.toJson),
      if predicateGenerators.nonEmpty then
        Some("predicateGenerators" -> Json.Arr(predicateGenerators))
      else None,
      // Emitted only when set, mirroring rift-java (`ProxyResponse.java:97-105`) — the engine
      // defaults them, so writing `false`/`{}` would be a gratuitous divergence on the wire.
      if addWaitBehavior then Some("addWaitBehavior" -> Json.Bool(true)) else None,
      if injectHeaders.nonEmpty then
        Some("injectHeaders" -> Json.Obj(injectHeaders.map((k, v) => k -> Json.Str(v))))
      else None,
      addDecorateBehavior.map(d => "addDecorateBehavior" -> Json.Str(d)),
      pathRewrite.map(p => "pathRewrite" -> p.toJson)
    ).flatten
    buildObj(ProxyResponse.modeledKeys, known, extra)

object ProxyResponse:
  private val modeledKeys =
    Set(
      "to",
      "mode",
      "predicateGenerators",
      "addWaitBehavior",
      "injectHeaders",
      "addDecorateBehavior",
      "pathRewrite"
    )

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
      addWait <- optBool(fields, "addWaitBehavior", false)
      injectHeaders <- fields.field("injectHeaders") match
        case Some(h) =>
          asObj(h, "injectHeaders").left.map(_.under("injectHeaders")).flatMap { entries =>
            entries
              .foldLeft[Either[JsonError.Decode, Vector[(String, String)]]](Right(Vector.empty)):
                case (acc, (k, Json.Str(v))) => acc.map(_ :+ (k -> v))
                case (_, (k, _)) =>
                  Left(
                    JsonError
                      .Decode("expected a string", Vector.empty)
                      .under(k)
                      .under("injectHeaders")
                  )
          }
        case None => Right(Vector.empty)
      decorate <- optString(fields, "addDecorateBehavior")
      rewrite <- fields.field("pathRewrite") match
        case Some(p) => PathRewrite.fromJson(p).map(Some(_)).left.map(_.under("pathRewrite"))
        case None => Right(None)
    yield ProxyResponse(
      to,
      mode,
      generators,
      addWait,
      injectHeaders,
      decorate,
      rewrite,
      fields.remainder(modeledKeys)
    )
