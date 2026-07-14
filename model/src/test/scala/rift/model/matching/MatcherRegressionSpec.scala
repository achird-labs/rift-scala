package rift.model.matching

import rift.dsl.*
import rift.json.Json
import rift.model.*
import rift.model.Method.*
import java.time.Instant

/** Regressions for defects found in review. Each one silently produced a WRONG verdict rather than
  * an error, which is the dangerous kind for a testkit: it passes a verification the engine would
  * have rejected.
  */
class MatcherRegressionSpec extends munit.FunSuite:

  private def req(
      method: Method = GET,
      path: String = "/x",
      query: Map[String, Vector[String]] = Map.empty,
      headers: Vector[(String, String)] = Vector.empty,
      body: Option[Json] = None
  ): RecordedRequest =
    RecordedRequest(
      method = method,
      path = path,
      query = query,
      headers = Headers(headers),
      body = body,
      bodyText = body.map(_.render),
      timestamp = Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )

  private def matchOf(p: Predicate): RequestMatch = new RequestMatch:
    def predicates: Vector[Predicate] = Vector(p)

  private def fields(entries: (String, Json)*): Fields = Fields(entries.toVector)

  // ── every sub-key of a multi-key headers/query predicate must be enforced ──
  test("a multi-key headers equals enforces EVERY header, not just the first"):
    val p = Predicate(
      PredicateOp.Equals(
        fields("headers" -> Json.obj("A" -> Json.Str("1"), "B" -> Json.Str("2")))
      )
    )
    val bothRight = req(headers = Vector("A" -> "1", "B" -> "2"))
    val secondWrong = req(headers = Vector("A" -> "1", "B" -> "WRONG"))
    assertEquals(RequestMatcher.evaluate(matchOf(p), bothRight), MatchResult.Matched)
    RequestMatcher.evaluate(matchOf(p), secondWrong) match
      case MatchResult.Missed(fs) => assertEquals(fs.map(_.field), Vector("headers.B"))
      case other => fail(s"expected a miss on headers.B, got $other")

  test("a multi-key query equals enforces EVERY parameter"):
    val p = Predicate(
      PredicateOp.Equals(fields("query" -> Json.obj("a" -> Json.Str("1"), "b" -> Json.Str("2"))))
    )
    val wrong = req(query = Map("a" -> Vector("1"), "b" -> Vector("WRONG")))
    RequestMatcher.evaluate(matchOf(p), wrong) match
      case MatchResult.Missed(fs) => assertEquals(fs.map(_.field), Vector("query.b"))
      case other => fail(s"expected a miss on query.b, got $other")

  test("a multi-key exists enforces every sub-key"):
    val p = Predicate(
      PredicateOp.Exists(
        fields("headers" -> Json.obj("A" -> Json.Bool(true), "B" -> Json.Bool(true)))
      )
    )
    RequestMatcher.evaluate(matchOf(p), req(headers = Vector("A" -> "1"))) match
      case MatchResult.Missed(fs) => assertEquals(fs.map(_.field), Vector("headers.B"))
      case other => fail(s"expected a miss on headers.B, got $other")

  // ── deepEquals compares structurally ──────────────────────────────────────
  test("deepEquals matches on query"):
    val p = Predicate(PredicateOp.DeepEquals(fields("query" -> Json.obj("a" -> Json.Str("1")))))
    assertEquals(
      RequestMatcher.evaluate(matchOf(p), req(query = Map("a" -> Vector("1")))),
      MatchResult.Matched
    )

  test("deepEquals on query is exact — an extra parameter does not match"):
    val p = Predicate(PredicateOp.DeepEquals(fields("query" -> Json.obj("a" -> Json.Str("1")))))
    val extra = req(query = Map("a" -> Vector("1"), "b" -> Vector("2")))
    assertNotEquals(RequestMatcher.evaluate(matchOf(p), extra), MatchResult.Matched)

  test("deepEquals matches on path and method"):
    val p = Predicate(
      PredicateOp.DeepEquals(fields("path" -> Json.Str("/api/users"), "method" -> Json.Str("GET")))
    )
    assertEquals(RequestMatcher.evaluate(matchOf(p), req(path = "/api/users")), MatchResult.Matched)

  test("deepEquals matches structurally on a JSON body"):
    val body = Json.parse("""{"a":1,"b":[2,3]}""").toOption.get
    val reordered = Json.parse("""{"b":[2,3],"a":1.0}""").toOption.get
    val p = Predicate(PredicateOp.DeepEquals(fields("body" -> reordered)))
    assertEquals(RequestMatcher.evaluate(matchOf(p), req(body = Some(body))), MatchResult.Matched)

  // ── an invalid regex must not escape a pure API ────────────────────────────
  test("an invalid regex reports a miss instead of throwing"):
    val p = Predicate(PredicateOp.Matches(fields("path" -> Json.Str("[unclosed"))))
    assertNotEquals(RequestMatcher.evaluate(matchOf(p), req()), MatchResult.Matched)
