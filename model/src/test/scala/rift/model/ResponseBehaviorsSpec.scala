package rift.model

import rift.json.Json

/** Issue #159 — where a response's behaviors live on the wire. The engine's `GET /imposters` writes
  * them as a `behaviors` array beside `is` (never an array under `_behaviors`, which it refuses),
  * with `repeat` hoisted to the response itself. Before #159 the model read only `_behaviors`, so
  * every GET payload's behaviors hid in `extra`: they round-tripped by accident, and anything
  * reading the typed model saw none.
  */
class ResponseBehaviorsSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  private def decodeIs(raw: String): Response.Is =
    Response.fromJson(parse(raw)) match
      case Right(is: Response.Is) => is
      case other => fail(s"expected an is-response from $raw, got $other")

  private val copyP =
    """{"from":"path","into":"${P}","using":{"method":"regex","selector":".+"}}"""

  // The shape the engine's docs show `GET /imposters` writing (docs/mountebank/behaviors.md).
  private val getShaped =
    s"""{"repeat":3,"behaviors":[{"wait":500},{"copy":$copyP},{"shellTransform":"echo x"}],"is":{"statusCode":200,"body":"Hello $${P}"}}"""

  test("a GET-shaped response exposes its behaviors and repeat as typed entries"):
    val is = decodeIs(getShaped)
    assertEquals(
      is.behaviors.entries,
      Vector(
        Behavior.Wait(WaitBehavior.Fixed(500L)),
        Behavior.Copy(Vector(parse(copyP)), bare = true),
        Behavior.ShellTransform(Vector("echo x"), bare = true),
        Behavior.Repeat(3, responseLevel = true)
      )
    )
    assertEquals(is.extra, Vector.empty)
    assertEquals(is.behaviors.effectiveRepeat, Some(3))

  test("a GET-shaped response writes back exactly what it read"):
    val written = decodeIs(getShaped).toJson
    assert(written.semanticEquals(parse(getShaped)), written.render)
    assertEquals(written.get("repeat"), Some(Json.Num(BigDecimal(3))))
    assertEquals(written.get("_behaviors"), None)

  test("both repeat spellings are kept, and the response-level one takes effect"):
    val raw = """{"is":{"statusCode":200},"repeat":3,"_behaviors":{"repeat":2}}"""
    val is = decodeIs(raw)
    assertEquals(
      is.behaviors.entries,
      Vector(Behavior.Repeat(2, responseLevel = false), Behavior.Repeat(3, responseLevel = true))
    )
    assertEquals(is.behaviors.effectiveRepeat, Some(3))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  test("the last block repeat takes effect when there is no response-level one"):
    val is = decodeIs("""{"is":{"statusCode":200},"behaviors":[{"repeat":1},{"repeat":2}]}""")
    assertEquals(is.behaviors.effectiveRepeat, Some(2))

  test("_behaviors wins over behaviors, and the losing key rides along untouched"):
    val raw = """{"is":{"statusCode":200},"_behaviors":{"wait":1},"behaviors":[{"wait":2}]}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(1L))))
    assertEquals(is.extra, Vector("behaviors" -> parse("""[{"wait":2}]""")))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  test("a null _behaviors counts as absent, so behaviors is read"):
    val raw = """{"is":{"statusCode":200},"_behaviors":null,"behaviors":[{"wait":2}]}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(2L))))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  test("a non-integer response-level repeat is a decode error, not an opaque extra"):
    assert(Response.fromJson(parse("""{"is":{"statusCode":200},"repeat":"3"}""")).isLeft)
    assert(Response.fromJson(parse("""{"is":{"statusCode":200},"repeat":1.5}""")).isLeft)

  test("a null response-level repeat counts as absent and round-trips"):
    val raw = """{"is":{"statusCode":200},"repeat":null,"_behaviors":{"wait":1}}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(1L))))
    assertEquals(is.behaviors.effectiveRepeat, None)
    assertEquals(is.extra, Vector("repeat" -> Json.Null))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  test("a modeled repeat and an extra repeat cannot both be written"):
    val bad = Response.Is(
      IsResponse(statusCode = Some(200)),
      behaviors = Behaviors.of(Behavior.Repeat(3, responseLevel = true)),
      extra = Vector("repeat" -> Json.Num(1))
    )
    intercept[IllegalArgumentException](bad.toJson)

  // An empty `_behaviors` still wins in the engine, so it keeps shadowing a `behaviors` array.
  // Dropping it on write would switch that array on after nothing but a read and a write.
  test("an empty _behaviors keeps shadowing a behaviors array"):
    List(
      """{"is":{"statusCode":200},"_behaviors":{},"behaviors":[{"wait":1}]}""",
      """{"is":{"statusCode":200},"_behaviors":[],"behaviors":[{"wait":1}]}"""
    ).foreach: raw =>
      val is = decodeIs(raw)
      assertEquals(is.behaviors.entries, Vector.empty, raw)
      assertEquals(is.toJson.render, parse(raw).render)

  test("an empty or null block, or a null repeat beside a block repeat, round-trips as read"):
    List(
      """{"is":{"statusCode":200},"_behaviors":{}}""" -> Vector.empty,
      """{"is":{"statusCode":200},"_behaviors":null}""" -> Vector.empty,
      """{"is":{"statusCode":200},"_behaviors":{"repeat":2},"repeat":null}""" ->
        Vector(Behavior.Repeat(2, responseLevel = false))
    ).foreach: (raw, entries) =>
      val is = decodeIs(raw)
      assertEquals(is.behaviors.entries, entries, raw)
      assertEquals(is.toJson.render, parse(raw).render)

  test("an array under _behaviors beside behaviors round-trips without throwing"):
    val raw = """{"is":{"statusCode":200},"_behaviors":[{"wait":1}],"behaviors":[{"wait":2}]}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(1L))))
    assertEquals(is.toJson.render, parse(raw).render)

  // The engine refuses an array under `_behaviors`, so a lone one is written back where it runs.
  test("a lone array under _behaviors is written back under behaviors"):
    val is = decodeIs("""{"is":{"statusCode":200},"_behaviors":[{"wait":1}]}""")
    assertEquals(
      is.toJson.render,
      """{"is":{"statusCode":200},"behaviors":[{"wait":1}]}"""
    )

  test("corpus fixture 20's behaviors arrays now decode as typed entries"):
    val text = scala.util.Using.resource(
      scala.io.Source.fromResource("corpus/20-migration-compat.json")
    )(_.mkString)
    val imposter = ImposterDefinition.fromJson(parse(text)).fold(e => fail(e.toString), identity)
    val keys = imposter.stubs.flatMap(_.responses).collect { case is: Response.Is =>
      is.behaviors.entries.map(_.key)
    }
    assert(keys.contains(Vector("wait", "decorate")), keys.toString)
    assert(keys.contains(Vector("wait")), keys.toString)
    val extras = imposter.stubs.flatMap(_.responses).collect { case is: Response.Is =>
      is.extra.map(_._1)
    }
    assert(extras.forall(!_.contains("behaviors")), extras.toString)

  test("an object block with a repeated key writes the behaviors array instead of losing one"):
    val b = Behaviors(
      Vector(Behavior.Decorate("a"), Behavior.Decorate("b")),
      Behaviors.Spelling.Object
    )
    val written = Response.Is(IsResponse(statusCode = Some(200)), behaviors = b).toJson
    assertEquals(written.get("_behaviors"), None)
    assertEquals(
      written.get("behaviors").map(_.render),
      Some("""[{"decorate":"a"},{"decorate":"b"}]""")
    )
