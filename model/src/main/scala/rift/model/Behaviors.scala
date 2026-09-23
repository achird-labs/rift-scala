package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** `_behaviors.wait`. Four spellings, all canonical per the
  * [[https://github.com/achird-labs/rift/issues/608 rift#608 ruling]] — the variant set mirrors
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

/** One behavior step, as written on the wire. A response's behaviors are an ordered program of
  * these (engine rift#1198): from engine 0.18.0 every element of a `behaviors` array runs, in array
  * order, and a key may repeat.
  *
  * `bare` records a single `copy`/`lookup` entry spelled `{...}` rather than `[{...}]`, or a single
  * `shellTransform` spelled `"cmd"` rather than `["cmd"]`. The engine writes both spellings, and
  * `semanticEquals` tells `Obj` from `Arr`, so the spelling has to survive a round trip. It is only
  * honoured for exactly one entry: a `bare` value with several entries still writes an array.
  */
enum Behavior:
  case Wait(spec: WaitBehavior)
  case Decorate(script: String)
  case Copy(entries: Vector[Json], bare: Boolean)
  case Lookup(entries: Vector[Json], bare: Boolean)
  case ShellTransform(commands: Vector[String], bare: Boolean)

  /** `responseLevel` marks Mountebank's spelling, beside `is` rather than inside the block. It is
    * what the engine's `GET /imposters` writes, and it wins over a block `repeat`. Each spelling is
    * written back where it came from.
    */
  case Repeat(count: Int, responseLevel: Boolean)

  /** A behavior the model does not know. Kept verbatim so a newer engine's behavior survives. */
  case Unknown(name: String, raw: Json)

  def key: String = this match
    case Wait(_) => "wait"
    case Decorate(_) => "decorate"
    case Copy(_, _) => "copy"
    case Lookup(_, _) => "lookup"
    case ShellTransform(_, _) => "shellTransform"
    case Repeat(_, _) => "repeat"
    case Unknown(name, _) => name

  def value: Json = this match
    case Wait(spec) => spec.toJson
    case Decorate(script) => Json.Str(script)
    case Copy(entries, bare) => Behavior.singleOrArray(entries, bare)
    case Lookup(entries, bare) => Behavior.singleOrArray(entries, bare)
    case ShellTransform(commands, bare) => Behavior.singleOrArray(commands.map(Json.Str(_)), bare)
    case Repeat(count, _) => Json.Num(BigDecimal(count))
    case Unknown(_, raw) => raw

object Behavior:
  private def singleOrArray(entries: Vector[Json], bare: Boolean): Json = entries match
    case Vector(one) if bare => one
    case many => Json.Arr(many)

/** A response's behaviors: an ordered program of [[Behavior]] entries.
  *
  * Two wire shapes carry it. The `_behaviors` object is what fixtures and this DSL write; its keys
  * run in the engine's fixed order (`wait`, `lookup`, `copy`, `shellTransform`, `decorate`) and
  * none can repeat. The `behaviors` array, one single-key element per step, is what the engine's
  * `GET /imposters` writes; its elements run in array order and keys may repeat. A response-level
  * `repeat` beside `is` is carried here too, as a [[Behavior.Repeat]] with `responseLevel = true`.
  *
  * Writing keeps the spelling that was read (`spelling`) and falls back to the array whenever the
  * object form would lose an entry, that is when a key repeats. Carrying a repeated key is not the
  * same as an engine running it: engine 0.17.0 merges the array as it parses and applies only the
  * last entry of a repeated key, and 0.18.0 runs every element. Writing the array keeps this SDK
  * from being the component that destroys the entry.
  *
  * A `null` behavior value is refused on purpose: the engine never writes one back and rift-java
  * refuses it too. It is absent in the object form but, in the array form, removes every earlier
  * step of its key, so dropping it on decode would change what runs once the document is re-sent,
  * and keeping it would need a model case whose only job is to carry a shape the engine discards on
  * read. The error says how to rewrite the document.
  *
  * A multi-key array element is read, expanded into one step per key in the engine's run order
  * (issue #178), and written back one key per element: the spelling changes, what runs does not.
  */
final case class Behaviors(
    entries: Vector[Behavior] = Vector.empty,
    spelling: Behaviors.Spelling = Behaviors.Spelling.Object
):
  def isEmpty: Boolean = entries.isEmpty

  /** The steps written inside the block: every entry except a response-level `repeat`. */
  def block: Vector[Behavior] = entries.filterNot(Behaviors.isResponseLevelRepeat)

  /** The response-level `repeat`, written beside `is`, if one was read or set. */
  def responseLevelRepeat: Option[Behavior.Repeat] =
    entries.collectFirst { case r @ Behavior.Repeat(_, true) => r }

  /** The `repeat` count the engine applies: the response-level one if present, else the last block
    * one (engine 0.18.0: "the last element to set it wins"). Engine 0.17.0 reads only the block
    * spelling.
    */
  def effectiveRepeat: Option[Int] =
    responseLevelRepeat
      .orElse(block.collect { case r: Behavior.Repeat => r }.lastOption)
      .map(_.count)

  /** Whether the block must be written as the `behaviors` array: it was read as one, or a key
    * repeats and the `_behaviors` object would keep only one of them.
    */
  def writesArray: Boolean =
    spelling == Behaviors.Spelling.Array || block.map(_.key).distinct.size != block.size

  /** The block's wire value: the array form or the object form, per [[writesArray]]. */
  def toJson: Json =
    if writesArray then Json.Arr(block.map(b => Json.Obj(Vector(b.key -> b.value))))
    else Json.Obj(block.map(b => b.key -> b.value))

object Behaviors:
  /** Which wire shape a block was read from, so a round trip writes the same one back. */
  enum Spelling:
    case Object, Array

  val empty: Behaviors = Behaviors()

  /** A block of `entries` in the object spelling — how the DSL and hand-built values start. */
  def of(entries: Behavior*): Behaviors = Behaviors(entries.toVector)

  private def isResponseLevelRepeat(b: Behavior): Boolean = b match
    case Behavior.Repeat(_, true) => true
    case _ => false

  /** Decodes a block from either spelling. A response-level `repeat` is not part of the block; the
    * enclosing [[Response]] decoder adds it.
    */
  def fromJson(json: Json): Either[JsonError.Decode, Behaviors] = json match
    case arr: Json.Arr =>
      decodeArray(arr, element).map(steps => Behaviors(steps.flatten, Spelling.Array))
    case other =>
      for
        fields <- asObj(other, "_behaviors")
        entries <- fields.foldLeft[Either[JsonError.Decode, Vector[Behavior]]](Right(Vector.empty)):
          case (acc, (key, value)) =>
            for
              seen <- acc
              entry <- decode(key, value, inArray = false)
            yield seen :+ entry
      yield Behaviors(entries, Spelling.Object)

  /** The keys this model types. A `null` for one of them is refused (see [[Behaviors]]); a `null`
    * for any other key is an [[Behavior.Unknown]] value like any other, kept verbatim.
    */
  private val knownKeys = Set("wait", "decorate", "copy", "lookup", "shellTransform", "repeat")

  /** One array element as steps. An element that sets several keys runs them in the engine's fixed
    * order, not the order written (rift-lint W018), so it is expanded into one step per key in that
    * order — what runs is unchanged, and the block writes them back one key per element. Mirrors
    * rift-java 0.3.0 (#240).
    */
  private def element(item: Json): Either[JsonError.Decode, Vector[Behavior]] =
    asObj(item, "behavior").flatMap:
      case Vector() => Left(JsonError.Decode("expected at least one key", Vector.empty))
      case fields =>
        fields
          .sortBy((key, _) => (engineRank(key), key))
          .foldLeft[Either[JsonError.Decode, Vector[Behavior]]](Right(Vector.empty)):
            case (acc, (key, value)) =>
              for
                seen <- acc
                step <- decode(key, value, inArray = true)
              yield seen :+ step

  /** The engine's run order for the keys of one behaviors object: `wait`, `lookup`, `copy`,
    * `shellTransform`, `decorate`, then every other key (`repeat` and unknown ones) alphabetically.
    */
  private def engineRank(key: String): Int =
    runOrder.indexOf(key) match
      case -1 => runOrder.size
      case rank => rank

  private val runOrder = Vector("wait", "lookup", "copy", "shellTransform", "decorate")

  /** The fields that mark a bare object as a real single `copy`/`lookup` entry (the engine's
    * untagged single-operation shorthand) in the object form. `copy` and `lookup` are otherwise raw
    * `Json`, so an "any object goes" rule would swallow garbage like `{"copy":{"a":1}}` as if it
    * were an operation. An array element has no such ambiguity: it is one step by construction.
    */
  private val singleEntryRequiredFields: Map[String, Set[String]] =
    Map("copy" -> Set("from", "into"), "lookup" -> Set("key", "fromDataSource"))

  private def readEntries(
      key: String,
      value: Json,
      inArray: Boolean
  ): Either[JsonError.Decode, (Vector[Json], Boolean)] = value match
    case Json.Arr(entries) => Right((entries, false))
    case single @ Json.Obj(fields)
        if inArray || singleEntryRequiredFields(key).subsetOf(fields.map(_._1).toSet) =>
      Right((Vector(single), true))
    case _ => Left(JsonError.Decode(s"expected an array or a single $key entry", Vector.empty))

  private def decode(
      key: String,
      value: Json,
      inArray: Boolean
  ): Either[JsonError.Decode, Behavior] =
    val decoded: Either[JsonError.Decode, Behavior] = key match
      case known if value == Json.Null && knownKeys(known) =>
        Left(
          JsonError.Decode(
            "null is not read: the engine treats a null behavior as absent (and, in an array, as " +
              "removing earlier steps of that key) and never writes one back; delete the key or " +
              "the element",
            Vector.empty
          )
        )
      case "wait" => WaitBehavior.fromJson(value).map(Behavior.Wait(_))
      case "decorate" =>
        value match
          case Json.Str(s) => Right(Behavior.Decorate(s))
          case _ => Left(JsonError.Decode("expected a string", Vector.empty))
      case "copy" => readEntries(key, value, inArray).map(Behavior.Copy(_, _))
      case "lookup" => readEntries(key, value, inArray).map(Behavior.Lookup(_, _))
      case "shellTransform" =>
        value match
          case Json.Str(s) => Right(Behavior.ShellTransform(Vector(s), bare = true))
          case Json.Arr(commands) =>
            commands
              .foldLeft[Either[JsonError.Decode, Vector[String]]](Right(Vector.empty)):
                case (acc, Json.Str(s)) => acc.map(_ :+ s)
                case (_, _) => Left(JsonError.Decode("expected an array of strings", Vector.empty))
              .map(Behavior.ShellTransform(_, bare = false))
          case _ => Left(JsonError.Decode("expected a string or array of strings", Vector.empty))
      case "repeat" => decodeRepeat(value).map(Behavior.Repeat(_, responseLevel = false))
      case other => Right(Behavior.Unknown(other, value))
    decoded.left.map(_.under(key))

  /** A `repeat` count, block or response-level. The engine types it `u32`; a fractional or
    * non-numeric count is loud rather than truncated.
    */
  private[model] def decodeRepeat(value: Json): Either[JsonError.Decode, Int] = value match
    case Json.Num(n) if n.isValidInt => Right(n.toInt)
    case Json.Num(_) => Left(JsonError.Decode("expected an integer", Vector.empty))
    case _ => Left(JsonError.Decode("expected a number", Vector.empty))
