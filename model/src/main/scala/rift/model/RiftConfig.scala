package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** Redis backend settings for `_rift.flowState.redis` — types.rs:923-946, `RiftRedisConfig.java`.
  */
final case class RedisConfig(url: String, poolSize: Long = 10, keyPrefix: String = "rift:"):
  def toJson: Json = Json.obj(
    "url" -> Json.Str(url),
    "poolSize" -> Json.Num(BigDecimal(poolSize)),
    "keyPrefix" -> Json.Str(keyPrefix)
  )

object RedisConfig:
  def fromJson(json: Json): Either[JsonError.Decode, RedisConfig] =
    for
      fields <- asObj(json, "redis")
      url <- reqString(fields, "url")
      poolSize <- optLong(fields, "poolSize").map(_.getOrElse(10L))
      keyPrefix <- optString(fields, "keyPrefix").map(_.getOrElse("rift:"))
    yield RedisConfig(url, poolSize, keyPrefix)

/** `_rift.flowState`'s backend selector. The wire discriminant key is `"backend"` (not `"kind"`),
  * and the Redis settings nest under a `"redis"` object rather than sitting flat next to it —
  * types.rs:888-923, `RiftFlowStateConfig.java`, zio-bdd `RiftProtocol.scala:36`.
  */
enum FlowStateBackend:
  case InMemory
  case Redis(config: RedisConfig)

  def toJson: Json = this match
    case FlowStateBackend.InMemory => Json.obj("backend" -> Json.Str("inmemory"))
    case FlowStateBackend.Redis(config) =>
      Json.obj("backend" -> Json.Str("redis"), "redis" -> config.toJson)

object FlowStateBackend:
  def fromJson(json: Json): Either[JsonError.Decode, FlowStateBackend] =
    for
      fields <- asObj(json, "flowState backend")
      kind <- reqString(fields, "backend")
      backend <- kind match
        case "inmemory" => Right(InMemory)
        case "redis" =>
          fields.field("redis") match
            case Some(r) => RedisConfig.fromJson(r).map(Redis(_)).left.map(_.under("redis"))
            case None =>
              Left(JsonError.Decode("missing required field", Vector.empty).under("redis"))
        case other =>
          Left(
            JsonError.Decode(s"unknown flow state backend: $other", Vector.empty).under("backend")
          )
    yield backend

final case class FlowStateConfig(
    backend: FlowStateBackend = FlowStateBackend.InMemory,
    ttlSeconds: Option[Long] = None,
    flowIdSource: Option[String] = None
):
  def toJson: Json =
    val backendFields = backend.toJson.asObject.getOrElse(Vector.empty)
    Json.Obj(
      backendFields ++ Vector(
        ttlSeconds.map(t => "ttlSeconds" -> Json.Num(BigDecimal(t))),
        flowIdSource.map(s => "flowIdSource" -> Json.Str(s))
      ).flatten
    )

object FlowStateConfig:
  def fromJson(json: Json): Either[JsonError.Decode, FlowStateConfig] =
    for
      fields <- asObj(json, "flowState")
      backend <- FlowStateBackend.fromJson(json)
      ttlSeconds <- optLong(fields, "ttlSeconds")
      flowIdSource <- optString(fields, "flowIdSource")
    yield FlowStateConfig(backend, ttlSeconds, flowIdSource)

/** `_rift.scriptEngine` — the wire key is `"defaultEngine"`, not `"default"` — types.rs:1008-1017,
  * `RiftScriptEngineConfig.java:8`.
  */
final case class ScriptEngineConfig(default: ScriptEngine, timeoutMs: Option[Long] = None):
  def toJson: Json = Json.Obj(
    Vector(
      Some("defaultEngine" -> default.toJson),
      timeoutMs.map(t => "timeoutMs" -> Json.Num(BigDecimal(t)))
    ).flatten
  )

object ScriptEngineConfig:
  def fromJson(json: Json): Either[JsonError.Decode, ScriptEngineConfig] =
    for
      fields <- asObj(json, "scriptEngine")
      defaultJson <- fields
        .field("defaultEngine")
        .toRight[JsonError.Decode](
          JsonError.Decode("missing required field", Vector.empty).under("defaultEngine")
        )
      default <- ScriptEngine.fromJson(defaultJson).left.map(_.under("defaultEngine"))
      timeoutMs <- optLong(fields, "timeoutMs")
    yield ScriptEngineConfig(default, timeoutMs)

/** `_rift.metrics` — an object `{enabled, port}`, not a bare boolean — types.rs:948-961,
  * `RiftMetricsConfig.java`.
  */
final case class MetricsConfig(enabled: Boolean = false, port: Int = 9090):
  def toJson: Json =
    Json.obj("enabled" -> Json.Bool(enabled), "port" -> Json.Num(BigDecimal(port)))

object MetricsConfig:
  def fromJson(json: Json): Either[JsonError.Decode, MetricsConfig] =
    for
      fields <- asObj(json, "metrics")
      enabled <- optBool(fields, "enabled", false)
      port <- optInt(fields, "port").map(_.getOrElse(9090))
    yield MetricsConfig(enabled, port)

/** `_rift.proxy.upstream` — where an imposter-level proxy sends traffic. Defaults mirror rift-java
  * (`RiftUpstreamConfig.java:10`): `protocol` is optional and defaults to `http`; host and port are
  * required.
  */
final case class UpstreamConfig(host: String, port: Int, protocol: String = "http"):
  def toJson: Json = Json.obj(
    "host" -> Json.Str(host),
    "port" -> Json.Num(BigDecimal(port)),
    "protocol" -> Json.Str(protocol)
  )

object UpstreamConfig:
  val defaultProtocol: String = "http"

  def fromJson(json: Json): Either[JsonError.Decode, UpstreamConfig] =
    for
      fields <- asObj(json, "upstream")
      host <- reqString(fields, "host")
      port <- optInt(fields, "port").flatMap(
        _.toRight(JsonError.Decode("missing required field", Vector.empty).under("port"))
      )
      protocol <- optString(fields, "protocol").map(_.getOrElse(defaultProtocol))
    yield UpstreamConfig(host, port, protocol)

/** `_rift.proxy.connectionPool`. Defaults mirror rift-java's
  * (`RiftConnectionPoolConfig.java:9-10`).
  */
final case class ConnectionPoolConfig(
    maxIdlePerHost: Int = ConnectionPoolConfig.defaultMaxIdlePerHost,
    idleTimeoutSecs: Long = ConnectionPoolConfig.defaultIdleTimeoutSecs
):
  def toJson: Json = Json.obj(
    "maxIdlePerHost" -> Json.Num(BigDecimal(maxIdlePerHost)),
    "idleTimeoutSecs" -> Json.Num(BigDecimal(idleTimeoutSecs))
  )

object ConnectionPoolConfig:
  val defaultMaxIdlePerHost: Int = 100
  val defaultIdleTimeoutSecs: Long = 90

  def fromJson(json: Json): Either[JsonError.Decode, ConnectionPoolConfig] =
    for
      fields <- asObj(json, "connectionPool")
      maxIdle <- optInt(fields, "maxIdlePerHost").map(_.getOrElse(defaultMaxIdlePerHost))
      idleTimeout <- optLong(fields, "idleTimeoutSecs").map(_.getOrElse(defaultIdleTimeoutSecs))
    yield ConnectionPoolConfig(maxIdle, idleTimeout)

/** `_rift.proxy` — imposter-level proxy settings. Both members are optional, matching
  * `RiftProxyConfig.java:8`.
  */
final case class ProxyConfig(
    upstream: Option[UpstreamConfig] = None,
    connectionPool: Option[ConnectionPoolConfig] = None
):
  def toJson: Json = Json.Obj(
    Vector(
      upstream.map(u => "upstream" -> u.toJson),
      connectionPool.map(p => "connectionPool" -> p.toJson)
    ).flatten
  )

object ProxyConfig:
  def fromJson(json: Json): Either[JsonError.Decode, ProxyConfig] =
    for
      fields <- asObj(json, "proxy")
      upstream <- fields.field("upstream") match
        case Some(u) => UpstreamConfig.fromJson(u).map(Some(_)).left.map(_.under("upstream"))
        case None => Right(None)
      pool <- fields.field("connectionPool") match
        case Some(p) =>
          ConnectionPoolConfig.fromJson(p).map(Some(_)).left.map(_.under("connectionPool"))
        case None => Right(None)
    yield ProxyConfig(upstream, pool)

/** The imposter-level `_rift` extension block: flow-state backend, scripting, metrics, proxy. */
final case class RiftConfig(
    flowState: Option[FlowStateConfig] = None,
    scriptEngine: Option[ScriptEngineConfig] = None,
    scripts: Vector[(String, ScriptSource)] = Vector.empty,
    metrics: Option[MetricsConfig] = None,
    proxy: Option[ProxyConfig] = None
):
  def toJson: Json = Json.Obj(
    Vector(
      flowState.map(f => "flowState" -> f.toJson),
      scriptEngine.map(s => "scriptEngine" -> s.toJson),
      if scripts.nonEmpty then Some("scripts" -> Json.Obj(scripts.map((k, v) => k -> v.toJson)))
      else None,
      metrics.map(m => "metrics" -> m.toJson),
      proxy.map(p => "proxy" -> p.toJson)
    ).flatten
  )

object RiftConfig:
  def fromJson(json: Json): Either[JsonError.Decode, RiftConfig] =
    for
      fields <- asObj(json, "_rift")
      flowState <- fields.field("flowState") match
        case Some(f) => FlowStateConfig.fromJson(f).map(Some(_)).left.map(_.under("flowState"))
        case None => Right(None)
      scriptEngine <- fields.field("scriptEngine") match
        case Some(s) =>
          ScriptEngineConfig.fromJson(s).map(Some(_)).left.map(_.under("scriptEngine"))
        case None => Right(None)
      scripts <- fields.field("scripts") match
        case Some(s) =>
          asObj(s, "scripts").left.map(_.under("scripts")).flatMap { entries =>
            entries.foldLeft[Either[JsonError.Decode, Vector[(String, ScriptSource)]]](
              Right(Vector.empty)
            ) { case (acc, (name, srcJson)) =>
              for
                xs <- acc
                src <- ScriptSource.fromJson(srcJson).left.map(_.under(name).under("scripts"))
              yield xs :+ (name -> src)
            }
          }
        case None => Right(Vector.empty)
      metrics <- fields.field("metrics") match
        case Some(m) => MetricsConfig.fromJson(m).map(Some(_)).left.map(_.under("metrics"))
        case None => Right(None)
      proxy <- fields.field("proxy") match
        case Some(p) => ProxyConfig.fromJson(p).map(Some(_)).left.map(_.under("proxy"))
        case None => Right(None)
    yield RiftConfig(flowState, scriptEngine, scripts, metrics, proxy)
