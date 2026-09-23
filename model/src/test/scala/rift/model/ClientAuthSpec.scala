package rift.model

import rift.dsl.*
import rift.json.Json

/** Issue #175 — HTTPS imposter client-certificate auth (engine 0.18.0, rift#977): `mutualAuth`,
  * `rejectUnauthorized` and `ca`. The engine refuses every combination that cannot take effect; the
  * DSL builds only the two it accepts, and decoding validates nothing, so any engine output reads.
  * Mirrors rift-java 0.3.0 (#233).
  */
class ClientAuthSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  private val pemA = "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----"
  private val pemB = "-----BEGIN CERTIFICATE-----\nB\n-----END CERTIFICATE-----"

  private def written(b: ImposterBuilder, key: String): Option[Json] = b.build.toJson.get(key)

  test("requireClientCertificate sets mutualAuth only"):
    val b = imposter("a").https("cert", "key").requireClientCertificate
    assertEquals(written(b, "mutualAuth"), Some(Json.Bool(true)))
    assertEquals(written(b, "rejectUnauthorized"), None)
    assertEquals(written(b, "ca"), None)

  test("requireClientCertificate(ca) also validates the chain against one anchor"):
    val b = imposter("a").https("cert", "key").requireClientCertificate(pemA)
    assertEquals(written(b, "mutualAuth"), Some(Json.Bool(true)))
    assertEquals(written(b, "rejectUnauthorized"), Some(Json.Bool(true)))
    assertEquals(written(b, "ca"), Some(Json.Str(pemA)))

  test("several anchors are written as an array, in order"):
    val b = imposter("a").requireClientCertificate(pemA, pemB)
    assertEquals(written(b, "ca"), Some(Json.arr(Json.Str(pemA), Json.Str(pemB))))

  test("a later call replaces an earlier one"):
    val b = imposter("a").requireClientCertificate(pemA).requireClientCertificate
    assertEquals(written(b, "rejectUnauthorized"), None)
    assertEquals(written(b, "ca"), None)

  test("an anchor with no certificate block is refused at construction"):
    val e = intercept[IllegalArgumentException](imposter("a").requireClientCertificate("not a pem"))
    assert(e.getMessage.contains("BEGIN CERTIFICATE"), e.getMessage)
    intercept[IllegalArgumentException](imposter("a").requireClientCertificate(pemA, "junk"))

  test("an imposter that never mentions client auth writes none of the keys"):
    val json = imposter("a").https("cert", "key").build.toJson
    List("mutualAuth", "rejectUnauthorized", "ca").foreach(k => assertEquals(json.get(k), None, k))

  private val caA = Json.Str(pemA).render
  private val caB = Json.Str(pemB).render

  test("both ca spellings decode to typed values and round-trip byte for byte"):
    val head = """{"protocol":"https","mutualAuth":true,"rejectUnauthorized":true,"ca":"""
    List(
      s"$head$caA}",
      s"$head[$caA,$caB]}"
    ).foreach: raw =>
      val d = ImposterDefinition.fromJson(parse(raw)).fold(e => fail(s"$raw: $e"), identity)
      assertEquals(d.clientAuth.mutualAuth, Some(true))
      assertEquals(d.extra, Vector.empty)
      assertEquals(d.toJson.render, parse(raw).render)
    val single = ImposterDefinition
      .fromJson(parse(s"""{"ca":$caA}"""))
      .fold(e => fail(e.toString), identity)
    assertEquals(single.clientAuth.ca, Some(CaCertificates.Single(pemA)))

  test("a combination the engine refuses still decodes: reading validates nothing"):
    val raw = """{"protocol":"http","rejectUnauthorized":true,"mutualAuth":false,"ca":[]}"""
    val d = ImposterDefinition.fromJson(parse(raw)).fold(e => fail(e.toString), identity)
    assertEquals(
      d.clientAuth,
      ClientAuth(Some(false), Some(true), Some(CaCertificates.Many(Vector.empty)))
    )
    assert(d.toJson.semanticEquals(parse(raw)), d.toJson.render)

  test("imposterFromJson carries client auth through the builder unchanged"):
    val raw = s"""{"protocol":"https","mutualAuth":true}"""
    val out = imposterFromJson(raw).fold(e => fail(e.toString), _.build.toJson)
    assertEquals(out.get("mutualAuth"), Some(Json.Bool(true)))

  test("a mistyped key is a decode error naming it"):
    ImposterDefinition.fromJson(parse("""{"mutualAuth":"yes"}""")) match
      case Left(e) => assertEquals(e.path, Vector("mutualAuth"))
      case Right(d) => fail(s"decoded to $d")
    ImposterDefinition.fromJson(parse("""{"ca":[1]}""")) match
      case Left(e) => assertEquals(e.path.headOption, Some("ca"))
      case Right(d) => fail(s"decoded to $d")
