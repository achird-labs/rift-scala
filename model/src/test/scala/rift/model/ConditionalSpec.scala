package rift.model

import java.time.Instant
import java.util.Locale

import rift.dsl.*
import rift.json.Json

/** Issue #206 — `_rift.conditional`, engine 0.20.0's declarative conditional GET: an `ETag` and a
  * `Last-Modified` on a 2xx GET/HEAD answer, and a bodyless 304 to a request presenting either
  * back. Mirrors rift-java 0.3.6's `IsSpec.conditional*` and `RiftConditional` (#276), except that
  * an unknown key inside the object form is refused here, where rift-java ignores it.
  */
class ConditionalSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  private def conditionalOf(r: IsResponseBuilder): Option[String] =
    r.build.toJson.get("_rift", "conditional").map(_.render)

  private def ext(s: String) = RiftResponseExt.fromJson(parse(s))

  // ── DSL ────────────────────────────────────────────────────────────────────────────────────
  test("each chainer writes the engine's wire form"):
    assertEquals(conditionalOf(ok.text("x").conditional), Some("true"))
    assertEquals(
      conditionalOf(ok.conditional(Instant.parse("2026-01-05T08:09:10Z"))),
      Some("""{"lastModified":"Mon, 05 Jan 2026 08:09:10 GMT"}""")
    )
    assertEquals(conditionalOf(ok.conditionalWithoutEtag), Some("""{"etag":false}"""))
    assertEquals(
      conditionalOf(ok.conditionalWithoutEtag(Instant.parse("2026-10-17T23:59:59Z"))),
      Some("""{"etag":false,"lastModified":"Sat, 17 Oct 2026 23:59:59 GMT"}""")
    )

  test("a response without a conditional chainer writes no conditional key"):
    assertEquals(conditionalOf(ok.text("x")), None)
    assertEquals(ok.text("x").build.toJson.get("_rift"), None)

  test("the last conditional call wins"):
    assertEquals(conditionalOf(ok.conditionalWithoutEtag.conditional), Some("true"))
    assertEquals(conditionalOf(ok.conditional.conditionalWithoutEtag), Some("""{"etag":false}"""))

  test("the date is an IMF-fixdate: two-digit day, whole seconds, GMT, epoch padded"):
    def date(i: String) = RiftConditional.httpDate(Instant.parse(i))
    assertEquals(date("2026-03-01T00:00:00Z"), "Sun, 01 Mar 2026 00:00:00 GMT")
    assertEquals(date("2026-03-01T12:34:56.789Z"), "Sun, 01 Mar 2026 12:34:56 GMT")
    assertEquals(date("2026-03-01T01:00:00+02:00"), "Sat, 28 Feb 2026 23:00:00 GMT")
    assertEquals(date("1970-01-01T00:00:00Z"), "Thu, 01 Jan 1970 00:00:00 GMT")

  test("the date ignores the JVM's default locale"):
    val saved = Locale.getDefault
    try
      Locale.setDefault(Locale.GERMANY)
      assertEquals(
        RiftConditional.httpDate(Instant.parse("2026-05-03T10:00:00Z")),
        "Sun, 03 May 2026 10:00:00 GMT"
      )
    finally Locale.setDefault(saved)

  test("conditional coexists with the other _rift keys"):
    val json = ok.templated.setState("k", "v").conditional.build.toJson
    assertEquals(json.get("_rift", "templated"), Some(Json.Bool(true)))
    assertEquals(json.get("_rift", "conditional"), Some(Json.Bool(true)))
    assert(json.get("_rift", "stateOps").isDefined)

  // ── model codec ──────────────────────────────────────────────────────────────────────────────
  test("every wire form decodes to the modeled case and re-encodes byte-for-byte"):
    val cases = Seq(
      """true""" -> RiftConditional.Enabled(true),
      """false""" -> RiftConditional.Enabled(false),
      """{}""" -> RiftConditional.Validators(None, None),
      """{"etag":false}""" -> RiftConditional.Validators(Some(false), None),
      """{"lastModified":"load"}""" -> RiftConditional.Validators(None, Some("load")),
      """{"etag":true,"lastModified":"Mon, 05 Jan 2026 08:09:10 GMT"}""" ->
        RiftConditional.Validators(Some(true), Some("Mon, 05 Jan 2026 08:09:10 GMT"))
    )
    for (wire, modeled) <- cases do
      val decoded = ext(s"""{"conditional":$wire}""").fold(e => fail(s"$wire: $e"), identity)
      assertEquals(decoded.conditional, Some(modeled), wire)
      assertEquals(decoded.extra, Vector.empty, s"$wire leaked into extra")
      assertEquals(decoded.toJson.get("conditional").map(_.render), Some(wire), wire)

  test("a malformed conditional is a decode error under conditional, not an extra"):
    for wire <- Seq(
        "1",
        "\"x\"",
        "[]",
        """{"etag":"no"}""",
        """{"lastModified":5}""",
        """{"etag":false,"etg":true}"""
      )
    do
      assertEquals(
        ext(s"""{"conditional":$wire}""").left.map(_.path.headOption),
        Left(Some("conditional")),
        wire
      )
