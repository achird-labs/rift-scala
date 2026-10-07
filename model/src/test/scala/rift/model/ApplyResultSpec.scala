package rift.model

import rift.json.Json

/** The engine's apply report (`POST /admin/reload`, `PUT /imposters`, `rift_apply_config`) as
  * engine 0.20.0 writes it: port arrays, `toggled`, failures, warnings and intercept counts. Issue
  * #207 — the report used to be four counts and raw failures (rift-java ≤ 0.3.4).
  */
class ApplyResultSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)
  private def decode(s: String): ApplyResult =
    ApplyResult.fromJson(parse(s)).fold(e => fail(e.toString), identity)
  private def port(n: Int): Port = Port.from(n).fold(e => fail(e), identity)

  test("a full 0.20 report decodes every list into its own field"):
    val r = decode(
      """{"created":[4545],"replaced":[4546,4547],"stubPatched":[4548],"toggled":[4549],
        |"deleted":[4550],"failed":[],"warnings":["w1"],
        |"intercept":{"rulesSeeded":2,"rulesRuntime":3}}""".stripMargin
    )
    assertEquals(r.created, Vector(port(4545)))
    assertEquals(r.replaced, Vector(port(4546), port(4547)))
    assertEquals(r.stubPatched, Vector(port(4548)))
    assertEquals(r.toggled, Vector(port(4549)))
    assertEquals(r.deleted, Vector(port(4550)))
    assertEquals(r.warnings, Vector("w1"))
    assertEquals(r.intercept, Some(InterceptCounts(2, 3)))

  test("a report from before engine 0.20.0 has no toggled, warnings or intercept"):
    val r = decode("""{"created":[4545],"replaced":[],"stubPatched":[],"deleted":[]}""")
    assertEquals(r.toggled, Vector.empty)
    assertEquals(r.warnings, Vector.empty)
    assertEquals(r.intercept, None)
    assertEquals(r.failed, Vector.empty)

  test("a failure object keeps its port and message; port 0 means auto-assigned"):
    val r = decode(
      """{"failed":[{"port":4545,"error":"bad stub"},{"port":0,"error":"no port"}]}"""
    )
    assertEquals(
      r.failed,
      Vector(ApplyFailure(Some(port(4545)), "bad stub"), ApplyFailure(None, "no port"))
    )

  test("the engine's string renderings of a failure are parsed the way rift-java parses them"):
    val r = decode(
      """{"failed":["4545: bad stub","auto-assign: no port","something else: 1","0: zero",
        |"70000: too big","4546: "]}""".stripMargin
    )
    assertEquals(
      r.failed,
      Vector(
        ApplyFailure(Some(port(4545)), "bad stub"),
        ApplyFailure(None, "no port"),
        ApplyFailure(None, "something else: 1"),
        ApplyFailure(None, "zero"),
        ApplyFailure(None, "70000: too big"),
        ApplyFailure(Some(port(4546)), "")
      )
    )

  test("changedNothing is false when any one of the five lists has a port"):
    for key <- Seq("created", "replaced", "stubPatched", "toggled", "deleted") do
      assert(!decode(s"""{"$key":[4545]}""").changedNothing, key)

  test("changedNothing ignores failures and warnings, and holds for an empty report"):
    assert(decode("{}").changedNothing)
    assert(decode("""{"failed":[{"port":4545,"error":"x"}],"warnings":["w"]}""").changedNothing)

  test("a malformed report is a decode error naming the field, not a silent default"):
    def path(s: String) = ApplyResult.fromJson(parse(s)).left.map(_.path)
    assertEquals(path("""{"toggled":4545}"""), Left(Vector("toggled")))
    assertEquals(path("""{"created":["x"]}"""), Left(Vector("created", "0")))
    assertEquals(path("""{"created":[0]}"""), Left(Vector("created", "0")))
    assertEquals(path("""{"failed":[7]}"""), Left(Vector("failed", "0")))
    assertEquals(path("""{"warnings":[1]}"""), Left(Vector("warnings", "0")))
    assertEquals(
      path("""{"intercept":{"rulesSeeded":1}}"""),
      Left(Vector("intercept", "rulesRuntime"))
    )
    assertEquals(path("""{"intercept":[]}"""), Left(Vector("intercept")))
    assertEquals(path("""{"created":[70000]}"""), Left(Vector("created", "0")))
    assertEquals(path("""{"created":[4545.5]}"""), Left(Vector("created", "0")))
    assertEquals(path("""{"toggled":null}"""), Left(Vector("toggled")))
    assertEquals(path("""{"failed":{}}"""), Left(Vector("failed")))
    assertEquals(path("""{"warnings":"w"}"""), Left(Vector("warnings")))
    assertEquals(
      path("""{"failed":[{"port":70000,"error":"x"}]}"""),
      Left(Vector("failed", "0", "port"))
    )
    assertEquals(path("""{"failed":[{"port":4545}]}"""), Left(Vector("failed", "0", "error")))
    assertEquals(path("""{"failed":[{"error":"e"}]}"""), Left(Vector("failed", "0", "port")))
    assertEquals(
      path("""{"failed":[{"port":"x","error":"e"}]}"""),
      Left(Vector("failed", "0", "port"))
    )
    assert(ApplyResult.fromJson(parse("[]")).isLeft)
    assert(ApplyResult.fromJson(parse("\"x\"")).isLeft)

  test("toJson writes the engine's wire shape"):
    val r = ApplyResult(
      created = Vector(port(4545)),
      replaced = Vector.empty,
      stubPatched = Vector.empty,
      toggled = Vector(port(4549)),
      deleted = Vector.empty,
      failed = Vector(ApplyFailure(Some(port(4546)), "bad"), ApplyFailure(None, "auto")),
      warnings = Vector("w"),
      intercept = Some(InterceptCounts(1, 0))
    )
    assertEquals(
      r.toJson,
      parse(
        """{"created":[4545],"replaced":[],"stubPatched":[],"toggled":[4549],"deleted":[],
          |"failed":[{"port":4546,"error":"bad"},{"port":0,"error":"auto"}],"warnings":["w"],
          |"intercept":{"rulesSeeded":1,"rulesRuntime":0}}""".stripMargin
      )
    )
    assertEquals(ApplyResult.fromJson(r.toJson), Right(r))

  test("an empty report without intercept counts writes no intercept key and round-trips"):
    val empty =
      ApplyResult(
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        None
      )
    assertEquals(
      empty.toJson,
      parse(
        """{"created":[],"replaced":[],"stubPatched":[],"toggled":[],"deleted":[],"failed":[],
          |"warnings":[]}""".stripMargin
      )
    )
    assertEquals(ApplyResult.fromJson(empty.toJson), Right(empty))
