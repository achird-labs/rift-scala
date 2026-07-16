package rift.bridge

import scala.jdk.CollectionConverters.*

import rift.RiftError
import rift.dsl.RequestMatch
import rift.json.{Json, JsonError}
import rift.model.{RecordedRequest, Stub, Times}

import io.github.etacassiopeia.rift.verify as jverify
import io.github.etacassiopeia.rift.json.JsonValue as JJsonValue
import io.github.etacassiopeia.rift.RecordedRequest as JRecordedRequest
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

  def times(t: Times): jverify.VerificationTimes = t match
    case Times.Exactly(n) => jverify.VerificationTimes.exactly(n)
    case Times.AtLeast(n) => jverify.VerificationTimes.atLeast(n)
    case Times.AtMost(n) => jverify.VerificationTimes.atMost(n)
    case Times.Between(lo, hi) => jverify.VerificationTimes.between(lo, hi)
