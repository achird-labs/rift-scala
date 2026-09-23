package rift.dsl

import rift.json.Json
import rift.model.*

import scala.concurrent.duration.FiniteDuration

/** Anything `.reply(...)`/`.thenReply(...)` accepts and that builds to a wire `Response` (DESIGN.md
  * §5.1.3) — an is-response, a proxy, a fault, an inject script, or a rift script.
  */
trait ResponseBuilder:
  def build: Response

private[dsl] def requireProbability(p: Double): Double =
  require(p >= 0.0 && p <= 1.0, s"probability must be within 0.0..1.0, got $p")
  p

private val tcharPunctuation = "!#$%&'*+-.^_`|~"

/** The RFC 9110 §5.6.2 field-name grammar (`1*tchar`). A name outside it is not a header the engine
  * can write faithfully, and it silently defeats every case-insensitive comparison downstream —
  * `equalsIgnoreCase` strips nothing, so `" Content-Type"` reaches the wire *beside* the serve
  * path's injected Content-Type default and past the repeated-name guard, neither of which can
  * equate it with `Content-Type`.
  *
  * Rejected at the call site that wrote it rather than trimmed: trimming would silently answer with
  * a header the caller did not ask for. Decoding stays permissive on purpose — `Headers.fromJson`
  * reproduces whatever a recorded fixture or an engine payload actually carried, which is a
  * different contract from authoring one here.
  */
private[dsl] def requireHeaderName(name: String): String =
  def isTchar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      tcharPunctuation.contains(c)
  require(name.nonEmpty, "header name must not be empty — expected an RFC 9110 token")
  val offender = name.indexWhere(!isTchar(_))
  // Named by code point, not merely quoted: a non-breaking space or a stray tab renders
  // indistinguishably from a legal name, so quoting alone leaves the caller staring at input that
  // looks correct. `require`'s message is by-name, so `name(offender)` is unreachable when valid.
  require(
    offender < 0,
    s"header name '$name' is not an RFC 9110 token: expected ASCII letters, digits or " +
      f"$tcharPunctuation, but found U+${name(offender).toInt}%04X at index $offender"
  )
  name

private[dsl] def parseJsonOrThrow(raw: String): Json =
  Json.parse(raw).fold(e => throw new IllegalArgumentException(e.toString), identity)

/** Builder for a Mountebank `is` response — status code, headers, body, `_behaviors`, and the
  * `_rift` extensions (faults, templating, embedded script).
  */
final class IsResponseBuilder private[dsl] (
    private val statusCodeValue: Int,
    private val headersValue: Vector[(String, String)] = Vector.empty,
    private val bodyValue: Option[Json] = None,
    private val binaryModeValue: Boolean = false,
    protected val behaviorsValue: Behaviors = Behaviors.empty,
    private val faultValue: Option[FaultConfig] = None,
    private val scriptValue: Option[ScriptSource] = None,
    private val templatedValue: Boolean = false,
    private val stateOpsValue: Vector[StateOp] = Vector.empty
) extends ResponseBuilder
    with BehaviorChain[IsResponseBuilder]:

  private def withState(
      statusCodeValue: Int = this.statusCodeValue,
      headersValue: Vector[(String, String)] = this.headersValue,
      bodyValue: Option[Json] = this.bodyValue,
      binaryModeValue: Boolean = this.binaryModeValue,
      behaviorsValue: Behaviors = this.behaviorsValue,
      faultValue: Option[FaultConfig] = this.faultValue,
      scriptValue: Option[ScriptSource] = this.scriptValue,
      templatedValue: Boolean = this.templatedValue,
      stateOpsValue: Vector[StateOp] = this.stateOpsValue
  ): IsResponseBuilder =
    new IsResponseBuilder(
      statusCodeValue,
      headersValue,
      bodyValue,
      binaryModeValue,
      behaviorsValue,
      faultValue,
      scriptValue,
      templatedValue,
      stateOpsValue
    )

  def json(raw: String): IsResponseBuilder = withState(bodyValue = Some(parseJsonOrThrow(raw)))

  def json[A](a: A)(using jb: JsonBody[A]): IsResponseBuilder =
    withState(bodyValue = Some(jb.encode(a)))

  def text(s: String): IsResponseBuilder = withState(bodyValue = Some(Json.Str(s)))

  def binary(bytes: Array[Byte]): IsResponseBuilder =
    withState(
      bodyValue = Some(Json.Str(java.util.Base64.getEncoder.encodeToString(bytes))),
      binaryModeValue = true
    )

  def header(name: String, value: String): IsResponseBuilder =
    withState(headersValue = headersValue :+ (requireHeaderName(name) -> value))

  def templated: IsResponseBuilder = withState(templatedValue = true)

  /** After this response is sent, stores `value` under `key` in the request's flow state
    * (`_rift.stateOps`, engine ≥ 0.18.0; an older engine drops the block, and rift-java refuses the
    * imposter there). `value` is a template: `{{ request.* }}`, `{{ state.* }}` and `{{
    * previousValue }}` render. Ops run in the order the calls were made, and an imposter with state
    * ops but no `flowState` gets an in-memory store.
    */
  def setState(key: String, value: String): IsResponseBuilder =
    withStateOp(StateOp.Set(key, value))

  /** After this response, adds `by` (which may be negative) to the integer under `key`; see
    * [[setState]].
    */
  def incrementState(key: String, by: Long = 1L): IsResponseBuilder =
    withStateOp(StateOp.Increment(key, Some(by)))

  /** After this response, removes `key` from the request's flow state; see [[setState]]. */
  def deleteState(key: String): IsResponseBuilder = withStateOp(StateOp.Delete(key))

  /** After this response, removes every key of the request's flow; see [[setState]]. */
  def clearFlowState: IsResponseBuilder = withStateOp(StateOp.ClearFlow)

  private def withStateOp(op: StateOp): IsResponseBuilder =
    withState(stateOpsValue = stateOpsValue :+ op)

  protected def withBehaviors(behaviors: Behaviors): IsResponseBuilder =
    withState(behaviorsValue = behaviors)

  def withLatencyFault(probability: Double, duration: FiniteDuration): IsResponseBuilder =
    requireProbability(probability)
    val latency = LatencyFault(probability, ms = Some(duration.toMillis))
    withState(faultValue = Some(mergeFault(faultValue, FaultConfig(latency = Some(latency)))))

  def withLatencyFault(probability: Double, range: DurationRange): IsResponseBuilder =
    requireProbability(probability)
    val latency =
      LatencyFault(probability, minMs = Some(range.min.toMillis), maxMs = Some(range.max.toMillis))
    withState(faultValue = Some(mergeFault(faultValue, FaultConfig(latency = Some(latency)))))

  def withErrorFault(
      probability: Double,
      status: Int,
      body: String,
      headers: Map[String, String] = Map.empty
  ): IsResponseBuilder =
    requireProbability(probability)
    headers.keys.foreach(requireHeaderName)
    // RiftErrorFault.body is a raw wire string (types.rs:1184-1185) — the engine writes it
    // verbatim, so it is never parsed/validated as JSON here, unlike `is`-response bodies.
    val error = ErrorFault(probability, status, Some(body), Headers(headers))
    withState(faultValue = Some(mergeFault(faultValue, FaultConfig(error = Some(error)))))

  def withTcpFault(kind: TcpFaultKind): IsResponseBuilder =
    withState(faultValue =
      Some(mergeFault(faultValue, FaultConfig(tcp = Some(TcpFault(kind, None)))))
    )

  def withTcpFault(probability: Double, kind: TcpFaultKind): IsResponseBuilder =
    requireProbability(probability)
    withState(faultValue =
      Some(mergeFault(faultValue, FaultConfig(tcp = Some(TcpFault(kind, Some(probability))))))
    )

  private def mergeFault(existing: Option[FaultConfig], added: FaultConfig): FaultConfig =
    val base = existing.getOrElse(FaultConfig())
    base.copy(
      latency = added.latency.orElse(base.latency),
      error = added.error.orElse(base.error),
      tcp = added.tcp.orElse(base.tcp)
    )

  private def extra: Vector[(String, Json)] =
    if binaryModeValue then Vector("_mode" -> Json.Str("binary")) else Vector.empty

  def buildIs: IsResponse =
    IsResponse(Some(statusCodeValue), Headers(headersValue), bodyValue, extra)

  def build: Response =
    val rift =
      if faultValue.isDefined || scriptValue.isDefined || templatedValue || stateOpsValue.nonEmpty
      then Some(RiftResponseExt(faultValue, scriptValue, templatedValue, stateOpsValue))
      else None
    Response.Is(buildIs, behaviorsValue, rift)

def ok: IsResponseBuilder = new IsResponseBuilder(200)
def created: IsResponseBuilder = new IsResponseBuilder(201)
def accepted: IsResponseBuilder = new IsResponseBuilder(202)
def noContent: IsResponseBuilder = new IsResponseBuilder(204)
def badRequest: IsResponseBuilder = new IsResponseBuilder(400)
def notFound: IsResponseBuilder = new IsResponseBuilder(404)
def status(code: Int): IsResponseBuilder = new IsResponseBuilder(code)
