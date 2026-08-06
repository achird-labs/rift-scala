package rift.bridge

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.dsl.{RequestMatch, ResponseBuilder}
import rift.json.{Json, JsonError}
import rift.model.{
  Behaviors,
  ClosestMiss,
  FailedPredicate,
  FlowId,
  Headers,
  IsResponse,
  Port,
  Predicate,
  RecordedRequest,
  Response,
  RiftResponseExt,
  Stub,
  Times,
  VerificationResult,
  VerifyDetail
}

import io.github.achirdlabs.rift.verify as jverify
import io.github.achirdlabs.rift.MatchClause as JMatchClause
import io.github.achirdlabs.rift.json.JsonValue as JJsonValue
import io.github.achirdlabs.rift.dsl.{IsSpec as JIsSpec, RiftDsl as JRiftDsl}
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

  /** Translates a rift-scala response into the facade's `IsSpec` for an intercept `serve` rule.
    *
    * The accepted set is what the engine's serve action delivers: a numeric `statusCode`,
    * single-valued `headers`, and a text or JSON `body`. `InterceptImpl.toServeStub`
    * (rift-java-core 0.2.3) builds that action from those three fields alone — it reads neither
    * `Response.Is.behaviors()` nor `.rift()` nor `IsResponse.mode()`, and keeps only the first
    * value of each header — so everything else an `IsSpec` can carry is discarded there, whatever
    * this translation puts into it.
    *
    * So the undeliverable set is **rejected** here rather than translated (issue #147): passing it
    * on would register a rule that answers a response the caller never asked for, and a
    * fault-injection test written against one asserts resilience the system under test does not
    * have. `redirectTo(imposter)` is the full-fidelity path — its stubs go through the D2 raw-JSON
    * seam unchanged. See `requireDeliverable` for the set and for what would relax it.
    *
    * One caveat *within* the accepted set: the engine writes a serve rule's headers verbatim and
    * infers none, while an imposter answering the same response defaults an absent `Content-Type`
    * to `application/json`. So a `.json(...)` body served here arrives untyped unless the response
    * sets the header itself — issue #149.
    */
  def isSpec(response: ResponseBuilder): JIsSpec =
    response.build match
      case Response.Is(is, behaviors, riftExt, extra)
          if extra.isEmpty && isPlainIsExtra(is.extra) =>
        requireDeliverable(is, behaviors, riftExt)
        isSpecFromIs(is)
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

  /** Names **every** construct in this guard's own set, in one error rather than the first one
    * found: first-wins would send a caller round the loop once per construct, each time reporting a
    * rule they had already been told was unusable. (The two rejections outside this set — an
    * unknown key in `isSpec`, a non-numeric `statusCode` in `isSpecFromIs` — are still first-wins;
    * they are "cannot express" rather than "would be dropped", and pairing one with a construct
    * from this set is not a shape the DSL builds.)
    *
    * These are not translation gaps — `IsSpec` expresses most of them, and this module used to
    * translate them faithfully. They are dropped one hop later, by `InterceptImpl.toServeStub`
    * (achird-labs/rift-java#207), which is why the reason given is about the engine's action and
    * not about the facade. When that is fixed upstream, whichever of these the new action carries
    * can come off this list and go back to being translated.
    */
  private def requireDeliverable(
      is: IsResponse,
      behaviors: Behaviors,
      riftExt: Option[RiftResponseExt]
  ): Unit =
    val ext = riftExt.getOrElse(RiftResponseExt())
    val fault = ext.fault
    val dropped =
      Vector(
        Option.when(behaviors.waitFor.isDefined)("`_behaviors.wait`"),
        Option.when(behaviors.decorate.isDefined)("`_behaviors.decorate`"),
        Option.when(behaviors.copyEntries.nonEmpty)("`_behaviors.copy`"),
        Option.when(behaviors.lookup.nonEmpty)("`_behaviors.lookup`"),
        Option.when(behaviors.shellTransform.nonEmpty)("`_behaviors.shellTransform`"),
        Option.when(behaviors.repeat.isDefined)("`_behaviors.repeat`")
      ).flatten ++
        behaviors.unknown.map((key, _) => s"`_behaviors.$key`") ++
        Vector(
          Option.when(ext.templated)("`_rift.templated`"),
          Option.when(ext.script.isDefined)("`_rift.script`"),
          Option
            .when(fault.exists(_.latency.isDefined))("`_rift.fault.latency` (withLatencyFault)"),
          Option.when(fault.exists(_.error.isDefined))("`_rift.fault.error` (withErrorFault)"),
          Option.when(fault.exists(_.tcp.isDefined))("`_rift.fault.tcp` (withTcpFault)"),
          Option.when(is.extra.contains(binaryMarker))("a binary body (`_mode=binary`)")
        ).flatten ++
        repeatedHeaderNames(is.headers).map(name => s"repeated header '$name'")

    if dropped.nonEmpty then
      throw invalid(
        s"intercept serve cannot deliver ${dropped.mkString(", ")} — the engine's serve action " +
          "carries only statusCode, headers and body, so the rule would be registered and then " +
          "answer a response you did not ask for. Use redirectTo(imposter) for full stub fidelity."
      )

  /** Header names carrying more than one value, in first-seen order. The engine's serve action
    * emits only `values.get(0)` per name, so the rest would vanish.
    *
    * Quadratic, over a header list: the alternative that reads as cheaper (`groupBy`) returns hash
    * order, which would reorder the names in the error message run to run.
    */
  private def repeatedHeaderNames(headers: Headers): Vector[String] =
    val names = headers.entries.map(_._1)
    names.distinct.filter(name => names.count(_ == name) > 1)

  private def isPlainIsExtra(extra: Vector[(String, Json)]): Boolean =
    extra.isEmpty || extra == Vector(binaryMarker)

  private def isSpecFromIs(is: IsResponse): JIsSpec =
    // `rawStatusCode` is a *modeled* field (the non-numeric wire form, e.g. a migrated mock's `"404"`), so it
    // never lands in `is.extra` and the unknown-key guard cannot see it. `RiftDsl.status` takes an
    // Int, so translating would silently answer 200 for a response that named some other status.
    if is.rawStatusCode.isDefined then
      throw invalid(
        "intercept serve: a non-numeric `statusCode` has no facade IsSpec entry point (RiftDsl." +
          "status takes an Int) — use redirectTo(imposter) for full stub fidelity"
      )
    val withStatus = JRiftDsl.status(is.statusCode.getOrElse(200))
    // One call per entry rather than a varargs-per-name collapse: `requireDeliverable` has already
    // rejected any repeated name, so no entry here shares a name with another. That guard is what
    // makes this safe — the facade's `withHeader` is a `LinkedHashMap.put`, so a repeated name
    // would quietly keep the last value rather than fail.
    val withHeaders = is.headers.entries.foldLeft(withStatus) { case (spec, (name, value)) =>
      spec.withHeader(name, value)
    }
    is.body match
      case Some(Json.Str(s)) => withHeaders.withTextBody(s)
      case Some(json) => withHeaders.withJsonBody(JJsonValue.parse(json.render))
      case None => withHeaders
