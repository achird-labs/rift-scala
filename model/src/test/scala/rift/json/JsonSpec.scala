package rift.json

class JsonSpec extends munit.FunSuite:

  test("parses scalars"):
    assertEquals(Json.parse("null"), Right(Json.Null))
    assertEquals(Json.parse("true"), Right(Json.Bool(true)))
    assertEquals(Json.parse("\"hi\""), Right(Json.Str("hi")))
    assertEquals(Json.parse("1"), Right(Json.Num(BigDecimal(1))))
    assertEquals(Json.parse("-1.5e2"), Right(Json.Num(BigDecimal("-1.5e2"))))

  test("parses nested structures preserving object key order"):
    val parsed = Json.parse("""{"b":1,"a":[1,{"z":null}]}""")
    assertEquals(
      parsed,
      Right(
        Json.Obj(
          Vector(
            "b" -> Json.Num(BigDecimal(1)),
            "a" -> Json.Arr(Vector(Json.Num(BigDecimal(1)), Json.Obj(Vector("z" -> Json.Null))))
          )
        )
      )
    )

  test("parses string escapes including surrogate pairs"):
    assertEquals(Json.parse("\"a\\nb\""), Right(Json.Str("a\nb")))
    assertEquals(Json.parse("\"\\u0041\""), Right(Json.Str("A")))
    assertEquals(Json.parse("\"\\\"\\\\\\/\\b\\f\\r\\t\""), Right(Json.Str("\"\\/\b\f\r\t")))
    // G-clef U+1D11E as a surrogate pair
    assertEquals(Json.parse("\"\\uD834\\uDD1E\""), Right(Json.Str("𝄞")))

  test("rejects duplicate object keys"):
    assert(Json.parse("""{"a":1,"a":2}""").isLeft)

  test("rejects malformed input rather than throwing"):
    List("", "{", "[1,]", "{\"a\"}", "tru", "01", "\"unterminated", "{\"a\":1}x").foreach: bad =>
      assert(Json.parse(bad).isLeft, s"expected parse failure for: $bad")

  test("render round-trips through parse"):
    val text = """{"a":[1,2.5,true,null,"x"],"b":{"c":"d"}}"""
    assertEquals(Json.parse(text).map(_.render), Right(text))

  test("render escapes control characters"):
    assertEquals(Json.Str("a\nb\"c").render, "\"a\\nb\\\"c\"")

  test("renderPretty re-parses to an equal value"):
    val v = Json.parse("""{"a":[1,{"b":null}],"c":true}""").toOption.get
    assertEquals(Json.parse(v.renderPretty), Right(v))

  test("semanticEquals ignores object key order"):
    val a = Json.parse("""{"x":1,"y":2}""").toOption.get
    val b = Json.parse("""{"y":2,"x":1}""").toOption.get
    assert(a.semanticEquals(b))
    assert(!a.equals(b), "structural equality is order-sensitive; semanticEquals is not")

  test("semanticEquals treats 1 and 1.0 as equal"):
    assert(Json.parse("1").toOption.get.semanticEquals(Json.parse("1.0").toOption.get))
    assert(Json.parse("1e2").toOption.get.semanticEquals(Json.parse("100").toOption.get))

  test("semanticEquals is order-sensitive for arrays"):
    val a = Json.parse("[1,2]").toOption.get
    val b = Json.parse("[2,1]").toOption.get
    assert(!a.semanticEquals(b))

  test("semanticEquals recurses through nesting"):
    val a = Json.parse("""{"o":{"p":1,"q":[{"r":1.0}]}}""").toOption.get
    val b = Json.parse("""{"o":{"q":[{"r":1}],"p":1.00}}""").toOption.get
    assert(a.semanticEquals(b))

  test("semanticEquals distinguishes absent from null"):
    val a = Json.parse("""{"x":null}""").toOption.get
    val b = Json.parse("""{}""").toOption.get
    assert(!a.semanticEquals(b))

  test("get navigates object paths"):
    val v = Json.parse("""{"a":{"b":{"c":42}}}""").toOption.get
    assertEquals(v.get("a", "b", "c"), Some(Json.Num(BigDecimal(42))))
    assertEquals(v.get("a", "nope"), None)
    assertEquals(v.get(), Some(v))

  test("obj rejects duplicate keys at construction"):
    intercept[IllegalArgumentException](Json.obj("a" -> Json.Null, "a" -> Json.Null))

  test("obj and arr build the expected shapes"):
    assertEquals(Json.obj("a" -> Json.Null), Json.Obj(Vector("a" -> Json.Null)))
    assertEquals(Json.arr(Json.Null), Json.Arr(Vector(Json.Null)))

  test("deeply nested input does not overflow the stack"):
    val deep = "[" * 2000 + "]" * 2000
    assert(Json.parse(deep).isRight || Json.parse(deep).isLeft, "must terminate without throwing")
