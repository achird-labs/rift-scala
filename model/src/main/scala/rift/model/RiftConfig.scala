package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** `_rift.flowState`: `ttlSeconds` and `flowIdSource`. `extra` carries every other key verbatim and
  * in order — provider-store options since engine 0.17.0 — so a decode -> encode round trip never
  * drops one.
  *
  * There is no backend selector. Engine 0.22.0 removed the Redis backend (rift#1336) and stopped
  * reading `backend` (rift#1463); rift-java 0.3.8 dropped it from `RiftFlowStateConfig` likewise. A
  * document that still writes `backend` or a `redis` block, exported from an older engine, decodes
  * with those keys on `extra` and is written back with the same keys and values, typed keys first
  * (#216).
  */
final case class FlowStateConfig(
    ttlSeconds: Option[Long] = None,
    flowIdSource: Option[String] = None,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json =
    buildObj(
      FlowStateConfig.knownKeys,
      Vector(
        ttlSeconds.map(t => "ttlSeconds" -> Json.Num(BigDecimal(t))),
        flowIdSource.map(s => "flowIdSource" -> Json.Str(s))
      ).flatten,
      extra
    )

object FlowStateConfig:
  private val knownKeys: Set[String] = Set("ttlSeconds", "flowIdSource")

  def fromJson(json: Json): Either[JsonError.Decode, FlowStateConfig] =
    for
      fields <- asObj(json, "flowState")
      ttlSeconds <- optLong(fields, "ttlSeconds")
      flowIdSource <- optString(fields, "flowIdSource")
    yield FlowStateConfig(ttlSeconds, flowIdSource, fields.remainder(knownKeys))

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

/** The imposter-level `_rift` extension block: flow state, scripting, metrics, proxy.
  *
  * `extra` carries every child the model does not know, verbatim and in order, so a decode ->
  * encode round trip never drops one. Engine 0.18.0 (rift#1152) adds `warnings` here on create and
  * on `GET /imposters`, as `config_key_ignored` entries; its shape is engine-owned, so it is kept
  * rather than typed. It is output-only: the engine recomputes it from its own analysis and ignores
  * a `warnings` key sent back on create, so re-sending a read imposter is harmless.
  */
final case class RiftConfig(
    flowState: Option[FlowStateConfig] = None,
    scriptEngine: Option[ScriptEngineConfig] = None,
    scripts: Vector[(String, ScriptSource)] = Vector.empty,
    metrics: Option[MetricsConfig] = None,
    proxy: Option[ProxyConfig] = None,
    extra: Vector[(String, Json)] = Vector.empty
):
  def toJson: Json = buildObj(
    RiftConfig.knownKeys,
    Vector(
      flowState.map(f => "flowState" -> f.toJson),
      scriptEngine.map(s => "scriptEngine" -> s.toJson),
      if scripts.nonEmpty then Some("scripts" -> Json.Obj(scripts.map((k, v) => k -> v.toJson)))
      else None,
      metrics.map(m => "metrics" -> m.toJson),
      proxy.map(p => "proxy" -> p.toJson)
    ).flatten,
    extra
  )

object RiftConfig:
  private val knownKeys = Set("flowState", "scriptEngine", "scripts", "metrics", "proxy")

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
    yield RiftConfig(flowState, scriptEngine, scripts, metrics, proxy, fields.remainder(knownKeys))
