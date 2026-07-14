package rift.dsl

import rift.json.Json
import rift.model.*

/** A request field selector (`method`, `path`, `body`, `header(name)`, `query(name)`), refinable
  * with `.jsonPath`/`.xpath`, and the source of the predicate operators (`.is`, `.contains`, ...).
  */
final case class FieldSelector private[dsl] (
    key: String,
    fieldName: Option[String] = None,
    selectorValue: Option[PredicateSelector] = None
):
  def jsonPath(path: String): FieldSelector =
    copy(selectorValue = Some(PredicateSelector.JsonPath(path)))

  def xpath(path: String, ns: Map[String, String] = Map.empty): FieldSelector =
    copy(selectorValue = Some(PredicateSelector.XPath(path, ns)))

  private def wrapValue(value: Json): Json = fieldName match
    case Some(n) => Json.Obj(Vector(n -> value))
    case None => value

  private def fields(value: Json): Fields = Fields(Vector(key -> wrapValue(value)))
  private def params: PredicateParams = PredicateParams(selector = selectorValue)

  /** The bare field reference (no comparison value) — used by `.copy`/`.lookup` "from" clauses. */
  private[dsl] def locatorJson: Json = fieldName match
    case Some(n) => Json.Obj(Vector(key -> Json.Str(n)))
    case None => Json.Str(key)

  def is(value: String): PredicateBuilder =
    PredicateBuilder(PredicateOp.Equals(fields(Json.Str(value))), params)

  def deepEquals(value: Json): PredicateBuilder =
    PredicateBuilder(PredicateOp.DeepEquals(fields(value)), params)

  def deepEquals[A](value: A)(using jb: JsonBody[A]): PredicateBuilder = deepEquals(
    jb.encode(value)
  )

  def contains(value: String): PredicateBuilder =
    PredicateBuilder(PredicateOp.Contains(fields(Json.Str(value))), params)

  def startsWith(value: String): PredicateBuilder =
    PredicateBuilder(PredicateOp.StartsWith(fields(Json.Str(value))), params)

  def endsWith(value: String): PredicateBuilder =
    PredicateBuilder(PredicateOp.EndsWith(fields(Json.Str(value))), params)

  def matches(regex: String): PredicateBuilder =
    PredicateBuilder(PredicateOp.Matches(fields(Json.Str(regex))), params)

  def exists: PredicateBuilder =
    PredicateBuilder(PredicateOp.Exists(fields(Json.Bool(true))), params)

  def notExists: PredicateBuilder =
    PredicateBuilder(PredicateOp.Exists(fields(Json.Bool(false))), params)

/** A not-yet-attached predicate: the result of a field selector's operator, or of `not`/`allOf`/
  * `anyOf` combining other predicate builders. `.where(...)` attaches it to a [[StubBuilder]].
  */
final case class PredicateBuilder private[dsl] (
    op: PredicateOp,
    params: PredicateParams = PredicateParams.empty
):
  private[dsl] def build: Predicate = Predicate(op, params)

def method: FieldSelector = FieldSelector("method")
def path: FieldSelector = FieldSelector("path")
def body: FieldSelector = FieldSelector("body")
def header(name: String): FieldSelector = FieldSelector("headers", Some(name))
def query(name: String): FieldSelector = FieldSelector("query", Some(name))

def not(predicate: PredicateBuilder): PredicateBuilder =
  PredicateBuilder(PredicateOp.Not(predicate.build))

def allOf(predicates: PredicateBuilder*): PredicateBuilder =
  PredicateBuilder(PredicateOp.And(predicates.map(_.build).toVector))

def anyOf(predicates: PredicateBuilder*): PredicateBuilder =
  PredicateBuilder(PredicateOp.Or(predicates.map(_.build).toVector))
