package rift.bridge

import java.time.Duration as JDuration

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.json.{Json, JsonError}
import rift.model.{
  Behaviors,
  ClosestMiss,
  ErrorFault,
  FailedPredicate,
  FaultConfig,
  FlowId,
  Headers,
  IsResponse,
  LatencyFault,
  Port,
  Predicate,
  RecordedRequest,
  Response,
  RiftResponseExt,
  Stub,
  TcpFaultKind,
  Times,
  VerificationResult,
  VerifyDetail,
  WaitBehavior
}

import io.github.achirdlabs.rift.verify as jverify
import io.github.achirdlabs.rift.MatchClause as JMatchClause
import io.github.achirdlabs.rift.json.JsonValue as JJsonValue
import io.github.achirdlabs.rift.dsl.{Fault as JFault, IsSpec as JIsSpec, RiftDsl as JRiftDsl}
import io.github.achirdlabs.rift.RecordedRequest as JRecordedRequest
import io.github.achirdlabs.rift.RecordedPage as JRecordedPage
import io.github.achirdlabs.rift.RiftEvent as JRiftEvent
import io.github.achirdlabs.rift.RiftEvent.ImposterChanged.Action as JImposterAction
import io.github.achirdlabs.rift.model.{ImposterDefinition as JImposterDefinition, Stub as JStub}

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

  /** Total mapping of the facade's `RiftEvent` ADT (D1/D2, issue #87). The embedded request in
    * `RequestRecorded` goes through the same D2 raw-JSON seam as every other recorded request
    * (`recordedRequest` above); everything else is scalar field translation, since the envelopes
    * expose no `toJson()` to cross a raw-JSON seam with. The `case _` default is unreachable under
    * the pinned jar (`RiftEvent` is JVM-sealed to exactly the four cases matched above) but keeps
    * this total against a newer `rift-java-core` minor that adds a subtype — translated to
    * `Unknown` rather than crashing the stream.
    */
  def riftEvent(j: JRiftEvent): RiftEvent = j match
    case h: JRiftEvent.Hello =>
      RiftEvent.Hello(
        engineVersion = h.engineVersion(),
        seqAtConnect = h.seqAtConnect(),
        types = h.types().asScala.toVector,
        port = h.port().toScala.map(portFrom)
      )
    case ic: JRiftEvent.ImposterChanged =>
      RiftEvent.ImposterChanged(
        seq = ic.seq().toScala,
        action = imposterAction(ic.action()),
        port = ic.port().toScala.map(portFrom)
      )
    case rr: JRiftEvent.RequestRecorded =>
      RiftEvent.RequestRecorded(
        seq = rr.seq().toScala,
        port = portFrom(rr.port()),
        index = rr.index().toScala,
        flowId = rr.flowId().toScala.map(flowIdFrom),
        request = recordedRequest(rr.request())
      )
    case lg: JRiftEvent.Lagged => RiftEvent.Lagged(lg.missed())
    case other => RiftEvent.Unknown(s"${other.getClass.getSimpleName}: $other", other.seq().toScala)

  /** An out-of-range port is real response data that failed to parse — `DecodeFailed`, not dropped
    * (mirrors `decodeOrThrow` below; unlike `ImposterConnector.port`, this has no
    * `CommunicationError` precedent to follow since these fields aren't sourced from a facade
    * exception).
    */
  private def portFrom(value: Int): Port =
    Port
      .from(value)
      .getOrElse(
        throw RiftError.DecodeFailed(s"engine reported an out-of-range port: $value", None)
      )

  private def flowIdFrom(value: String): FlowId =
    FlowId
      .from(value)
      .getOrElse(throw RiftError.DecodeFailed(s"engine reported an invalid flow id: $value", None))

  private def imposterAction(a: JImposterAction): ImposterAction = a match
    case JImposterAction.CREATED => ImposterAction.Created
    case JImposterAction.REPLACED => ImposterAction.Replaced
    case JImposterAction.STUBS_CHANGED => ImposterAction.StubsChanged
    case JImposterAction.DELETED => ImposterAction.Deleted
    case JImposterAction.ALL_DELETED => ImposterAction.AllDeleted

  /** `verifyResult`'s non-throwing counterpart to `translateReport` (`RiftError.scala`) — but
    * unlike that best-effort, drop-on-decode-failure rendering, this is real response data: any
    * decode failure inside `requests`/`closest` propagates as `RiftError.DecodeFailed` via
    * `decodeOrThrow`/`json`, never a silently partial `VerificationResult`.
    */
  def verificationResult(jr: jverify.VerificationResult): VerificationResult =
    VerificationResult(
      matched = jr.matched(),
      total = jr.total(),
      satisfied = jr.satisfied(),
      requests = recordedRequests(jr.requests()),
      closest = jr.closest().toScala.map(closestMiss)
    )

  private def closestMiss(jc: jverify.ClosestMiss): ClosestMiss =
    ClosestMiss(
      request = recordedRequest(jc.request()),
      failedPredicates = jc.failedPredicates().asScala.toVector.map { fp =>
        FailedPredicate(
          predicate = decodeOrThrow(fp.predicate().toJson(), Predicate.fromJson),
          actual = json(fp.actual())
        )
      }
    )

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

  /** The facade `forward` target for a typed port — the bare port, since that is all the wire
    * carries.
    *
    * `InterceptRuleBuilder.forward(hostPort)` calls `InterceptImpl.parsePort` and hands the int to
    * `addForwardRule`; the engine's forward action is `ForwardTarget { port: u16 }` and it proxies
    * to `http://127.0.0.1:{port}`. A host component would be parsed and discarded, so rendering one
    * would only imply a routing choice the engine cannot make.
    */
  def forwardTarget(port: Port): String = Port.value(port).toString

  /** Total mapping of a `TailFilter` onto the facade's `MatchClause` — the two enums mirror each
    * other case for case (`header`/`flowId`/`method`/`path`), so this can never be lossy.
    *
    * The facade's `method`/`path` factories validate their argument and throw
    * `IllegalArgumentException`, which is not a `RiftException` and so would escape
    * `FacadeBoundary` as a defect. Translated here to the typed error instead, since a malformed
    * filter is a caller mistake, not a bug.
    */
  def matchClause(filter: TailFilter): JMatchClause =
    try
      filter match
        case TailFilter.Header(name, value) => JMatchClause.header(name, value)
        case TailFilter.Flow(flowId) => JMatchClause.flowId(FlowId.value(flowId))
        case TailFilter.Method(value) => JMatchClause.method(value)
        case TailFilter.Path(value) => JMatchClause.path(value)
    catch
      case e: IllegalArgumentException =>
        throw RiftError.InvalidDefinition(s"invalid tail filter: ${e.getMessage}", None)

  def matchClauses(filters: Seq[TailFilter]): Array[JMatchClause] =
    filters.iterator.map(matchClause).toArray

  /** `matchClauses` for a *space-scoped* read (#129).
    *
    * `SpaceImpl` prepends the space's own `flowId` clause to every read and then rejects a second
    * one — clauses AND together, so a caller-supplied `TailFilter.Flow` either duplicates the scope
    * or selects nothing. It raises `IllegalArgumentException` while the argument is evaluated, on
    * *every* transport, including the HTTP-backed ones the space tail otherwise works on.
    *
    * Caught here so it lands as a typed `InvalidDefinition` instead of escaping as a defect — the
    * same treatment `matchClause` above already gives an unusable clause.
    */
  def spaceMatchClauses(filters: Seq[TailFilter]): Array[JMatchClause] =
    if filters.exists { case TailFilter.Flow(_) => true; case _ => false } then
      throw RiftError.InvalidDefinition(
        "a space read is already scoped to its own flow, so TailFilter.Flow cannot be used here — " +
          "the clauses would AND together and select nothing. Drop it, or page the imposter-level " +
          "tail (ImposterConnector.recordedPage) to filter by flow.",
        None
      )
    else matchClauses(filters)

  def times(t: Times): jverify.VerificationTimes = t match
    case Times.Exactly(n) => jverify.VerificationTimes.exactly(n)
    case Times.AtLeast(n) => jverify.VerificationTimes.atLeast(n)
    case Times.AtMost(n) => jverify.VerificationTimes.atMost(n)
    case Times.Between(lo, hi) => jverify.VerificationTimes.between(lo, hi)

  /** Total mapping of `VerifyDetail` onto the facade's varargs enum — `verifyResult`'s detail flags
    * travel entirely inside this array, nothing else to encode Scala-side.
    */
  def verifyDetails(details: Seq[VerifyDetail]): Array[jverify.VerifyDetail] =
    details.map {
      case VerifyDetail.Requests => jverify.VerifyDetail.REQUESTS
      case VerifyDetail.Closest => jverify.VerifyDetail.CLOSEST
    }.toArray

  private val binaryMarker: (String, Json) = ("_mode", Json.Str("binary"))

  /** Translates a rift-scala response into the facade's `IsSpec` for an intercept `serve` rule. The
    * facade only accepts a concrete `IsSpec` (no raw-JSON overload like the stub/verify paths
    * have), so this is a deliberate value translation — covering the plain `is` core (status,
    * headers, body incl. binary) plus every `_behaviors`/`_rift` construct `IsSpec` can express
    * (waits, decorate, repeat, shellTransform, templating, and latency/error/tcp faults).
    *
    * What `IsSpec` genuinely cannot express is still **rejected**, never silently degraded — the
    * residual set belongs to `redirectTo(imposter)`, whose stubs go through the D2 raw-JSON seam
    * unchanged: `copy`/`lookup` (the facade's `CopySpec`/`LookupSpec` have no JSON seam and the
    * model holds these as raw `Json`), unknown behavior keys, `_rift.script`, a latency fault that
    * is neither fixed nor a complete range, an error fault whose headers repeat a name or that
    * carries headers without a body, unknown `is`/top-level keys, and non-`is` responses.
    */
  def isSpec(response: ResponseBuilder): JIsSpec =
    response.build match
      case Response.Is(is, behaviors, riftExt, extra)
          if extra.isEmpty && isPlainIsExtra(is.extra) =>
        val withBehaviors = applyBehaviors(behaviors, isSpecFromIs(is))
        riftExt.fold(withBehaviors)(ext => applyRiftExt(ext, withBehaviors))
      case Response.Is(is, _, _, extra) =>
        val offenders =
          (extra.map(_._1) ++ is.extra.filterNot(_ == binaryMarker).map(_._1)).distinct
        throw invalid(
          s"intercept serve: unknown response key(s) ${offenders.mkString(", ")} have no facade " +
            "IsSpec equivalent — use redirectTo(imposter) for full stub fidelity"
        )
      case _ =>
        throw invalid(
          "intercept serve supports an `is` response; a proxy/inject/fault response is not " +
            "translatable to the facade IsSpec — use redirectTo(imposter) for full stub fidelity"
        )

  private def invalid(msg: String): RiftError.InvalidDefinition =
    RiftError.InvalidDefinition(msg, None)

  private def applyBehaviors(behaviors: Behaviors, spec: JIsSpec): JIsSpec =
    if behaviors.copyEntries.nonEmpty then
      throw invalid(
        "intercept serve: a `copy` behavior cannot be rebuilt through the facade's CopySpec (it " +
          "exposes no JSON seam) — use redirectTo(imposter)"
      )
    if behaviors.lookup.nonEmpty then
      throw invalid(
        "intercept serve: a `lookup` behavior cannot be rebuilt through the facade's LookupSpec " +
          "(it exposes no JSON seam) — use redirectTo(imposter)"
      )
    if behaviors.unknown.nonEmpty then
      throw invalid(
        s"intercept serve: unknown behavior key(s) ${behaviors.unknown.map(_._1).mkString(", ")} " +
          "have no facade IsSpec equivalent — use redirectTo(imposter)"
      )
    val withWait = behaviors.waitFor.fold(spec) {
      case WaitBehavior.Fixed(millis) => spec.waitMs(millis)
      case WaitBehavior.Range(min, max) => spec.waitBetween(min, max)
      case WaitBehavior.Inject(script) => spec.waitInject(script)
      case WaitBehavior.Script(source) => spec.waitScript(source)
    }
    val withDecorate = behaviors.decorate.fold(withWait)(js => withWait.decorate(js))
    val withShell =
      if behaviors.shellTransform.isEmpty then withDecorate
      else withDecorate.shellTransform(behaviors.shellTransform*)
    behaviors.repeat.fold(withShell)(times => withShell.repeat(times))

  private def applyRiftExt(ext: RiftResponseExt, spec: JIsSpec): JIsSpec =
    if ext.script.isDefined then
      throw invalid(
        "intercept serve: an embedded `_rift.script` has no facade IsSpec equivalent — use " +
          "redirectTo(imposter)"
      )
    val withTemplated = if ext.templated then spec.templated() else spec
    ext.fault.fold(withTemplated)(fault => applyFault(fault, withTemplated))

  private def applyFault(fault: FaultConfig, spec: JIsSpec): JIsSpec =
    val withLatency = fault.latency.fold(spec)(l => applyLatency(l, spec))
    val withError = fault.error.fold(withLatency)(e => applyError(e, withLatency))
    fault.tcp.fold(withError) { tcp =>
      val kind = tcpFault(tcp.kind)
      tcp.probability.fold(withError.withTcpFault(kind))(p => withError.withTcpFault(p, kind))
    }

  /** The facade models the two latency spellings as separate overloads and emits only the one it
    * was built with, so a config naming both a fixed delay *and* a range has no faithful
    * translation — rejected rather than silently dropping half of it.
    */
  private def applyLatency(latency: LatencyFault, spec: JIsSpec): JIsSpec =
    (latency.ms, latency.minMs, latency.maxMs) match
      case (Some(ms), None, None) =>
        spec.withLatencyFault(latency.probability, JDuration.ofMillis(ms))
      case (None, Some(min), Some(max)) =>
        spec.withLatencyFault(latency.probability, JDuration.ofMillis(min), JDuration.ofMillis(max))
      case _ =>
        throw invalid(
          "intercept serve: a `latency` fault must name either a fixed `ms` or both `minMs` and " +
            "`maxMs` — the facade IsSpec cannot express any other combination"
        )

  /** The facade's only headers-carrying error-fault overload is `withErrorFault(probability,
    * status, body, headers)`, which wraps `body` in `Optional.of` — so headers without a body is
    * unrepresentable (a null would throw, and `""` would turn an absent body into an empty one).
    */
  private def applyError(error: ErrorFault, spec: JIsSpec): JIsSpec =
    val headers = singleValuedHeaders(error)
    (error.body, headers.isEmpty) match
      case (None, true) => spec.withErrorFault(error.probability, error.status)
      case (Some(body), true) => spec.withErrorFault(error.probability, error.status, body)
      case (Some(body), false) =>
        spec.withErrorFault(error.probability, error.status, body, headers)
      case (None, false) =>
        throw invalid(
          "intercept serve: an `error` fault carrying headers must also carry a body — the " +
            "facade's only headers-carrying overload requires one"
        )

  /** A `LinkedHashMap` rather than `.toMap`: the facade copies whatever iteration order it is
    * handed, and an immutable `Map` of five or more entries iterates in hash order — which would
    * emit the fault's headers in a different order than `ErrorFault.toJson` does.
    */
  private def singleValuedHeaders(error: ErrorFault): java.util.LinkedHashMap[String, String] =
    val grouped = orderedHeaders(error.headers)
    grouped.find(_._2.sizeIs > 1) match
      case Some((name, _)) =>
        throw invalid(
          s"intercept serve: `error` fault header '$name' repeats — the facade takes " +
            "single-valued headers, so collapsing it would drop a value"
        )
      case None =>
        val ordered = java.util.LinkedHashMap[String, String]()
        grouped.foreach((name, values) => ordered.put(name, values.head))
        ordered

  private def tcpFault(kind: TcpFaultKind): JFault = kind match
    case TcpFaultKind.ConnectionResetByPeer => JFault.CONNECTION_RESET_BY_PEER
    case TcpFaultKind.EmptyResponse => JFault.EMPTY_RESPONSE
    case TcpFaultKind.RandomDataThenClose => JFault.RANDOM_DATA_THEN_CLOSE
    case TcpFaultKind.MalformedResponseChunk => JFault.MALFORMED_RESPONSE_CHUNK

  private def isPlainIsExtra(extra: Vector[(String, Json)]): Boolean =
    extra.isEmpty || extra == Vector(binaryMarker)

  private def isSpecFromIs(is: IsResponse): JIsSpec =
    // `rawStatusCode` is a *modeled* field (the non-numeric wire form, e.g. mimeo's `"404"`), so it
    // never lands in `is.extra` and the unknown-key guard cannot see it. `RiftDsl.status` takes an
    // Int, so translating would silently answer 200 for a response that named some other status.
    if is.rawStatusCode.isDefined then
      throw invalid(
        "intercept serve: a non-numeric `statusCode` has no facade IsSpec entry point (RiftDsl." +
          "status takes an Int) — use redirectTo(imposter) for full stub fidelity"
      )
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
