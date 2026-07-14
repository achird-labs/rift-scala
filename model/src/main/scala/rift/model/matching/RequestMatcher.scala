package rift.model.matching

import rift.dsl.RequestMatch
import rift.json.Json
import rift.model.*

import java.util.regex.{Pattern, PatternSyntaxException}

/** A pure client-side diff engine (DESIGN.md §5.1.4, D5): evaluates a `RequestMatch`'s predicates
  * against a `RecordedRequest` and explains near misses for testkit verification failures. The
  * engine's own `verify` endpoint is authoritative — this is best-effort.
  */
object RequestMatcher:

  def evaluate(m: RequestMatch, r: RecordedRequest): MatchResult =
    val failures = m.predicates.flatMap(evalPredicate(_, r))
    if failures.isEmpty then MatchResult.Matched else MatchResult.Missed(failures)

  def explain(m: RequestMatch, recorded: Vector[RecordedRequest]): VerificationReport =
    val results = recorded.map(r => r -> evaluate(m, r))
    val matched = results.collect { case (r, MatchResult.Matched) => r }
    val missed = results.collect { case (r, MatchResult.Missed(fs)) => MissedRequest(r, fs) }
    VerificationReport(matched, missed)

  private def evalPredicate(p: Predicate, r: RecordedRequest): Vector[PredicateFailure] =
    p.op match
      case op @ PredicateOp.Equals(fields) => evalComparison(op, fields, p.params, r)(eqCompare)
      case op @ PredicateOp.DeepEquals(fields) => evalDeepEquals(op, fields, p.params, r)
      case op @ PredicateOp.Contains(fields) =>
        evalComparison(op, fields, p.params, r)(containsCompare)
      case op @ PredicateOp.StartsWith(fields) =>
        evalComparison(op, fields, p.params, r)(startsWithCompare)
      case op @ PredicateOp.EndsWith(fields) =>
        evalComparison(op, fields, p.params, r)(endsWithCompare)
      case op @ PredicateOp.Matches(fields) =>
        evalComparison(op, fields, p.params, r)(matchesCompare)
      case op @ PredicateOp.Exists(fields) => evalExists(op, fields, p.params, r)
      case PredicateOp.And(ps) => ps.flatMap(evalPredicate(_, r))
      case PredicateOp.Or(ps) =>
        val results = ps.map(evalPredicate(_, r))
        if results.exists(_.isEmpty) then Vector.empty else results.flatten
      case PredicateOp.Not(inner) =>
        if evalPredicate(inner, r).isEmpty then
          Vector(PredicateFailure(inner.op, "not", "predicate not to match", None))
        else Vector.empty
      case PredicateOp.Inject(_) => Vector.empty

  /** One comparable leaf: the failure label, the expected leaf JSON, and the request's actual text.
    */
  private final case class Resolved(field: String, expected: Json, actual: Option[String])

  /** Resolves one `Fields` entry into EVERY leaf it constrains.
    *
    * `headers` and `query` carry an object of sub-keys, and each one is a separate constraint:
    * returning only the first would let `{"headers":{"A":"1","B":"2"}}` match a request that
    * satisfies `A` alone, silently widening the match.
    */
  private def resolve(
      key: String,
      value: Json,
      params: PredicateParams,
      r: RecordedRequest
  ): Vector[Resolved] =
    key match
      case "method" => Vector(Resolved("method", value, Some(methodWire(r.method))))
      case "path" => Vector(Resolved("path", value, Some(r.path)))
      case "headers" =>
        value.asObject match
          case Some(subs) if subs.nonEmpty =>
            subs.map((name, leaf) => Resolved(s"headers.$name", leaf, r.headers.get(name)))
          case _ => Vector(Resolved("headers", value, None))
      case "query" =>
        value.asObject match
          case Some(subs) if subs.nonEmpty =>
            subs.map { (name, leaf) =>
              Resolved(s"query.$name", leaf, r.query.get(name).flatMap(_.headOption))
            }
          case _ => Vector(Resolved("query", value, None))
      case "body" =>
        val actual = params.selector match
          case Some(PredicateSelector.JsonPath(path)) =>
            r.body.flatMap(SimpleJsonPath.get(_, path)).map(jsonToText)
          case Some(PredicateSelector.XPath(_, _)) => r.bodyText
          case None => r.bodyText.orElse(r.body.map(_.render))
        Vector(Resolved("body", value, actual))
      case other => Vector(Resolved(other, value, None))

  private def evalComparison(
      op: PredicateOp,
      fields: Fields,
      params: PredicateParams,
      r: RecordedRequest
  )(
      cmp: (String, String, Boolean) => Boolean
  ): Vector[PredicateFailure] =
    fields.entries.flatMap { (key, value) =>
      resolve(key, value, params, r).flatMap { res =>
        val expectedText = jsonToText(res.expected)
        res.actual match
          case Some(a) if cmp(a, expectedText, params.caseSensitive) => Vector.empty
          case other => Vector(PredicateFailure(op, res.field, expectedText, other))
      }
    }

  /** `deepEquals` compares a whole field structurally: `{"query":{"a":"1"}}` asserts the request's
    * entire query object, not merely that `a` is present. Scalars (`method`, `path`) have no
    * structure, so they compare as text — re-parsing them as JSON would make `path` never match.
    */
  private def evalDeepEquals(
      op: PredicateOp,
      fields: Fields,
      params: PredicateParams,
      r: RecordedRequest
  ): Vector[PredicateFailure] =
    fields.entries.flatMap { (key, expected) =>
      val (fieldName, actualJson) = resolveStructural(key, params, r)
      if actualJson.exists(_.semanticEquals(expected)) then Vector.empty
      else Vector(PredicateFailure(op, fieldName, jsonToText(expected), actualJson.map(jsonToText)))
    }

  private def resolveStructural(
      key: String,
      params: PredicateParams,
      r: RecordedRequest
  ): (String, Option[Json]) =
    key match
      case "method" => ("method", Some(Json.Str(methodWire(r.method))))
      case "path" => ("path", Some(Json.Str(r.path)))
      case "headers" =>
        ("headers", Some(Json.Obj(r.headers.entries.map((k, v) => k -> Json.Str(v)))))
      case "query" =>
        val entries = r.query.toVector.sortBy(_._1).map { (k, vs) =>
          k -> (if vs.sizeIs == 1 then Json.Str(vs.head) else Json.Arr(vs.map(Json.Str.apply)))
        }
        ("query", Some(Json.Obj(entries)))
      case "body" =>
        val b = params.selector match
          case Some(PredicateSelector.JsonPath(path)) => r.body.flatMap(SimpleJsonPath.get(_, path))
          case _ => r.body
        ("body", b)
      case other => (other, None)

  private def evalExists(
      op: PredicateOp,
      fields: Fields,
      params: PredicateParams,
      r: RecordedRequest
  ): Vector[PredicateFailure] =
    fields.entries.flatMap { (key, value) =>
      resolve(key, value, params, r).flatMap { res =>
        val shouldExist = res.expected match
          case Json.Bool(b) => b
          case _ => true
        if res.actual.isDefined == shouldExist then Vector.empty
        else
          val expected = if shouldExist then "<exists>" else "<absent>"
          Vector(PredicateFailure(op, res.field, expected, res.actual))
      }
    }

  private def jsonToText(j: Json): String = j match
    case Json.Str(s) => s
    case other => other.render

  private def methodWire(m: Method): String = m.toJson.asString.getOrElse(m.toString)

  private def eqCompare(actual: String, expected: String, caseSensitive: Boolean): Boolean =
    if caseSensitive then actual == expected else actual.equalsIgnoreCase(expected)

  private def containsCompare(actual: String, expected: String, caseSensitive: Boolean): Boolean =
    if caseSensitive then actual.contains(expected)
    else actual.toLowerCase.contains(expected.toLowerCase)

  private def startsWithCompare(actual: String, expected: String, caseSensitive: Boolean): Boolean =
    if caseSensitive then actual.startsWith(expected)
    else actual.toLowerCase.startsWith(expected.toLowerCase)

  private def endsWithCompare(actual: String, expected: String, caseSensitive: Boolean): Boolean =
    if caseSensitive then actual.endsWith(expected)
    else actual.toLowerCase.endsWith(expected.toLowerCase)

  /** An invalid regex is the user's data, not a defect: this explainer runs *because* something
    * already failed, so it reports a non-match rather than throwing out of a pure API.
    */
  private def matchesCompare(actual: String, expected: String, caseSensitive: Boolean): Boolean =
    val flags = if caseSensitive then 0 else Pattern.CASE_INSENSITIVE
    try Pattern.compile(expected, flags).matcher(actual).find()
    catch case _: PatternSyntaxException => false

  /** A minimal dotted-path evaluator for `$.a.b` style selectors — no dependency, only simple
    * object-field traversal is required (DESIGN.md §5.1.4).
    */
  private object SimpleJsonPath:
    def get(json: Json, path: String): Option[Json] =
      val trimmed = path.stripPrefix("$")
      val segments = trimmed.split('.').iterator.filter(_.nonEmpty).toVector
      segments.foldLeft(Option(json)) {
        case (Some(Json.Obj(fields)), seg) => fields.collectFirst { case (k, v) if k == seg => v }
        case _ => None
      }
