package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

enum WaitBehavior:
  case Fixed(millis: Long)
  case Inject(script: String)

  def toJson: Json = this match
    case WaitBehavior.Fixed(millis) => Json.Num(BigDecimal(millis))
    case WaitBehavior.Inject(script) => Json.obj("inject" -> Json.Str(script))

object WaitBehavior:
  def fromJson(json: Json): Either[JsonError.Decode, WaitBehavior] = json match
    case Json.Num(n) => Right(Fixed(n.toLong))
    case Json.Obj(fields) =>
      fields.field("inject") match
        case Some(Json.Str(s)) => Right(Inject(s))
        case _ => Left(JsonError.Decode("expected {inject: <script>}", Vector.empty))
    case _ => Left(JsonError.Decode("expected a number or {inject: <script>}", Vector.empty))

/** `_behaviors`. Unknown keys survive on `unknown` — the engine adds new behaviors over time and a
  * decode -> encode round-trip must not drop them.
  *
  * `waitFor`/`copyEntries` are named around two collisions: `wait` is `final` on `Object`, and
  * `copy` would shadow the case class's synthesized `copy` method.
  */
final case class Behaviors(
    waitFor: Option[WaitBehavior] = None,
    decorate: Option[String] = None,
    copyEntries: Vector[Json] = Vector.empty,
    lookup: Vector[Json] = Vector.empty,
    shellTransform: Vector[String] = Vector.empty,
    repeat: Option[Int] = None,
    unknown: Vector[(String, Json)] = Vector.empty
):
  def isEmpty: Boolean =
    waitFor.isEmpty && decorate.isEmpty && copyEntries.isEmpty && lookup.isEmpty &&
      shellTransform.isEmpty && repeat.isEmpty && unknown.isEmpty

  def toJson: Json =
    val known = Vector(
      waitFor.map(w => "wait" -> w.toJson),
      decorate.map(d => "decorate" -> Json.Str(d)),
      if copyEntries.nonEmpty then Some("copy" -> Json.Arr(copyEntries)) else None,
      if lookup.nonEmpty then Some("lookup" -> Json.Arr(lookup)) else None,
      if shellTransform.nonEmpty then
        Some("shellTransform" -> Json.Arr(shellTransform.map(Json.Str(_))))
      else None,
      repeat.map(r => "repeat" -> Json.Num(BigDecimal(r)))
    ).flatten
    buildObj(Behaviors.knownKeys, known, unknown)

object Behaviors:
  val empty: Behaviors = Behaviors()

  private val knownKeys = Set("wait", "decorate", "copy", "lookup", "shellTransform", "repeat")

  def fromJson(json: Json): Either[JsonError.Decode, Behaviors] =
    for
      fields <- asObj(json, "_behaviors")
      waitFor <- fields.field("wait") match
        case Some(w) => WaitBehavior.fromJson(w).map(Some(_)).left.map(_.under("wait"))
        case None => Right(None)
      decorate <- optString(fields, "decorate")
      copyEntries = fields.field("copy").flatMap(_.asArray).getOrElse(Vector.empty)
      lookup = fields.field("lookup").flatMap(_.asArray).getOrElse(Vector.empty)
      shellTransform <- fields.field("shellTransform") match
        case Some(Json.Arr(items)) =>
          items.foldLeft[Either[JsonError.Decode, Vector[String]]](Right(Vector.empty)) {
            case (acc, Json.Str(s)) => acc.map(_ :+ s)
            case (_, _) =>
              Left(
                JsonError
                  .Decode("expected an array of strings", Vector.empty)
                  .under("shellTransform")
              )
          }
        case Some(Json.Str(s)) => Right(Vector(s))
        case Some(_) =>
          Left(
            JsonError
              .Decode("expected a string or array of strings", Vector.empty)
              .under("shellTransform")
          )
        case None => Right(Vector.empty)
      repeat <- optInt(fields, "repeat")
    yield Behaviors(
      waitFor,
      decorate,
      copyEntries,
      lookup,
      shellTransform,
      repeat,
      fields.remainder(knownKeys)
    )
