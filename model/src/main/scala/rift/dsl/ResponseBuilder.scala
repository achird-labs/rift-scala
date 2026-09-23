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
    withState(headersValue = headersValue :+ (requireHeaderName(name) -> value))

  def templated: IsResponseBuilder = withState(templatedValue = true)

  /** Sets or merges one behavior into the `_behaviors` object this builder writes. The DSL keeps
    * one entry per key, in the order it has always written them, whatever order the chainers are
    * called in: an ordered program with a repeated key is read from raw JSON, not authored here.
    */
  private def withBehavior(key: String)(f: Option[Behavior] => Behavior): IsResponseBuilder =
    val entries = behaviorsValue.entries
    val updated = entries.indexWhere(_.key == key) match
      case -1 =>
        val rank = IsResponseBuilder.behaviorOrder.indexOf(key)
        val at = entries.indexWhere(e => IsResponseBuilder.behaviorOrder.indexOf(e.key) > rank)
        val next = f(None)
        if at < 0 then entries :+ next else entries.patch(at, Vector(next), 0)
      case i => entries.updated(i, f(Some(entries(i))))
    withState(behaviorsValue = behaviorsValue.copy(entries = updated))

  def after(duration: FiniteDuration): IsResponseBuilder =
    withBehavior("wait")(_ => Behavior.Wait(WaitBehavior.Fixed(duration.toMillis)))

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
    withBehavior("wait")(_ => Behavior.Wait(WaitBehavior.Range(min.toMillis, max.toMillis)))

  /** A wait whose duration is computed by a JS function the engine runs — needs the engine's
    * injection support. Prefer [[after]]/[[afterBetween]] unless the delay is genuinely dynamic.
    */
  def afterInject(script: String): IsResponseBuilder =
    withBehavior("wait")(_ => Behavior.Wait(WaitBehavior.Inject(script)))

  def decorate(js: String): IsResponseBuilder =
    withBehavior("decorate")(_ => Behavior.Decorate(js))

  /** `_behaviors.shellTransform`: pipe the response through shell command(s), in order. Repeated
    * calls append, matching the facade's varargs accumulation. Always emits the array wire form —
    * the bare-string spelling exists only to preserve what a decode saw.
    */
  def shellTransform(commands: String*): IsResponseBuilder =
    if commands.isEmpty then this
    else
      withBehavior("shellTransform"):
        case Some(Behavior.ShellTransform(existing, _)) =>
          Behavior.ShellTransform(existing ++ commands, bare = false)
        case _ => Behavior.ShellTransform(commands.toVector, bare = false)

  def copy(from: FieldSelector, into: String, extractWith: CopyUsing): IsResponseBuilder =
    val entry =
      Json.obj("from" -> from.locatorJson, "into" -> Json.Str(into), "using" -> extractWith.toJson)
    withBehavior("copy"):
      case Some(Behavior.Copy(existing, _)) => Behavior.Copy(existing :+ entry, bare = false)
      case _ => Behavior.Copy(Vector(entry), bare = false)

  def lookup(key: LookupKey, csv: String, keyColumn: String, into: String): IsResponseBuilder =
    val entry = Json.obj(
      "key" -> Json.obj("from" -> key.fieldJson, "using" -> CopyUsing.Regex(".+").toJson),
      "fromDataSource" -> Json.obj(
        "csv" -> Json.obj("path" -> Json.Str(csv), "keyColumn" -> Json.Str(keyColumn))
      ),
      "into" -> Json.Str(into)
    )
    withBehavior("lookup"):
      case Some(Behavior.Lookup(existing, _)) => Behavior.Lookup(existing :+ entry, bare = false)
      case _ => Behavior.Lookup(Vector(entry), bare = false)

  def repeat(times: Int): IsResponseBuilder =
    withBehavior("repeat")(_ => Behavior.Repeat(times, responseLevel = false))

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
      if faultValue.isDefined || scriptValue.isDefined || templatedValue then
        Some(RiftResponseExt(faultValue, scriptValue, templatedValue))
      else None
    Response.Is(buildIs, behaviorsValue, rift)

object IsResponseBuilder:
  /** The key order the DSL has always written `_behaviors` in. The object form runs in the engine's
    * own fixed order whatever order its keys are written, so this only keeps the output stable.
    */
  private val behaviorOrder =
    Vector("wait", "decorate", "copy", "lookup", "shellTransform", "repeat")

def ok: IsResponseBuilder = new IsResponseBuilder(200)
def created: IsResponseBuilder = new IsResponseBuilder(201)
def accepted: IsResponseBuilder = new IsResponseBuilder(202)
def noContent: IsResponseBuilder = new IsResponseBuilder(204)
def badRequest: IsResponseBuilder = new IsResponseBuilder(400)
def notFound: IsResponseBuilder = new IsResponseBuilder(404)
def status(code: Int): IsResponseBuilder = new IsResponseBuilder(code)
