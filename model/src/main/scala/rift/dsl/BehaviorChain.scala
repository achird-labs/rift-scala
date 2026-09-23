package rift.dsl

import rift.json.Json
import rift.model.*

import scala.concurrent.duration.FiniteDuration

/** The Mountebank behavior chainers, shared by every response builder whose response the engine
  * runs behaviors on: `is`, `proxy` (engine 0.18.0 runs them on the upstream response, before it is
  * recorded) and `inject` (issue #173). Mirrors rift-java 0.3.0's `BehaviorChain`.
  *
  * The chainers build one `_behaviors` object. Its keys run in the engine's fixed order — `wait`,
  * `lookup`, `copy`, `shellTransform`, `decorate` — however they were written, so the order the
  * chainers are called in is not the order they run. An ordered program (a `behaviors` array, with
  * repeated keys) is written as raw JSON, not authored here.
  */
trait BehaviorChain[Self]:
  protected def behaviorsValue: Behaviors
  protected def withBehaviors(behaviors: Behaviors): Self

  /** Sets or merges one behavior into the `_behaviors` object this builder writes. The DSL keeps
    * one entry per key, in the order it has always written them, whatever order the chainers are
    * called in: an ordered program with a repeated key is read from raw JSON, not authored here.
    */
  private def withBehavior(key: String)(f: Option[Behavior] => Behavior): Self =
    val entries = behaviorsValue.entries
    val updated = entries.indexWhere(_.key == key) match
      case -1 =>
        val rank = BehaviorChain.behaviorOrder.indexOf(key)
        val at = entries.indexWhere(e => BehaviorChain.behaviorOrder.indexOf(e.key) > rank)
        val next = f(None)
        if at < 0 then entries :+ next else entries.patch(at, Vector(next), 0)
      case i => entries.updated(i, f(Some(entries(i))))
    withBehaviors(behaviorsValue.copy(entries = updated))

  def after(duration: FiniteDuration): Self =
    withBehavior("wait")(_ => Behavior.Wait(WaitBehavior.Fixed(duration.toMillis)))

  /** A random wait in `[min, max]`, using the engine's native `{min,max}` wait rather than a
    * generated JS function — nothing to execute, and no injection support needed at serve time.
    */
  def afterBetween(min: FiniteDuration, max: FiniteDuration): Self =
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
  def afterInject(script: String): Self =
    withBehavior("wait")(_ => Behavior.Wait(WaitBehavior.Inject(script)))

  def decorate(js: String): Self =
    withBehavior("decorate")(_ => Behavior.Decorate(js))

  /** `_behaviors.shellTransform`: pipe the response through shell command(s), in order. Repeated
    * calls append, matching the facade's varargs accumulation. Always emits the array wire form —
    * the bare-string spelling exists only to preserve what a decode saw.
    */
  def shellTransform(commands: String*): Self =
    if commands.isEmpty then withBehaviors(behaviorsValue)
    else
      withBehavior("shellTransform"):
        case Some(Behavior.ShellTransform(existing, _)) =>
          Behavior.ShellTransform(existing ++ commands, bare = false)
        case _ => Behavior.ShellTransform(commands.toVector, bare = false)

  def copy(from: FieldSelector, into: String, extractWith: CopyUsing): Self =
    val entry =
      Json.obj("from" -> from.locatorJson, "into" -> Json.Str(into), "using" -> extractWith.toJson)
    withBehavior("copy"):
      case Some(Behavior.Copy(existing, _)) => Behavior.Copy(existing :+ entry, bare = false)
      case _ => Behavior.Copy(Vector(entry), bare = false)

  def lookup(key: LookupKey, csv: String, keyColumn: String, into: String): Self =
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

  def repeat(times: Int): Self =
    withBehavior("repeat")(_ => Behavior.Repeat(times, responseLevel = false))

object BehaviorChain:
  /** The key order the DSL has always written `_behaviors` in. The object form runs in the engine's
    * own fixed order whatever order its keys are written, so this only keeps the output stable.
    */
  private[dsl] val behaviorOrder =
    Vector("wait", "decorate", "copy", "lookup", "shellTransform", "repeat")
