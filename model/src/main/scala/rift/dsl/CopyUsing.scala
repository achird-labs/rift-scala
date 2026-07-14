package rift.dsl

import rift.json.Json

/** The extraction method for a `.copy(from, into, extractWith)` response behavior — Mountebank's
  * `using: {method, selector}` shape.
  */
enum CopyUsing:
  case Regex(pattern: String)
  case JsonPath(path: String)
  case XPath(path: String)

  private[dsl] def toJson: Json = this match
    case CopyUsing.Regex(pattern) =>
      Json.obj("method" -> Json.Str("regex"), "selector" -> Json.Str(pattern))
    case CopyUsing.JsonPath(path) =>
      Json.obj("method" -> Json.Str("jsonpath"), "selector" -> Json.Str(path))
    case CopyUsing.XPath(path) =>
      Json.obj("method" -> Json.Str("xpath"), "selector" -> Json.Str(path))

def regex(pattern: String): CopyUsing = CopyUsing.Regex(pattern)
def jsonPathUsing(path: String): CopyUsing = CopyUsing.JsonPath(path)
def xpathUsing(path: String): CopyUsing = CopyUsing.XPath(path)

/** The key a `.lookup(...)` behavior reads from the request — `copyFrom(query("user"))`. */
final case class LookupKey private[dsl] (fieldJson: Json)

def copyFrom(selector: FieldSelector): LookupKey = LookupKey(selector.locatorJson)
