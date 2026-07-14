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

  def flowIdFromHeader(name: String): FlowStateConfigBuilder =
    new FlowStateConfigBuilder(backend, ttlSecondsValue, Some(s"header:$name"))

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
    private val protocolValue: Protocol = Protocol.Http,
    private val recordRequestsFlag: Boolean = false,
    private val recordMatchesFlag: Boolean = false,
    private val stubsValue: Vector[Stub] = Vector.empty,
    private val defaultResponseValue: Option[IsResponse] = None,
    private val defaultForwardValue: Option[String] = None,
    private val allowCorsFlag: Boolean = false,
    private val strictBehaviorsFlag: Boolean = false,
    private val tlsValue: Option[TlsMaterial] = None,
    private val riftValue: Option[RiftConfig] = None,
    // carried so `imposterFromJson` round-trips unknown engine keys instead of dropping them
    private val extraValue: Vector[(String, Json)] = Vector.empty
):
  private def withState(
      portValue: Option[Port] = this.portValue,
      protocolValue: Protocol = this.protocolValue,
      recordRequestsFlag: Boolean = this.recordRequestsFlag,
      recordMatchesFlag: Boolean = this.recordMatchesFlag,
      stubsValue: Vector[Stub] = this.stubsValue,
      defaultResponseValue: Option[IsResponse] = this.defaultResponseValue,
      defaultForwardValue: Option[String] = this.defaultForwardValue,
      allowCorsFlag: Boolean = this.allowCorsFlag,
      strictBehaviorsFlag: Boolean = this.strictBehaviorsFlag,
      tlsValue: Option[TlsMaterial] = this.tlsValue,
      riftValue: Option[RiftConfig] = this.riftValue,
      extraValue: Vector[(String, Json)] = this.extraValue
  ): ImposterBuilder =
    new ImposterBuilder(
      nameValue,
      portValue,
      protocolValue,
      recordRequestsFlag,
      recordMatchesFlag,
      stubsValue,
      defaultResponseValue,
      defaultForwardValue,
      allowCorsFlag,
      strictBehaviorsFlag,
      tlsValue,
      riftValue,
      extraValue
    )

  def port(value: Int): ImposterBuilder =
    Port.from(value) match
      case Right(p) => withState(portValue = Some(p))
      case Left(msg) => throw new IllegalArgumentException(msg)

  def record: ImposterBuilder = withState(recordRequestsFlag = true)
  def recordMatches: ImposterBuilder = withState(recordMatchesFlag = true)

  def https(certPem: String, keyPem: String): ImposterBuilder =
    withState(protocolValue = Protocol.Https, tlsValue = Some(TlsMaterial(certPem, keyPem)))

  def defaultResponse(response: IsResponseBuilder): ImposterBuilder =
    withState(defaultResponseValue = Some(response.buildIs))

  def defaultForward(url: String): ImposterBuilder = withState(defaultForwardValue = Some(url))

  def allowCors: ImposterBuilder = withState(allowCorsFlag = true)
  def strictBehaviors: ImposterBuilder = withState(strictBehaviorsFlag = true)

  private def rift: RiftConfig = riftValue.getOrElse(RiftConfig())

  def flowState(config: FlowStateConfigBuilder): ImposterBuilder =
    withState(riftValue = Some(rift.copy(flowState = Some(config.build))))

  def scriptEngine(engine: ScriptEngine, timeout: FiniteDuration): ImposterBuilder =
    withState(riftValue =
      Some(rift.copy(scriptEngine = Some(ScriptEngineConfig(engine, Some(timeout.toMillis)))))
    )

  def script(name: String, source: ScriptSource): ImposterBuilder =
    withState(riftValue = Some(rift.copy(scripts = rift.scripts :+ (name -> source))))

  /** The imposter-level `_rift.proxy` block — where this imposter proxies to, and how it pools
    * those connections. Distinct from a per-stub `proxyTo(...)` response.
    *
    * Overloaded rather than defaulted so an omitted `connectionPool` stays absent on the wire: the
    * engine supplies its own defaults, and emitting `{"maxIdlePerHost":100,...}` the author never
    * wrote would be a gratuitous divergence.
    */
  def proxyConfig(upstream: UpstreamConfig): ImposterBuilder =
    withState(riftValue = Some(rift.copy(proxy = Some(ProxyConfig(Some(upstream), None)))))

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
      recordRequests = recordRequestsFlag,
      recordMatches = recordMatchesFlag,
      stubs = stubsValue,
      defaultResponse = defaultResponseValue,
      defaultForward = defaultForwardValue,
      allowCors = allowCorsFlag,
      strictBehaviors = strictBehaviorsFlag,
      tls = tlsValue,
      rift = riftValue,
      extra = extraValue
    )

def imposter(name: String): ImposterBuilder = new ImposterBuilder(Some(name))

private def fromDefinition(definition: ImposterDefinition): ImposterBuilder =
  new ImposterBuilder(
    nameValue = definition.name,
    portValue = definition.port,
    protocolValue = definition.protocol,
    recordRequestsFlag = definition.recordRequests,
    recordMatchesFlag = definition.recordMatches,
    stubsValue = definition.stubs,
    defaultResponseValue = definition.defaultResponse,
    defaultForwardValue = definition.defaultForward,
    allowCorsFlag = definition.allowCors,
    strictBehaviorsFlag = definition.strictBehaviors,
    tlsValue = definition.tls,
    riftValue = definition.rift,
    extraValue = definition.extra
  )

def imposterFromJson(raw: String): Either[JsonError, ImposterBuilder] =
  for
    json <- Json.parse(raw)
    definition <- ImposterDefinition.fromJson(json)
  yield fromDefinition(definition)
