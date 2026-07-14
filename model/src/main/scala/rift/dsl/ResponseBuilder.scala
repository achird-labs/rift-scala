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
    private val behaviorsValue: Behaviors = Behaviors.empty,
    private val faultValue: Option[FaultConfig] = None,
    private val scriptValue: Option[ScriptSource] = None,
    private val templatedValue: Boolean = false
) extends ResponseBuilder:

  private def withState(
      statusCodeValue: Int = this.statusCodeValue,
      headersValue: Vector[(String, String)] = this.headersValue,
      bodyValue: Option[Json] = this.bodyValue,
      binaryModeValue: Boolean = this.binaryModeValue,
      behaviorsValue: Behaviors = this.behaviorsValue,
      faultValue: Option[FaultConfig] = this.faultValue,
      scriptValue: Option[ScriptSource] = this.scriptValue,
      templatedValue: Boolean = this.templatedValue
  ): IsResponseBuilder =
    new IsResponseBuilder(
      statusCodeValue,
      headersValue,
      bodyValue,
      binaryModeValue,
      behaviorsValue,
      faultValue,
      scriptValue,
      templatedValue
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
    withState(headersValue = headersValue :+ (name -> value))

  def templated: IsResponseBuilder = withState(templatedValue = true)

  def after(duration: FiniteDuration): IsResponseBuilder =
    withState(behaviorsValue =
      behaviorsValue.copy(waitFor = Some(WaitBehavior.Fixed(duration.toMillis)))
    )

  /** A random wait in `[min, max]`, using the engine's native `{min,max}` wait rather than a
    * generated JS function — nothing to execute, and no injection support needed at serve time.
    */
  def afterBetween(min: FiniteDuration, max: FiniteDuration): IsResponseBuilder =
    // The engine's range is `u64` and it samples `min..=max`, so an inverted or negative range is
    // rejected (or panics) engine-side at serve time — catch it here, at the call site that wrote it.
    require(min.toMillis >= 0, s"afterBetween: min (${min.toMillis}ms) must not be negative")
    require(
      max >= min,
      s"afterBetween: max (${max.toMillis}ms) must not be less than min (${min.toMillis}ms)"
    )
    withState(behaviorsValue =
      behaviorsValue.copy(waitFor = Some(WaitBehavior.Range(min.toMillis, max.toMillis)))
    )

  /** A wait whose duration is computed by a JS function the engine runs — needs the engine's
    * injection support. Prefer [[after]]/[[afterBetween]] unless the delay is genuinely dynamic.
    */
  def afterInject(script: String): IsResponseBuilder =
    withState(behaviorsValue = behaviorsValue.copy(waitFor = Some(WaitBehavior.Inject(script))))

  def decorate(js: String): IsResponseBuilder =
    withState(behaviorsValue = behaviorsValue.copy(decorate = Some(js)))

  def copy(from: FieldSelector, into: String, extractWith: CopyUsing): IsResponseBuilder =
    val entry =
      Json.obj("from" -> from.locatorJson, "into" -> Json.Str(into), "using" -> extractWith.toJson)
    withState(behaviorsValue =
      behaviorsValue.copy(copyEntries = behaviorsValue.copyEntries :+ entry)
    )

  def lookup(key: LookupKey, csv: String, keyColumn: String, into: String): IsResponseBuilder =
    val entry = Json.obj(
      "key" -> Json.obj("from" -> key.fieldJson, "using" -> CopyUsing.Regex(".+").toJson),
      "fromDataSource" -> Json.obj(
        "csv" -> Json.obj("path" -> Json.Str(csv), "keyColumn" -> Json.Str(keyColumn))
      ),
      "into" -> Json.Str(into)
    )
    withState(behaviorsValue = behaviorsValue.copy(lookup = behaviorsValue.lookup :+ entry))

  def repeat(times: Int): IsResponseBuilder =
    withState(behaviorsValue = behaviorsValue.copy(repeat = Some(times)))

  def withLatencyFault(probability: Double, duration: FiniteDuration): IsResponseBuilder =
    requireProbability(probability)
    val latency = LatencyFault(probability, ms = Some(duration.toMillis))
    withState(faultValue = Some(mergeFault(faultValue, FaultConfig(latency = Some(latency)))))

  def withLatencyFault(probability: Double, range: DurationRange): IsResponseBuilder =
    requireProbability(probability)
    val latency =
      LatencyFault(probability, minMs = Some(range.min.toMillis), maxMs = Some(range.max.toMillis))
    withState(faultValue = Some(mergeFault(faultValue, FaultConfig(latency = Some(latency)))))

  def withErrorFault(probability: Double, status: Int, body: String): IsResponseBuilder =
    requireProbability(probability)
    // RiftErrorFault.body is a raw wire string (types.rs:1184-1185) — the engine writes it
    // verbatim, so it is never parsed/validated as JSON here, unlike `is`-response bodies.
    val error = ErrorFault(probability, status, Some(body))
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
      if faultValue.isDefined || scriptValue.isDefined || templatedValue then
        Some(RiftResponseExt(faultValue, scriptValue, templatedValue))
      else None
    Response.Is(buildIs, behaviorsValue, rift)

def ok: IsResponseBuilder = new IsResponseBuilder(200)
def created: IsResponseBuilder = new IsResponseBuilder(201)
def accepted: IsResponseBuilder = new IsResponseBuilder(202)
def noContent: IsResponseBuilder = new IsResponseBuilder(204)
def badRequest: IsResponseBuilder = new IsResponseBuilder(400)
def notFound: IsResponseBuilder = new IsResponseBuilder(404)
def status(code: Int): IsResponseBuilder = new IsResponseBuilder(code)
