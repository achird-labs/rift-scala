package rift.json.circe

import io.circe.{Codec, Json as CJson, JsonNumber}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}
import rift.json.Json
import rift.model.JsonBody

/** The gate for issue #17 (DESIGN.md §5.9).
  *
  * Mirrors `ZioJsonBodySpec` (#16) so the two side-cars stay behaviourally identical on the shared
  * `JsonBody` seam. No cats-effect / ZIO type appears: this artifact is usable from any backend.
  */
class CirceJsonBodySpec extends ScalaCheckSuite:

  // ── fixtures ────────────────────────────────────────────────────────────────
  // `derives` rather than circe-generic's `deriveCodec`: circe-core ships Scala 3 derivation, and
  // the README promises this artifact needs nothing but circe-core. Deriving here proves it — the
  // module would not compile if circe-generic were actually required.
  case class User(id: Int, name: String, tags: List[String]) derives Codec.AsObject

  enum Status derives Codec.AsObject:
    case Active
    case Suspended(reason: String)

  given Arbitrary[User] = Arbitrary(
    for
      id <- Arbitrary.arbitrary[Int]
      name <- Arbitrary.arbitrary[String]
      tags <- Gen.listOf(Arbitrary.arbitrary[String])
    yield User(id, name, tags)
  )

  given Arbitrary[Status] = Arbitrary(
    Gen.oneOf(
      Gen.const(Status.Active),
      Arbitrary.arbitrary[String].map(Status.Suspended.apply)
    )
  )

  /** Generates the model AST. Keys stay distinct (the model rejects duplicates) and container sizes
    * start at 0 so empty objects/arrays are covered.
    */
  private val genJson: Gen[Json] =
    def loop(depth: Int): Gen[Json] =
      val scalars: Gen[Json] = Gen.oneOf(
        Gen.const(Json.Null),
        Arbitrary.arbitrary[Boolean].map(Json.Bool.apply),
        Arbitrary.arbitrary[String].map(Json.Str.apply),
        Gen.oneOf(
          Arbitrary.arbitrary[Long].map(l => Json.Num(BigDecimal(l))),
          Gen.oneOf("0.1", "1.0", "1.000", "-1.5e2", "1e-8").map(s => Json.Num(BigDecimal(s)))
        )
      )
      if depth <= 0 then scalars
      else
        Gen.frequency(
          3 -> scalars,
          1 -> Gen
            .choose(0, 3)
            .flatMap(Gen.listOfN(_, loop(depth - 1)))
            .map(items => Json.Arr(items.toVector)),
          1 -> Gen
            .choose(0, 3)
            .flatMap(Gen.listOfN(_, Gen.zip(Gen.identifier, loop(depth - 1))))
            .map(fields => Json.Obj(fields.distinctBy(_._1).toVector))
        )
    loop(3)

  given Arbitrary[Json] = Arbitrary(genJson)

  // ── AC1: round-trip A -> encode -> decode via derived codecs ────────────────
  property("round-trips a derived case-class codec"):
    forAll: (u: User) =>
      val jb = summon[JsonBody[User]]
      assertEquals(jb.decode(jb.encode(u)), Right(u))

  property("round-trips a derived enum codec"):
    forAll: (s: Status) =>
      val jb = summon[JsonBody[Status]]
      assertEquals(jb.decode(jb.encode(s)), Right(s))

  // ── AC2: Json <-> io.circe.Json converters ─────────────────────────────────
  property("Json -> CJson -> Json is the identity"):
    forAll: (j: Json) =>
      assertEquals(j.toCirceJson.toRiftJson, j)

  test("converts object key order faithfully (both directions)"):
    val j = Json.Obj(Vector("b" -> Json.Num(BigDecimal(1)), "a" -> Json.Str("x")))
    assertEquals(j.toCirceJson.toRiftJson, j)
    assertEquals(j.toCirceJson, CJson.obj("b" -> CJson.fromInt(1), "a" -> CJson.fromString("x")))

  test("preserves BigDecimal precision and scale"):
    val precise = BigDecimal("123456789012345678901234567890.123456789")
    val j = Json.Num(precise)
    assertEquals(j.toCirceJson.toRiftJson, j)
    assertEquals(j.toCirceJson.toRiftJson.asInstanceOf[Json.Num].value.toString, precise.toString)

  /** circe's own `toBigDecimal` attaches the default `MathContext` (34 digits), which `toString`
    * and `scale` cannot see — it only bites on the *next* arithmetic op. Assert the context
    * survives, so this side-car keeps agreeing with the zio-json one.
    */
  test("preserves the MathContext so later arithmetic does not round"):
    val precise = BigDecimal("123456789012345678901234567890123456789") // 39 digits > default 34
    val round = Json.Num(precise).toCirceJson.toRiftJson.asInstanceOf[Json.Num].value
    assertEquals(round.mc.getPrecision, precise.mc.getPrecision)
    assertEquals((round + BigDecimal(0)).toString, (precise + BigDecimal(0)).toString)

  /** `BigDecimal.==` compares by value (`1.0 == 1.00`), so the identity property cannot see scale
    * drift. Assert on the scale itself.
    */
  test("preserves trailing-zero scale exactly"):
    List("1.0", "1.000", "0.10", "1e-8").foreach: s =>
      val original = BigDecimal(s)
      val round = Json.Num(original).toCirceJson.toRiftJson.asInstanceOf[Json.Num].value
      assertEquals(round.scale, original.scale, s"scale drifted for $s")
      assertEquals(round.toString, original.toString, s"representation drifted for $s")

  test("round-trips empty objects and arrays"):
    val empties = Json.Obj(Vector("arr" -> Json.Arr(Vector.empty), "obj" -> Json.Obj(Vector.empty)))
    assertEquals(empties.toCirceJson.toRiftJson, empties)
    assertEquals(Json.Arr(Vector.empty).toCirceJson.toRiftJson, Json.Arr(Vector.empty))
    assertEquals(Json.Obj(Vector.empty).toCirceJson.toRiftJson, Json.Obj(Vector.empty))

  test("round-trips unicode and escaped strings"):
    List("a\"b\\c", "line\nbreak\ttab", "emoji 😀 astral", " control", "üñïçø∂é").foreach: s =>
      assertEquals(Json.Str(s).toCirceJson.toRiftJson, Json.Str(s))

  test("maps every AST case in both directions"):
    val all = Json.Obj(
      Vector(
        "null" -> Json.Null,
        "bool" -> Json.Bool(true),
        "str" -> Json.Str("s"),
        "num" -> Json.Num(BigDecimal("1.5")),
        "arr" -> Json.Arr(Vector(Json.Null, Json.Bool(false))),
        "obj" -> Json.Obj(Vector("k" -> Json.Str("v")))
      )
    )
    assertEquals(all.toCirceJson.toRiftJson, all)

  // ── a circe number with no BigDecimal representation has no model counterpart ─
  /** circe keeps a parsed number lazily as its source text, so a literal whose scale overflows
    * `BigDecimal`'s `Int` scale lives happily in circe's AST but has no `Num(BigDecimal)` to map
    * to. This is reachable straight from the parser, i.e. from real wire data — not a synthetic
    * value.
    */
  test("toRiftJson rejects a parsed number that cannot be a BigDecimal"):
    val huge = io.circe.parser
      .parse("1e9999999999")
      .fold(e => fail(s"circe should parse this: $e"), identity)
    assert(huge.asNumber.flatMap(_.toBigDecimal).isEmpty, "precondition: circe cannot widen this")
    intercept[IllegalArgumentException](huge.toRiftJson)

  test("toRiftJson rejects an unrepresentable number built directly"):
    val huge = CJson.fromJsonNumber(JsonNumber.fromDecimalStringUnsafe("1e2147483649"))
    intercept[IllegalArgumentException](huge.toRiftJson)

  /** The boundary: scale `Int.MinValue` still fits, so this one must convert rather than throw. */
  test("toRiftJson accepts the largest still-representable exponent"):
    val edge = CJson.fromJsonNumber(JsonNumber.fromDecimalStringUnsafe("1e2147483648"))
    assertEquals(edge.toRiftJson.asInstanceOf[Json.Num].value.scale, Int.MinValue)

  test("a parsed number that IS representable converts"):
    val parsed = io.circe.parser
      .parse("""{"n":1e10}""")
      .fold(e => fail(s"circe should parse: $e"), identity)
    assertEquals(parsed.toRiftJson, Json.Obj(Vector("n" -> Json.Num(BigDecimal("1e10")))))

  // ── duplicate keys: circe collapses them, the model rejects them ────────────
  test("circe's own parser already collapses duplicate keys (last wins)"):
    val parsed = io.circe.parser
      .parse("""{"a":1,"a":2}""")
      .fold(e => fail(s"circe should parse: $e"), identity)
    // Documents circe's behaviour: unlike zio-json, no duplicate ever reaches `toRiftJson`.
    assertEquals(parsed.toRiftJson, Json.Obj(Vector("a" -> Json.Num(BigDecimal(2)))))

  /** The forward direction is where circe would lose data: `CJson.fromFields` keeps the last value
    * at the *first* key's position, so a naive conversion drops a field AND reorders. Fail loudly
    * instead — the model rejects such an `Obj` at its own entry points anyway.
    */
  test("toCirceJson rejects an object with duplicate keys rather than collapsing it"):
    val dupes = Json.Obj(
      Vector("a" -> Json.Str("1"), "b" -> Json.Str("2"), "a" -> Json.Str("3"))
    )
    intercept[IllegalArgumentException](dupes.toCirceJson)

  test("toCirceJson accepts keys that repeat across nesting levels"):
    val nested = Json.Obj(Vector("a" -> Json.Obj(Vector("a" -> Json.Str("inner")))))
    assertEquals(nested.toCirceJson.toRiftJson, nested)

  // ── AC3: the encoded body matches what circe itself produces ───────────────
  property("encode agrees with circe's own encoder"):
    forAll: (u: User) =>
      assertEquals(summon[JsonBody[User]].encode(u), summon[Codec[User]].apply(u).toRiftJson)

  // ── AC4: decode failures are values, not exceptions ────────────────────────
  test("decode returns Left on a shape mismatch"):
    val r = summon[JsonBody[User]].decode(Json.Str("not an object"))
    assert(r.isLeft, s"expected a Left, got $r")

  test("decode returns Left on a missing field"):
    val r = summon[JsonBody[User]].decode(Json.Obj(Vector("id" -> Json.Num(BigDecimal(1)))))
    assert(r.isLeft, s"expected a Left, got $r")

  test("decode's Left carries circe's failure message"):
    val r = summon[JsonBody[User]].decode(Json.Str("nope"))
    assert(r.left.exists(_.nonEmpty), s"expected a non-empty message, got $r")

  // ── issue #85: the read-back half the module scaladoc advertises ─────────────
  // Mirrors the zio-json side-car's gate: `recorded.bodyAs[User]` is what `CirceJsonCodec`'s doc
  // promises, exercised through a *derived* codec rather than a built-in instance.
  private def recordedWith(body: Option[Json]): rift.model.RecordedRequest =
    rift.model.RecordedRequest(
      method = rift.model.Method.POST,
      path = "/users",
      query = Map.empty,
      headers = rift.model.Headers.empty,
      body = body,
      bodyText = body.map(_.render),
      timestamp = java.time.Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )

  test("bodyAs round-trips a derived codec off a recorded request"):
    val user = User(1, "Alice", List("admin"))
    val recorded = recordedWith(Some(summon[JsonBody[User]].encode(user)))
    assertEquals(recorded.bodyAs[User], Right(user))

  test("bodyAs surfaces a wrong-shape body as a Left carrying the codec's message"):
    val recorded = recordedWith(Some(Json.Obj(Vector("id" -> Json.Num(BigDecimal(1))))))
    val result = recorded.bodyAs[User]
    // `result.left.map(...).getOrElse(d)` would silently answer `d` — the outer getOrElse reads
    // the Right side. Project the Left out first.
    val err = result.left.getOrElse(fail(s"expected a Left, got $result"))
    assertEquals(err.path, Vector("body"))
