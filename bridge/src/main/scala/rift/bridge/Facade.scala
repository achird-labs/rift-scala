package rift.bridge

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.json.{Json, JsonError}
import rift.model.{FlowId, Headers, IsResponse, RecordedRequest, Response, Stub, Times}

import io.github.etacassiopeia.rift.verify as jverify
import io.github.etacassiopeia.rift.MatchClause as JMatchClause
import io.github.etacassiopeia.rift.json.JsonValue as JJsonValue
import io.github.etacassiopeia.rift.dsl.{IsSpec as JIsSpec, RiftDsl as JRiftDsl}
import io.github.etacassiopeia.rift.RecordedRequest as JRecordedRequest
import io.github.etacassiopeia.rift.RecordedPage as JRecordedPage
import io.github.etacassiopeia.rift.model.{ImposterDefinition as JImposterDefinition, Stub as JStub}

/** Every rift-java facade call in this module goes through `run`: translate a recognised
  * `RiftException`/`VerificationException` into `RiftError` and rethrow; anything unrecognised
  * (including a `RiftError` already thrown by `FacadeDecode`) propagates unchanged, so it surfaces
  * as a defect at the caller's boundary (DESIGN.md §5.2, D3). Shared by `RiftConnector` and
  * `ImposterConnector`/its sub-handles so behavior can never diverge between them.
  */
private[bridge] object FacadeBoundary:
  def run[A](op: => A): A =
    try op
    catch case t: Throwable => throw RiftError.fromThrowable(t).getOrElse(throw t)

/** Decodes rift-java's own JSON documents (from its facade records' `toJson()`/JsonValue accessors)
  * back into the pure Scala model via the D2 raw-JSON seam, rather than a hand-written
  * field-by-field translation that could drift from the wire format. A decode failure here is real
  * response data that failed to parse — propagated as `RiftError.DecodeFailed`, never dropped.
  */
private[bridge] object FacadeDecode:

  def stub(js: JStub): Stub = decodeOrThrow(js.toJson(), Stub.fromJson)

  def imposterDefinition(jd: JImposterDefinition): ImposterDefinition =
    decodeOrThrow(jd.toJson(), ImposterDefinition.fromJson)

  def recordedRequest(jr: JRecordedRequest): RecordedRequest =
    decodeOrThrow(jr.raw().toJson(), RecordedRequest.fromJson)

  /** Batch decoders for the facade's `List`-returning reads — one place owns the Java-list →
    * decoded-Vector idiom instead of repeating `.asScala.toVector.map(...)` at every call site.
    */
  def stubs(jss: java.util.List[JStub]): Vector[Stub] = jss.asScala.toVector.map(stub)

  def recordedRequests(jrs: java.util.List[JRecordedRequest]): Vector[RecordedRequest] =
    jrs.asScala.toVector.map(recordedRequest)

  /** Cursor page decode (DESIGN.md §5.3, D6) — `nextIndex` stays an `Option[Long]` all the way
    * through rather than defaulting to a sentinel, so `RequestTail` can tell "no stable index"
    * apart from "index zero" and hold its cursor instead of resetting it.
    */
  def recordedPage(jp: JRecordedPage): RecordedPage =
    RecordedPage(
      requests = recordedRequests(jp.requests()),
      nextIndex = jp.nextIndex().toScala,
      truncated = jp.truncated()
    )

  def json(jv: JJsonValue): Json = Json.parse(jv.toJson()) match
    case Right(j) => j
    case Left(err) => throw RiftError.DecodeFailed(err.toString, None)

  private def decodeOrThrow[A](
      raw: String,
      decode: Json => Either[JsonError.Decode, A]
  ): A =
    Json.parse(raw) match
      case Left(err) => throw RiftError.DecodeFailed(err.toString, None)
      case Right(parsed) =>
        decode(parsed) match
          case Right(a) => a
          case Left(err) => throw RiftError.DecodeFailed(err.toString, Some(parsed))

/** Encodes Scala model/DSL values into the facade's JSON- or record-based inputs. */
private[bridge] object FacadeEncode:

  def json(j: Json): JJsonValue = JJsonValue.parse(j.render)

  def stub(s: Stub): JJsonValue = json(s.toJson)

  def requestMatch(matching: RequestMatch): jverify.RequestMatch =
    jverify.RequestMatch.ofJson(json(Json.Arr(matching.predicates.map(_.toJson))))

  /** Total mapping of a `TailFilter` onto the facade's narrow `MatchClause` — the two are defined
    * to mirror each other exactly (`header`/`flowId`), so this can never be lossy.
    */
  def matchClause(filter: TailFilter): JMatchClause = filter match
    case TailFilter.Header(name, value) => JMatchClause.header(name, value)
    case TailFilter.Flow(flowId) => JMatchClause.flowId(FlowId.value(flowId))

  def matchClauses(filters: Seq[TailFilter]): Array[JMatchClause] =
    filters.iterator.map(matchClause).toArray

  def times(t: Times): jverify.VerificationTimes = t match
    case Times.Exactly(n) => jverify.VerificationTimes.exactly(n)
    case Times.AtLeast(n) => jverify.VerificationTimes.atLeast(n)
    case Times.AtMost(n) => jverify.VerificationTimes.atMost(n)
    case Times.Between(lo, hi) => jverify.VerificationTimes.between(lo, hi)

  private val binaryMarker: (String, Json) = ("_mode", Json.Str("binary"))

  /** Translates a rift-scala response into the facade's `IsSpec` for an intercept `serve` rule. The
    * facade only accepts a concrete `IsSpec` (no raw-JSON overload like the stub/verify paths
    * have), so this is a deliberate value translation — but scoped to the plain `is` core (status,
    * headers, body incl. binary). A response carrying
    * `_behaviors`/`_rift`(faults/script/templating)/a non-`is` variant (proxy/inject/fault) or
    * unknown `is` keys is **rejected**, never silently degraded: the full behavior vocabulary
    * belongs to `redirectTo(imposter)`, whose stubs go through the D2 raw-JSON seam unchanged.
    */
  def isSpec(response: ResponseBuilder): JIsSpec =
    response.build match
      case Response.Is(is, behaviors, riftExt, extra)
          if behaviors.isEmpty && riftExt.isEmpty && extra.isEmpty && isPlainIsExtra(is.extra) =>
        isSpecFromIs(is)
      case _ =>
        throw RiftError.InvalidDefinition(
          "intercept serve supports a plain `is` response (status, headers, body); a response " +
            "with _behaviors/_rift/proxy/inject/fault is not translatable to the facade IsSpec — " +
            "use redirectTo(imposter) for full stub fidelity",
          None
        )

  private def isPlainIsExtra(extra: Vector[(String, Json)]): Boolean =
    extra.isEmpty || extra == Vector(binaryMarker)

  private def isSpecFromIs(is: IsResponse): JIsSpec =
    val withStatus = JRiftDsl.status(is.statusCode.getOrElse(200))
    val withHeaders =
      orderedHeaders(is.headers).foldLeft(withStatus) { case (spec, (name, values)) =>
        spec.withHeader(name, values*)
      }
    val binary = is.extra.contains(binaryMarker)
    is.body match
      case Some(Json.Str(s)) if binary =>
        withHeaders.withBinaryBody(java.util.Base64.getDecoder.decode(s))
      case Some(_) if binary =>
        // the binary marker promises a base64 string body; any other body shape would be silently
        // mis-encoded as a JSON body — reject rather than degrade (mirrors isSpec's top-level guard).
        throw RiftError.InvalidDefinition(
          "intercept serve: `_mode=binary` marker present but body is not a base64 string",
          None
        )
      case Some(Json.Str(s)) => withHeaders.withTextBody(s)
      case Some(json) => withHeaders.withJsonBody(JJsonValue.parse(json.render))
      case None => withHeaders

  /** Collapses repeated header names into one `withHeader(name, v*)` call, preserving first-seen
    * order — the facade's `withHeader` is varargs-per-name, so a flat per-entry mapping would risk
    * dropping a multi-valued header.
    */
  private def orderedHeaders(headers: Headers): Vector[(String, Vector[String])] =
    headers.entries.foldLeft(Vector.empty[(String, Vector[String])]) { case (acc, (name, value)) =>
      acc.indexWhere(_._1 == name) match
        case -1 => acc :+ (name -> Vector(value))
        case i => acc.updated(i, name -> (acc(i)._2 :+ value))
    }
