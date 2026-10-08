package rift.model

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** Declarative conditional GET on an `is` response, `_rift.conditional` (engine >= 0.20.0): the
  * engine adds an `ETag` and a `Last-Modified` to a 2xx answer to a GET or HEAD, and answers a
  * request presenting either back with a bodyless `304`.
  *
  * Mirrors the engine's wire form exactly, so a definition read back re-encodes as it was written:
  * `true`/`false`, or the validators spelled out with each absent field kept absent.
  */
enum RiftConditional:
  /** `true` — an `ETag` and `Last-Modified: load` — or `false`, the off state. */
  case Enabled(on: Boolean)

  /** The validators spelled out; an absent field takes the engine's default. `etag`: whether to
    * send a strong `ETag` over the served bytes (absent = yes). `lastModified`: `"load"` (when the
    * stub was created or last changed) or a fixed HTTP-date served verbatim (absent = `"load"`).
    */
  case Validators(etag: Option[Boolean], lastModified: Option[String])

  def toJson: Json = this match
    case Enabled(on) => Json.Bool(on)
    case Validators(etag, lastModified) =>
      Json.Obj(
        Vector(
          etag.map("etag" -> Json.Bool(_)),
          lastModified.map("lastModified" -> Json.Str(_))
        ).flatten
      )

object RiftConditional:
  private val keys = Set("etag", "lastModified")

  /** RFC 7231 §7.1.1.1 IMF-fixdate, which the engine serves verbatim: a two-digit day, whole
    * seconds, always GMT, and English names whatever the JVM's default locale. Not
    * `RFC_1123_DATE_TIME`, which writes a one-digit day.
    */
  private val imfFixdate =
    DateTimeFormatter
      .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.ROOT)
      .withZone(ZoneOffset.UTC)

  def httpDate(instant: Instant): String =
    imfFixdate.format(instant.truncatedTo(ChronoUnit.SECONDS))

  /** An unknown key inside the object is refused rather than dropped: the model has nowhere to keep
    * it, so accepting it would silently lose it on the next encode.
    */
  def fromJson(json: Json): Either[JsonError.Decode, RiftConditional] =
    json match
      case Json.Bool(on) => Right(Enabled(on))
      case Json.Obj(fields) =>
        fields.map(_._1).find(!keys(_)) match
          case Some(unknown) => Left(JsonError.Decode("unknown key", Vector.empty).under(unknown))
          case None =>
            for
              etag <- fields.field("etag") match
                case Some(Json.Bool(b)) => Right(Some(b))
                case Some(_) =>
                  Left(JsonError.Decode("expected a boolean", Vector.empty).under("etag"))
                case None => Right(None)
              lastModified <- optString(fields, "lastModified")
            yield Validators(etag, lastModified)
      case _ => Left(JsonError.Decode("expected a boolean or an object", Vector.empty))
