package rift.model

import rift.json.{Json, JsonError}
import JsonSupport.*

/** One entry of a response's `_rift.stateOps` (engine 0.18.0, rift#969): a flow-state write the
  * engine runs after the response, in list order, against the request's resolved flow id. No script
  * is needed, and an imposter with state ops but no `_rift.flowState` gets an in-memory store.
  *
  * Mirrors rift-java 0.3.0's `StateOp`. An op this model does not know is carried verbatim as
  * [[StateOp.Unknown]] rather than failing the read, so a newer engine's op round-trips.
  */
enum StateOp:
  /** Stores `value` under `key`. `value` is a template the engine renders, with the request, the
    * flow's state and the key's previous value in scope.
    */
  case Set(key: String, value: String)

  /** Adds `by` (which may be negative) to the integer under `key`. `None` is the engine's default
    * of 1, kept distinct so a document without `by` is written back without one.
    */
  case Increment(key: String, by: Option[Long])

  case Delete(key: String)

  /** Removes every key of the request's flow. */
  case ClearFlow

  case Unknown(op: String, raw: Json)

  def toJson: Json = this match
    case StateOp.Set(key, value) =>
      Json.obj("op" -> Json.Str("set"), "key" -> Json.Str(key), "value" -> Json.Str(value))
    case StateOp.Increment(key, by) =>
      Json.Obj(
        Vector(Some("op" -> Json.Str("increment")), Some("key" -> Json.Str(key))).flatten ++
          by.map(n => "by" -> Json.Num(BigDecimal(n)))
      )
    case StateOp.Delete(key) => Json.obj("op" -> Json.Str("delete"), "key" -> Json.Str(key))
    case StateOp.ClearFlow => Json.obj("op" -> Json.Str("clearFlow"))
    case StateOp.Unknown(_, raw) => raw

object StateOp:
  def fromJson(json: Json): Either[JsonError.Decode, StateOp] =
    for
      fields <- asObj(json, "state op")
      op <- reqString(fields, "op")
      decoded <- op match
        case "set" =>
          for
            key <- reqString(fields, "key")
            value <- reqString(fields, "value")
          yield Set(key, value)
        case "increment" =>
          for
            key <- reqString(fields, "key")
            by <- optLong(fields, "by")
          yield Increment(key, by)
        case "delete" => reqString(fields, "key").map(Delete(_))
        case "clearFlow" => Right(ClearFlow)
        case other => Right(Unknown(other, json))
    yield decoded
