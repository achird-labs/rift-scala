package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** `_behaviors.wait`. Four spellings, all canonical per the
  * [[https://github.com/EtaCassiopeia/rift/issues/608 rift#608 ruling]] — the variant set mirrors
  * rift-java's `WaitSpec` so both SDKs accept and emit the same wire forms.
  *
  * Each case re-encodes to the spelling it was decoded from, which is what lets `GET
  * /imposters?replayable=true` hand an author back their own config.
  *
  *   - [[Fixed]] `100` and [[Script]] `"function () {...}"` are the Mountebank-compatible
  *     spellings.
  *   - [[Range]] `{min,max}` and [[Inject]] `{inject}` are Rift extensions — accepted by the engine
  *     but not portable *to* Mountebank.
  */
enum WaitBehavior:
  case Fixed(millis: Long)
  case Range(minMillis: Long, maxMillis: Long)
  case Inject(script: String)
  case Script(source: String)

  def toJson: Json = this match
    case WaitBehavior.Fixed(millis) => Json.Num(BigDecimal(millis))
    case WaitBehavior.Range(min, max) =>
      Json.obj("min" -> Json.Num(BigDecimal(min)), "max" -> Json.Num(BigDecimal(max)))
    case WaitBehavior.Inject(script) => Json.obj("inject" -> Json.Str(script))
    case WaitBehavior.Script(source) => Json.Str(source)

object WaitBehavior:
  private val expected =
    "expected whole-millisecond number, a script string, {inject: <script>}, or {min, max}"

  /** The two object shapes are disjoint (`inject` vs `min`+`max`), so the untagged decode is
    * unambiguous — the same property the engine's own untagged enum relies on. `inject` is checked
    * first, matching rift-java's `WaitSpec.read` precedence.
    *
    * Millisecond counts are `isValidLong`-guarded rather than `toLong`-truncated: the engine types
    * these as `u64` and rift-java throws on a non-integral wait, so `1.5` or `1e30` must be a loud
    * decode error here too — truncating would silently re-encode a *different* config than the one
    * that was read.
    */
  def fromJson(json: Json): Either[JsonError.Decode, WaitBehavior] = json match
    case Json.Num(n) if n.isValidLong => Right(Fixed(n.toLong))
    case Json.Str(s) => Right(Script(s))
    case Json.Obj(fields) =>
      (fields.field("inject"), fields.field("min"), fields.field("max")) match
        case (Some(Json.Str(s)), _, _) => Right(Inject(s))
        case (None, Some(Json.Num(min)), Some(Json.Num(max)))
            if min.isValidLong && max.isValidLong =>
          Right(Range(min.toLong, max.toLong))
        case _ => Left(JsonError.Decode(expected, Vector.empty))
    case _ => Left(JsonError.Decode(expected, Vector.empty))

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

  /** The only keys the object form spells as an array, and therefore the only ones an array form
    * may legally repeat — see [[flattenArray]] and [[coalesce]].
    *
    * Deliberately an allow-list of the *vector* keys rather than a deny-list of the scalar ones: an
    * unknown key the engine grows later is not vector-valued just because we do not model it, and
    * treating it as one would rewrite `{"futureThing":{...}}` into `{"futureThing":[{...}]}` — the
    * exact corruption `unknown` exists to prevent.
    */
  private val vectorKeys = Set("copy", "lookup", "shellTransform")

  /** `GET /imposters` renders `_behaviors` as an **array of single-key objects**
    * (`[{"wait":100},{"decorate":"..."}]`, engine `behaviors_to_array`,
    * `imposter/types.rs:451-472`) while `POST /imposters` takes the object form. Both decode;
    * encoding always uses the object form — rift-java's policy verbatim (`Behaviors.java:13-17`),
    * so a GET -> PUT normalizes spelling identically in both SDKs.
    *
    * Flattening is where the two forms genuinely differ: the array can repeat a key. Only
    * [[vectorKeys]] accumulate, because only they have an array to accumulate *into* on the way
    * out. Any other repeated key — scalar or unknown — has no object-form representation, so it is
    * a decode error rather than a silent last-wins that would lose data on the very next encode.
    */
  private def flattenArray(items: Vector[Json]): Either[JsonError.Decode, Vector[(String, Json)]] =
    items.zipWithIndex.foldLeft[Either[JsonError.Decode, Vector[(String, Json)]]](
      Right(Vector.empty)
    ):
      case (acc, (item, i)) =>
        for
          seen <- acc
          entry <- asObj(item, "behavior").left.map(_.under(s"[$i]").under("_behaviors"))
          pair <- entry match
            case Vector(one) => Right(one)
            case other =>
              Left(
                JsonError
                  .Decode(
                    s"expected exactly one key, got ${other.size}",
                    Vector.empty
                  )
                  .under(s"[$i]")
                  .under("_behaviors")
              )
          _ <-
            if !vectorKeys.contains(pair._1) && seen.exists(_._1 == pair._1) then
              Left(
                JsonError
                  .Decode(s"'${pair._1}' appears more than once", Vector.empty)
                  .under(s"[$i]")
                  .under("_behaviors")
              )
            else Right(())
        yield seen :+ pair

  /** Merges an array's repeated [[vectorKeys]] into the single array the object form uses. Every
    * other key — scalar or unknown — passes through with its value untouched, so a behavior the
    * engine grows later round-trips byte-for-byte.
    *
    * A vector key's value may itself already be an array (`{"copy":[a,b]}`) or a single item
    * (`{"copy":a}`) — both spellings appear for the same key, so elements are flattened one level
    * rather than wrapped blindly, which would nest `[[a]]` and fail the element decoders.
    */
  private def coalesce(pairs: Vector[(String, Json)]): Vector[(String, Json)] =
    def elementsOf(value: Json): Vector[Json] = value match
      case Json.Arr(items) => items
      case single => Vector(single)

    pairs.foldLeft(Vector.empty[(String, Json)]): (acc, pair) =>
      val (key, value) = pair
      if !vectorKeys.contains(key) then acc :+ (key -> value)
      else
        acc.indexWhere(_._1 == key) match
          case -1 => acc :+ (key -> Json.Arr(elementsOf(value)))
          case at =>
            val existing = acc(at)._2.asArray.getOrElse(Vector.empty)
            acc.updated(at, key -> Json.Arr(existing ++ elementsOf(value)))

  private def entryArray(
      fields: Vector[(String, Json)],
      key: String
  ): Either[JsonError.Decode, Vector[Json]] = fields.field(key) match
    case Some(Json.Arr(items)) => Right(items)
    case Some(_) => Left(JsonError.Decode("expected an array", Vector.empty).under(key))
    case None => Right(Vector.empty)

  def fromJson(json: Json): Either[JsonError.Decode, Behaviors] =
    for
      fields <- json match
        case Json.Arr(items) => flattenArray(items).map(coalesce)
        case other => asObj(other, "_behaviors")
      waitFor <- fields.field("wait") match
        case Some(w) => WaitBehavior.fromJson(w).map(Some(_)).left.map(_.under("wait"))
        case None => Right(None)
      decorate <- optString(fields, "decorate")
      // A non-array `copy`/`lookup` used to be swallowed into an empty vector, silently dropping the
      // behavior; the array form accepts the same input, so equivalence between the two forms is
      // only true if this is loud.
      copyEntries <- entryArray(fields, "copy")
      lookup <- entryArray(fields, "lookup")
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
