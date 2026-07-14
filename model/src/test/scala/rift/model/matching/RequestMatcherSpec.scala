package rift.model.matching

import rift.dsl.*
import rift.json.Json
import rift.model.*
import rift.model.Method.*
import java.time.Instant

/** AC9 — `RequestMatcher` purely evaluates a `RequestMatch` against `RecordedRequest`s and explains
  * near misses (DESIGN.md §5.1.4, D5). Best-effort: the engine stays authoritative.
  */
class RequestMatcherSpec extends munit.FunSuite:

  private def req(
      method: Method = GET,
      path: String = "/api/users/1",
      query: Map[String, Vector[String]] = Map.empty,
      headers: Map[String, String] = Map.empty,
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

  test("a matching request yields Matched"):
    val m = on(GET, "/api/users/1").where(header("Accept").contains("json"))
    val r = req(headers = Map("Accept" -> "application/json"))
    assertEquals(RequestMatcher.evaluate(m, r), MatchResult.Matched)

  test("a request with no predicates always matches"):
    assertEquals(RequestMatcher.evaluate(onRequest, req()), MatchResult.Matched)

  test("a near miss reports the failing predicate with expected vs actual"):
    val m = on(GET, "/api/users/1").where(header("Accept").contains("json"))
    RequestMatcher.evaluate(m, req(headers = Map("Accept" -> "text/html"))) match
      case MatchResult.Missed(failures) =>
        assertEquals(failures.size, 1)
        val f = failures.head
        assertEquals(f.field, "headers.Accept")
        assertEquals(f.expected, "json")
        assertEquals(f.actual, Some("text/html"))
      case other => fail(s"expected Missed, got $other")

  test("only the failing predicates are reported, not the satisfied ones"):
    val m = on(GET, "/api/users/1").where(query("page").is("2"))
    RequestMatcher.evaluate(m, req(query = Map("page" -> Vector("3")))) match
      case MatchResult.Missed(failures) => assertEquals(failures.map(_.field), Vector("query.page"))
      case other => fail(s"expected Missed, got $other")

  test("a missing field reports actual = None"):
    val m = on(GET, "/x").where(header("X-Trace").exists)
    RequestMatcher.evaluate(m, req(path = "/x")) match
      case MatchResult.Missed(fs) => assertEquals(fs.head.actual, None)
      case other => fail(s"expected Missed, got $other")

  test("method and path mismatches are reported per field"):
    RequestMatcher.evaluate(on(POST, "/other"), req()) match
      case MatchResult.Missed(fs) => assertEquals(fs.map(_.field).toSet, Set("method", "path"))
      case other => fail(s"expected Missed, got $other")

  test("operators evaluate as documented"):
    val r = req(path = "/api/users/42", headers = Map("A" -> "abcdef"))
    def matches(m: RequestMatch): Boolean = RequestMatcher.evaluate(m, r) == MatchResult.Matched
    assert(matches(onRequest.where(path.matches("""/api/users/\d+"""))))
    assert(matches(onRequest.where(header("A").startsWith("abc"))))
    assert(matches(onRequest.where(header("A").endsWith("def"))))
    assert(matches(onRequest.where(header("A").contains("cde"))))
    assert(matches(onRequest.where(header("A").is("abcdef"))))
    assert(!matches(onRequest.where(header("A").is("nope"))))
    assert(matches(onRequest.where(not(header("Z").exists))))
    assert(matches(onRequest.where(anyOf(header("A").is("x"), header("A").is("abcdef")))))
    assert(!matches(onRequest.where(allOf(header("A").is("x"), header("A").is("abcdef")))))

  test("matching is case-insensitive by default and honours caseSensitive"):
    val r = req(headers = Map("A" -> "ABC"))
    assert(
      RequestMatcher.evaluate(onRequest.where(header("A").is("abc")), r) == MatchResult.Matched
    )
    assert(
      RequestMatcher.evaluate(onRequest.where(header("A").is("abc")).caseSensitive, r) !=
        MatchResult.Matched
    )

  test("jsonPath selectors evaluate against the body"):
    val r = req(body = Json.parse("""{"user":{"role":"admin"}}""").toOption)
    assert(
      RequestMatcher.evaluate(onRequest.where(body.jsonPath("$.user.role").is("admin")), r) ==
        MatchResult.Matched
    )
    assert(
      RequestMatcher.evaluate(onRequest.where(body.jsonPath("$.user.role").is("guest")), r) !=
        MatchResult.Matched
    )

  test("explain reports every recorded request that missed"):
    val m = on(GET, "/api/users/1")
    val report = RequestMatcher.explain(m, Vector(req(), req(path = "/other"), req(method = POST)))
    assertEquals(report.matched.size, 1)
    assertEquals(report.missed.size, 2)

  test("explain renders a human-readable near-miss table naming the failing field"):
    val report = RequestMatcher.explain(on(GET, "/api/users/1"), Vector(req(path = "/other")))
    val rendered = report.render
    assert(rendered.contains("path"), s"report should name the failing field:\n$rendered")
    assert(rendered.contains("/other"), s"report should show the actual value:\n$rendered")

  test("explain over no recorded requests says so rather than rendering an empty table"):
    val report = RequestMatcher.explain(on(GET, "/x"), Vector.empty)
    assertEquals(report.matched.size, 0)
    assert(report.render.nonEmpty)
