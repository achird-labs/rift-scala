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

  test("a block repeat in the behaviors array and a response-level repeat are both kept"):
    val raw = """{"is":{"statusCode":200},"repeat":3,"behaviors":[{"repeat":2}]}"""
    val is = decodeIs(raw)
    assertEquals(
      is.behaviors.entries,
      Vector(Behavior.Repeat(2, responseLevel = false), Behavior.Repeat(3, responseLevel = true))
    )
    assertEquals(is.behaviors.effectiveRepeat, Some(3))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  // #222 — engine rift#1738 (rift#1709) follows Mountebank's upcast rule: `_behaviors` runs only
  // when the response sets no `behaviors` (a `null` one included) and no top-level `repeat`; beside
  // either it is ignored whole, its own `repeat` included. The decoder follows the same rule, and
  // an ignored `_behaviors` rides on `extra` untouched, so a read -> write reproduces the document.
  test("a top-level repeat voids _behaviors whole, its block repeat included"):
    val raw = """{"is":{"statusCode":200},"repeat":3,"_behaviors":{"wait":1,"repeat":2}}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Repeat(3, responseLevel = true)))
    assertEquals(is.behaviors.effectiveRepeat, Some(3))
    assertEquals(is.extra, Vector("_behaviors" -> parse("""{"wait":1,"repeat":2}""")))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  test("a null behaviors voids _behaviors, and both keys ride along untouched"):
    val raw = """{"is":{"statusCode":200},"behaviors":null,"_behaviors":{"wait":1}}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector.empty)
    assertEquals(is.extra.map(_._1).toSet, Set("behaviors", "_behaviors"))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  test("a response-level repeat writes the block as the behaviors array, never beside _behaviors"):
    val is = Response.Is(
      IsResponse(statusCode = Some(200)),
      behaviors = Behaviors.of(
        Behavior.Wait(WaitBehavior.Fixed(5L)),
        Behavior.Repeat(3, responseLevel = true)
      )
    )
    assertEquals(
      is.toJson.render,
      """{"is":{"statusCode":200},"behaviors":[{"wait":5}],"repeat":3}"""
    )

  test("beside a response-level repeat a multi-entry step is also expanded one element each"):
    val is = Response.Is(
      IsResponse(statusCode = Some(200)),
      behaviors = Behaviors.of(
        Behavior.ShellTransform(Vector("a", "b"), bare = false),
        Behavior.Repeat(2, responseLevel = true)
      )
    )
    assertEquals(
      is.toJson.render,
      """{"is":{"statusCode":200},"behaviors":[{"shellTransform":"a"},{"shellTransform":"b"}],"repeat":2}"""
    )

  test("a response-level repeat with no block writes the repeat alone"):
    val is = Response.Is(
      IsResponse(statusCode = Some(200)),
      behaviors = Behaviors.of(Behavior.Repeat(4, responseLevel = true))
    )
    assertEquals(is.toJson.render, """{"is":{"statusCode":200},"repeat":4}""")

  test("the last block repeat takes effect when there is no response-level one"):
    val is = decodeIs("""{"is":{"statusCode":200},"behaviors":[{"repeat":1},{"repeat":2}]}""")
    assertEquals(is.behaviors.effectiveRepeat, Some(2))

  test("behaviors wins over _behaviors, and the ignored key rides along untouched"):
    val raw = """{"is":{"statusCode":200},"_behaviors":{"wait":1},"behaviors":[{"wait":2}]}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(2L))))
    assertEquals(is.extra, Vector("_behaviors" -> parse("""{"wait":1}""")))
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

  // Since rift#1738 a `behaviors` array runs beside any `_behaviors`, empty or not; the ignored
  // `_behaviors` is carried, not dropped, so the document comes back as it was read.
  test("a behaviors array runs beside an empty _behaviors, which rides along"):
    List(
      """{"is":{"statusCode":200},"_behaviors":{},"behaviors":[{"wait":1}]}""",
      """{"is":{"statusCode":200},"_behaviors":[],"behaviors":[{"wait":1}]}"""
    ).foreach: raw =>
      val is = decodeIs(raw)
      assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(1L))), raw)
      assertEquals(is.extra.map(_._1), Vector("_behaviors"), raw)
      assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

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
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(2L))))
    assert(is.toJson.semanticEquals(parse(raw)), is.toJson.render)

  // The engine refuses an object under `behaviors`; beside an ignored `_behaviors` the block is
  // written back as the array under `behaviors`, the one spelling that runs there.
  test("an object read from behaviors beside _behaviors is written back as the behaviors array"):
    val raw = """{"is":{"statusCode":200},"behaviors":{"wait":2},"_behaviors":{"wait":1}}"""
    val is = decodeIs(raw)
    assertEquals(is.behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(2L))))
    assertEquals(
      is.toJson.render,
      """{"is":{"statusCode":200},"behaviors":[{"wait":2}],"_behaviors":{"wait":1}}"""
    )

  // Editing a decoded document whose `_behaviors` the engine ignores: the new block must land
  // where it runs, never collide with the carried key or go under the ignored `_behaviors`.
  private def withBlock(raw: String, block: Behavior*): Json =
    val is = decodeIs(raw)
    is.copy(behaviors = Behaviors.of(block*)).toJson

  test("setting a block beside a carried behaviors placeholder replaces it with the array"):
    List(
      """{"is":{"statusCode":200},"behaviors":null,"_behaviors":{"wait":1}}""",
      """{"is":{"statusCode":200},"behaviors":[],"_behaviors":{"wait":1}}"""
    ).foreach: raw =>
      assertEquals(
        withBlock(raw, Behavior.Wait(WaitBehavior.Fixed(9L))).render,
        """{"is":{"statusCode":200},"behaviors":[{"wait":9}],"_behaviors":{"wait":1}}""",
        raw
      )

  test("setting an object block on a document with an ignored _behaviors writes the array"):
    val raw = """{"is":{"statusCode":200},"repeat":3,"_behaviors":{"wait":1}}"""
    assertEquals(
      withBlock(raw, Behavior.Wait(WaitBehavior.Fixed(9L))).render,
      """{"is":{"statusCode":200},"behaviors":[{"wait":9}],"_behaviors":{"wait":1}}"""
    )

  test("an empty block beside a carried behaviors placeholder leaves the document as read"):
    val raw = """{"is":{"statusCode":200},"behaviors":null,"_behaviors":{"wait":1}}"""
    assertEquals(withBlock(raw).render, parse(raw).render)

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

  // ── Issue #173 — behaviors on every response type (engine 0.18.0, rift#1188) ───────────────────
  // The engine runs behaviors on proxy and inject responses and honours `repeat` on every type. The
  // block is read and written with exactly the rules an is-response uses.
  private def roundTrip(raw: String): Response =
    val decoded = Response.fromJson(parse(raw)).fold(e => fail(s"$raw: $e"), identity)
    assertEquals(decoded.toJson.render, parse(raw).render)
    decoded

  test("a proxy response carries a typed behaviors block"):
    roundTrip("""{"proxy":{"to":"http://o","mode":"proxyOnce"},"_behaviors":{"wait":100}}""") match
      case Response.Proxy(_, behaviors, extra) =>
        assertEquals(behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(100L))))
        assertEquals(extra, Vector.empty)
      case other => fail(s"expected a proxy response, got $other")

  test("an inject response carries the array form and a response-level repeat"):
    roundTrip("""{"inject":"f","behaviors":[{"decorate":"a"},{"decorate":"b"}],"repeat":2}""") match
      case Response.Inject(_, behaviors, extra) =>
        assertEquals(
          behaviors.entries,
          Vector(
            Behavior.Decorate("a"),
            Behavior.Decorate("b"),
            Behavior.Repeat(2, responseLevel = true)
          )
        )
        assertEquals(extra, Vector.empty)
      case other => fail(s"expected an inject response, got $other")

  test("a fault response carries repeat in either spelling"):
    roundTrip("""{"fault":"CONNECTION_RESET_BY_PEER","repeat":2}""") match
      case Response.Fault(_, behaviors, _) =>
        assertEquals(behaviors.effectiveRepeat, Some(2))
      case other => fail(s"expected a fault response, got $other")
    roundTrip("""{"fault":"CONNECTION_RESET_BY_PEER","_behaviors":{"repeat":3}}""") match
      case Response.Fault(_, behaviors, _) =>
        assertEquals(behaviors.effectiveRepeat, Some(3))
      case other => fail(s"expected a fault response, got $other")

  test("a rift-script response carries a repeat block"):
    roundTrip(
      """{"_rift":{"script":{"engine":"rhai","code":"1"}},"_behaviors":{"repeat":2}}"""
    ) match
      case Response.RiftScript(_, behaviors, _) =>
        assertEquals(behaviors.entries, Vector(Behavior.Repeat(2, responseLevel = false)))
      case other => fail(s"expected a rift-script response, got $other")

  test("the block rules hold on every type: behaviors runs beside an ignored _behaviors"):
    val raw =
      """{"proxy":{"to":"http://o","mode":"proxyAlways"},"_behaviors":{},"behaviors":[{"wait":1}]}"""
    val decoded = Response.fromJson(parse(raw)).fold(e => fail(s"$raw: $e"), identity)
    assert(decoded.toJson.semanticEquals(parse(raw)), decoded.toJson.render)
    decoded match
      case Response.Proxy(_, behaviors, extra) =>
        assertEquals(behaviors.entries, Vector(Behavior.Wait(WaitBehavior.Fixed(1L))))
        assertEquals(extra.map(_._1), Vector("_behaviors"))
      case other => fail(s"expected a proxy response, got $other")

  test("the block rules hold on every type: a fault's top-level repeat voids _behaviors"):
    val raw = """{"fault":"CONNECTION_RESET_BY_PEER","repeat":2,"_behaviors":{"repeat":5}}"""
    val decoded = Response.fromJson(parse(raw)).fold(e => fail(s"$raw: $e"), identity)
    assertEquals(decoded.toJson.render, parse(raw).render)
    decoded match
      case Response.Fault(_, behaviors, extra) =>
        assertEquals(behaviors.effectiveRepeat, Some(2))
        assertEquals(extra.map(_._1), Vector("_behaviors"))
      case other => fail(s"expected a fault response, got $other")
