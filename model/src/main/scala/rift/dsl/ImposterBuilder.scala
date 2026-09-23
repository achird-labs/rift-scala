package rift.dsl

import rift.json.{Json, JsonError}
import rift.model.*

import scala.concurrent.duration.FiniteDuration

/** A named flow-state backend for an imposter's `_rift.flowState` block —
  * `inMemoryFlowState.ttl(5.minutes)` / `redisFlowState(url).ttl(...)`.
  */
final class FlowStateConfigBuilder private[dsl] (
    private val backend: FlowStateBackend,
    private val ttlSecondsValue: Option[Long] = None,
    private val flowIdSourceValue: Option[String] = None
):
  def ttl(duration: FiniteDuration): FlowStateConfigBuilder =
    new FlowStateConfigBuilder(backend, Some(duration.toSeconds), flowIdSourceValue)

  /** Partition flow state by the value of the inbound request header `name`.
    *
    * `name` must be an RFC 9110 token, and is rejected at construction otherwise. Unlike the
    * predicate-side `header(...)` selector, a bad name here is not merely a match that fails: the
    * engine strips `header:` off this config string and compares the rest verbatim,
    * case-insensitively, against inbound headers. A name outside the token grammar, such as one
    * holding a colon or whitespace or an empty one, can never match. Every request then silently
    * falls back to the imposter-port flow id, and correlated isolation collapses into one flow
    * shared by every space.
    */
  def flowIdFromHeader(name: String): FlowStateConfigBuilder =
    new FlowStateConfigBuilder(backend, ttlSecondsValue, Some(s"header:${requireHeaderName(name)}"))

  private[dsl] def build: FlowStateConfig =
    FlowStateConfig(backend, ttlSecondsValue, flowIdSourceValue)

/** Where an imposter-level proxy sends traffic — the `_rift.proxy.upstream` block. */
def upstream(
    host: String,
    port: Int,
    protocol: String = UpstreamConfig.defaultProtocol
): UpstreamConfig = UpstreamConfig(host, port, protocol)

/** Connection pooling for an imposter-level proxy — the `_rift.proxy.connectionPool` block. Omitted
  * values keep the engine's own defaults.
  */
def connectionPool(
    maxIdlePerHost: Int = ConnectionPoolConfig.defaultMaxIdlePerHost,
    idleTimeoutSecs: Long = ConnectionPoolConfig.defaultIdleTimeoutSecs
): ConnectionPoolConfig = ConnectionPoolConfig(maxIdlePerHost, idleTimeoutSecs)

def inMemoryFlowState: FlowStateConfigBuilder = new FlowStateConfigBuilder(
  FlowStateBackend.InMemory
)
def redisFlowState(url: String): FlowStateConfigBuilder = new FlowStateConfigBuilder(
  FlowStateBackend.Redis(RedisConfig(url))
)

/** The imposter builder (DESIGN.md §5.1.3): port, protocol, stubs, and the `_rift` extension block.
  * Immutable — every step returns a new value.
  */
final class ImposterBuilder private[dsl] (
    private val nameValue: Option[String],
    private val portValue: Option[Port] = None,
    private val hostValue: Option[String] = None,
    private val protocolValue: Protocol = Protocol.Http,
    private val serviceNameValue: Option[String] = None,
    private val serviceInfoValue: Option[Json] = None,
    private val recordRequestsFlag: Boolean = false,
    private val recordMatchesFlag: Boolean = false,
    private val enabledFlag: Boolean = true,
    private val stubsValue: Vector[Stub] = Vector.empty,
    private val defaultResponseValue: Option[IsResponse] = None,
    private val defaultForwardValue: Option[String] = None,
    private val allowCorsFlag: Boolean = false,
    private val strictBehaviorsFlag: Boolean = false,
    private val tlsValue: Option[TlsMaterial] = None,
    private val clientAuthValue: ClientAuth = ClientAuth.none,
    private val riftValue: Option[RiftConfig] = None,
    // carried so `imposterFromJson` round-trips unknown engine keys instead of dropping them
    private val extraValue: Vector[(String, Json)] = Vector.empty
):
  private def withState(
      portValue: Option[Port] = this.portValue,
      hostValue: Option[String] = this.hostValue,
      protocolValue: Protocol = this.protocolValue,
      serviceNameValue: Option[String] = this.serviceNameValue,
      serviceInfoValue: Option[Json] = this.serviceInfoValue,
      recordRequestsFlag: Boolean = this.recordRequestsFlag,
      recordMatchesFlag: Boolean = this.recordMatchesFlag,
      enabledFlag: Boolean = this.enabledFlag,
      stubsValue: Vector[Stub] = this.stubsValue,
      defaultResponseValue: Option[IsResponse] = this.defaultResponseValue,
      defaultForwardValue: Option[String] = this.defaultForwardValue,
      allowCorsFlag: Boolean = this.allowCorsFlag,
      strictBehaviorsFlag: Boolean = this.strictBehaviorsFlag,
      tlsValue: Option[TlsMaterial] = this.tlsValue,
      clientAuthValue: ClientAuth = this.clientAuthValue,
      riftValue: Option[RiftConfig] = this.riftValue,
      extraValue: Vector[(String, Json)] = this.extraValue
  ): ImposterBuilder =
    // Named, not positional: the constructor carries five `Boolean`s, three of them adjacent, so a
    // positional call silently swaps flags the moment one is inserted.
    new ImposterBuilder(
      nameValue = nameValue,
      portValue = portValue,
      hostValue = hostValue,
      protocolValue = protocolValue,
      serviceNameValue = serviceNameValue,
      serviceInfoValue = serviceInfoValue,
      recordRequestsFlag = recordRequestsFlag,
      recordMatchesFlag = recordMatchesFlag,
      enabledFlag = enabledFlag,
      stubsValue = stubsValue,
      defaultResponseValue = defaultResponseValue,
      defaultForwardValue = defaultForwardValue,
      allowCorsFlag = allowCorsFlag,
      strictBehaviorsFlag = strictBehaviorsFlag,
      tlsValue = tlsValue,
      clientAuthValue = clientAuthValue,
      riftValue = riftValue,
      extraValue = extraValue
    )

  /** `0` requests an engine-assigned ephemeral port (an absent `Port`, the same convention as
    * `InterceptConfig.port`); `1..65535` binds that port; anything else throws. `Port` itself stays
    * strict — 0 is not a valid `Port`, it is the "let the engine choose" sentinel at the builder.
    */
  def port(value: Int): ImposterBuilder =
    if value == 0 then withState(portValue = None)
    else
      Port.from(value) match
        case Right(p) => withState(portValue = Some(p))
        case Left(msg) => throw new IllegalArgumentException(msg)

  def record: ImposterBuilder = withState(recordRequestsFlag = true)

  /** Sets `recordMatches: true`, which no engine acts on: it does not record which stub matched.
    *
    * The engine parses the key and does not act on it. Engine 0.18.0 and later report it as a
    * `config_key_ignored` entry in the imposter's `_rift.warnings` and log a WARN when the imposter
    * loads; the imposter is still created. There is no capability flag for this, so check the
    * engine version. The key itself stays in the model, so `imposterFromJson` keeps reading and
    * writing it unchanged.
    */
  @deprecated(
    "has no effect on the engine; use `record` (recordRequests) and read the imposter's recorded requests instead",
    "0.1.5"
  )
  def recordMatches: ImposterBuilder = withState(recordMatchesFlag = true)

  def https(certPem: String, keyPem: String): ImposterBuilder =
    withState(protocolValue = Protocol.Https, tlsValue = Some(TlsMaterial(certPem, keyPem)))

  /** Requires every client of this HTTPS imposter to present a certificate, without validating its
    * chain (`mutualAuth`). A client with none is refused at the handshake. Use the overload taking
    * CA PEMs to also check who issued it.
    *
    * Needs engine ≥ 0.18.0: an older one drops the setting and accepts every client, so rift-java
    * refuses the imposter there. The imposter must be `https`, so chain [[https]]: the engine
    * refuses `mutualAuth` on http with a 400 at create. This is not checked here, because
    * `imposterFromJson` builds through the same path and must reproduce whatever the engine wrote.
    * A later call replaces an earlier one.
    */
  def requireClientCertificate: ImposterBuilder =
    withState(clientAuthValue = ClientAuth(mutualAuth = Some(true)))

  /** Requires every client to present a certificate chaining to one of the given PEM trust anchors
    * (`mutualAuth`, `rejectUnauthorized` and `ca`); any other client is refused at the handshake.
    * One anchor is written as a string and several as an array. Same engine and protocol
    * requirements as the no-argument overload. The engine's replayable export includes the anchors,
    * as it does the imposter's own certificate.
    */
  def requireClientCertificate(trustedCaPem: String, moreCaPems: String*): ImposterBuilder =
    val pems = trustedCaPem +: moreCaPems.toVector
    pems.foreach(pem =>
      require(
        pem.contains("-----BEGIN CERTIFICATE-----"),
        "a trusted CA PEM contains no certificate: expected a -----BEGIN CERTIFICATE----- block"
      )
    )
    val ca =
      if pems.size == 1 then CaCertificates.Single(trustedCaPem) else CaCertificates.Many(pems)
    withState(clientAuthValue = ClientAuth(Some(true), Some(true), Some(ca)))

  def defaultResponse(response: IsResponseBuilder): ImposterBuilder =
    withState(defaultResponseValue = Some(response.buildIs))

  def defaultForward(url: String): ImposterBuilder = withState(defaultForwardValue = Some(url))

  /** Provision the imposter paused (rift#818): it is created but serves nothing until the engine's
    * runtime toggle enables it.
    */
  def disabled: ImposterBuilder = withState(enabledFlag = false)

  /** Un-pause a builder that arrived paused. Unlike the flags that default to `false`, the
    * constructor default cannot express this one — `imposterFromJson` on a paused document carries
    * `enabled = false` in, and without this there is no way back out.
    */
  def enabled: ImposterBuilder = withState(enabledFlag = true)

  def allowCors: ImposterBuilder = withState(allowCorsFlag = true)
  def strictBehaviors: ImposterBuilder = withState(strictBehaviorsFlag = true)

  /** Bind the imposter to a specific interface. The engine binds `0.0.0.0` by default, so this
    * narrows rather than widens — e.g. `host("127.0.0.1")` for loopback-only.
    */
  def host(interface: String): ImposterBuilder = withState(hostValue = Some(interface))

  def serviceName(name: String): ImposterBuilder = withState(serviceNameValue = Some(name))

  /** Service-identity metadata. Raw `Json` because the engine stores it verbatim. */
  def serviceInfo(info: Json): ImposterBuilder = withState(serviceInfoValue = Some(info))

  private def rift: RiftConfig = riftValue.getOrElse(RiftConfig())

  def flowState(config: FlowStateConfigBuilder): ImposterBuilder =
    withState(riftValue = Some(rift.copy(flowState = Some(config.build))))

  /** The imposter's `_rift.scriptEngine`: the default engine and per-invocation timeout.
    *
    * The default engine (`defaultEngine`) is honoured by rift engine 0.18.0 and later. Engine
    * 0.17.0 and earlier parse it and ignore it, running a script that names no engine as Rhai. On
    * an engine that honours it, a script's engine is resolved in this order: the script's own
    * `engine`, then its `file` extension (`.rhai` or `.js`), then this default, then Rhai. A
    * `Script.ref` takes the engine of the script it names.
    *
    * The `Script` factories always name their engine, so this default only decides scripts that
    * reach the engine without one, such as raw-JSON `_rift.script` or `_rift.scripts` entries read
    * through `imposterFromJson`. There is no capability flag for this behaviour: check the engine
    * version (`rift.bridge.RiftVersions.engine`).
    */
  def scriptEngine(engine: ScriptEngine, timeout: FiniteDuration): ImposterBuilder =
    withState(riftValue =
      Some(rift.copy(scriptEngine = Some(ScriptEngineConfig(engine, Some(timeout.toMillis)))))
    )

  def script(name: String, source: ScriptSource): ImposterBuilder =
    withState(riftValue = Some(rift.copy(scripts = rift.scripts :+ (name -> source))))

  /** Sets the `_rift.metrics` block, which no engine acts on: it exposes no metrics endpoint.
    *
    * The engine parses the key and does not act on it. Engine 0.18.0 and later report it as a
    * `config_key_ignored` entry in the imposter's `_rift.warnings` and log a WARN when the imposter
    * loads; the imposter is still created. There is no capability flag for this, so check the
    * engine version. The key itself stays in the model, so `imposterFromJson` keeps reading and
    * writing it unchanged.
    */
  @deprecated(
    "has no effect on the engine; configure metrics on the engine process with --metrics-port",
    "0.1.5"
  )
  def metrics(port: Int): ImposterBuilder =
    withState(riftValue =
      Some(rift.copy(metrics = Some(MetricsConfig(enabled = true, port = port))))
    )

  /** Sets the imposter-level `_rift.proxy` block, which no engine acts on: the imposter does not
    * proxy to `upstream` and pools nothing. A per-stub `proxyTo(...)` response is what proxies.
    *
    * The engine parses the key and does not act on it. Engine 0.18.0 and later report it as a
    * `config_key_ignored` entry in the imposter's `_rift.warnings` and log a WARN when the imposter
    * loads; the imposter is still created. There is no capability flag for this, so check the
    * engine version. The key itself stays in the model, so `imposterFromJson` keeps reading and
    * writing it unchanged.
    *
    * Overloaded rather than defaulted so an omitted `connectionPool` stays absent on the wire.
    */
  @deprecated(
    "has no effect on the engine; there is no replacement block, use a per-stub proxyTo(...) response",
    "0.1.5"
  )
  def proxyConfig(upstream: UpstreamConfig): ImposterBuilder =
    withState(riftValue = Some(rift.copy(proxy = Some(ProxyConfig(Some(upstream), None)))))

  /** As the one-argument overload, with a `_rift.proxy.connectionPool` that no engine acts on. */
  @deprecated(
    "has no effect on the engine; there is no replacement block, use a per-stub proxyTo(...) response",
    "0.1.5"
  )
  def proxyConfig(upstream: UpstreamConfig, connectionPool: ConnectionPoolConfig): ImposterBuilder =
    withState(riftValue =
      Some(rift.copy(proxy = Some(ProxyConfig(Some(upstream), Some(connectionPool)))))
    )

  def stub(s: StubBuilder[StubPhase.Complete]): ImposterBuilder =
    withState(stubsValue = stubsValue :+ s.build)

  def stubs(ss: Vector[Stub]): ImposterBuilder = withState(stubsValue = stubsValue ++ ss)

  def build: ImposterDefinition =
    ImposterDefinition(
      port = portValue,
      protocol = protocolValue,
      name = nameValue,
      host = hostValue,
      serviceName = serviceNameValue,
      serviceInfo = serviceInfoValue,
      recordRequests = recordRequestsFlag,
      recordMatches = recordMatchesFlag,
      enabled = enabledFlag,
      stubs = stubsValue,
      defaultResponse = defaultResponseValue,
      defaultForward = defaultForwardValue,
      allowCors = allowCorsFlag,
      strictBehaviors = strictBehaviorsFlag,
      tls = tlsValue,
      clientAuth = clientAuthValue,
      rift = riftValue,
      extra = extraValue
    )

def imposter(name: String): ImposterBuilder = new ImposterBuilder(Some(name))

private def fromDefinition(definition: ImposterDefinition): ImposterBuilder =
  new ImposterBuilder(
    nameValue = definition.name,
    hostValue = definition.host,
    serviceNameValue = definition.serviceName,
    serviceInfoValue = definition.serviceInfo,
    portValue = definition.port,
    protocolValue = definition.protocol,
    recordRequestsFlag = definition.recordRequests,
    recordMatchesFlag = definition.recordMatches,
    enabledFlag = definition.enabled,
    stubsValue = definition.stubs,
    defaultResponseValue = definition.defaultResponse,
    defaultForwardValue = definition.defaultForward,
    allowCorsFlag = definition.allowCors,
    strictBehaviorsFlag = definition.strictBehaviors,
    tlsValue = definition.tls,
    clientAuthValue = definition.clientAuth,
    riftValue = definition.rift,
    extraValue = definition.extra
  )

def imposterFromJson(raw: String): Either[JsonError, ImposterBuilder] =
  for
    json <- Json.parse(raw)
    definition <- ImposterDefinition.fromJson(json)
  yield fromDefinition(definition)
