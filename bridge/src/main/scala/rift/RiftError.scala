package rift

import scala.util.control.NoStackTrace
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.json.Json
import rift.model.{Fields, Port, Predicate, PredicateOp}
import rift.model.RecordedRequest as RecordedRequestModel
import rift.model.matching.{MissedRequest, PredicateFailure, VerificationReport}

import io.github.etacassiopeia.rift.{error as jerr, verify as jverify}
import io.github.etacassiopeia.rift.RecordedRequest as JRecordedRequest

/** The typed error boundary between rift-java and every rift-scala backend (DESIGN.md §5.2, D3).
  * Because `RiftError` *is* an `Exception`, ZIO can `refineToOrDie[RiftError]`, Cats can raise it
  * directly, and Kyo's `Abort[RiftError]`/`pure`'s `Either[RiftError, A]` both hold the same
  * values.
  */
enum RiftError(val message: String, cause: Throwable | Null = null)
    extends Exception(message, cause),
      NoStackTrace:
  case InvalidDefinition(msg: String, cause: Option[Throwable]) extends RiftError(msg, cause.orNull)
  case EngineUnavailable(msg: String, cause: Option[Throwable]) extends RiftError(msg, cause.orNull)
  case CommunicationError(msg: String, cause: Option[Throwable])
      extends RiftError(msg, cause.orNull)
  case ImposterNotFound(port: Port)
      extends RiftError(s"imposter on port ${Port.value(port)} not found")
  case EngineError(code: Int, msg: String) extends RiftError(msg)
  case VerificationFailed(report: VerificationReport) extends RiftError(report.render)
  case DecodeFailed(msg: String, payload: Option[Json]) extends RiftError(msg)

object RiftError:

  /** Total mapping from the rift-java boundary. Every sealed `RiftException` subtype and
    * `VerificationException` maps to a `RiftError`; anything unrecognised stays a defect (`None`)
    * so ZIO/Cats/Kyo boundaries still see it as an unexpected failure rather than a modeled one.
    */
  def fromThrowable(t: Throwable): Option[RiftError] = t match
    case j: jerr.InvalidDefinition => Some(InvalidDefinition(j.getMessage, Option(j.getCause)))
    case j: jerr.EngineUnavailable => Some(EngineUnavailable(j.getMessage, Option(j.getCause)))
    case j: jerr.CommunicationError => Some(CommunicationError(j.getMessage, Option(j.getCause)))
    case j: jerr.ImposterNotFound =>
      Port.from(j.port()) match
        case Right(p) => Some(ImposterNotFound(p))
        // the engine only ever reports 1..65535; an out-of-range port here would be an engine-side
        // defect, and dropping it silently would hide that — downgrade to CommunicationError
        // instead of losing the failure.
        case Left(_) => Some(CommunicationError(j.getMessage, None))
    case j: jerr.EngineError => Some(EngineError(j.code(), j.getMessage))
    case j: jverify.VerificationException => Some(VerificationFailed(translateReport(j)))
    case _ => None

  /** Builds the typed `VerificationReport` from the facade's `VerificationResult` (D5). Per-item
    * decode failures are dropped rather than propagated: this report is documented as a best-effort
    * explanation (`VerificationReport`'s own scaladoc) with the engine's `verify` endpoint staying
    * authoritative, so a partial report — never a wrong `RiftError` case — is the correct
    * degradation.
    */
  private def translateReport(ve: jverify.VerificationException): VerificationReport =
    ve.result().toScala match
      case None => VerificationReport(Vector.empty, Vector.empty)
      case Some(result) =>
        val matched = result.requests().asScala.toVector.flatMap(translateRecorded)
        val missed = result.closest().toScala.toVector.flatMap { closest =>
          translateRecorded(closest.request()).map { request =>
            MissedRequest(
              request,
              closest.failedPredicates().asScala.toVector.flatMap(translateFailedPredicate)
            )
          }
        }
        VerificationReport(matched, missed)

  private def translateRecorded(jr: JRecordedRequest): Option[RecordedRequestModel] =
    Json.parse(jr.raw().toJson()).toOption.flatMap(RecordedRequestModel.fromJson(_).toOption)

  private def translateFailedPredicate(fp: jverify.FailedPredicate): Option[PredicateFailure] =
    val actual = Json.parse(fp.actual().toJson()).toOption
    Json
      .parse(fp.predicate().toJson())
      .toOption
      .flatMap(Predicate.fromJson(_).toOption)
      .map { p =>
        val (field, expected) = fieldAndExpected(p.op)
        PredicateFailure(p.op, field, expected, actual.map(_.render))
      }

  private def fieldAndExpected(op: PredicateOp): (String, String) = op match
    case PredicateOp.Equals(f) => fieldSummary(f)
    case PredicateOp.DeepEquals(f) => fieldSummary(f)
    case PredicateOp.Contains(f) => fieldSummary(f)
    case PredicateOp.StartsWith(f) => fieldSummary(f)
    case PredicateOp.EndsWith(f) => fieldSummary(f)
    case PredicateOp.Matches(f) => fieldSummary(f)
    case PredicateOp.Exists(f) => fieldSummary(f)
    case PredicateOp.And(_) => ("and", "<all>")
    case PredicateOp.Or(_) => ("or", "<any>")
    case PredicateOp.Not(_) => ("not", "<negated>")
    case PredicateOp.Inject(script) => ("inject", script)

  private def fieldSummary(fields: Fields): (String, String) =
    fields.entries.headOption match
      case Some((key, value)) => (key, value.render)
      case None => ("<unknown>", "<unknown>")
