package rift.model

import java.time.Instant

import munit.FunSuite

import rift.json.{Json, JsonError}

/** Issue #85 — `RecordedRequest.bodyAs[A]` is the read-back half of the `JsonBody[A]` side-car
  * design (D7); `ok.json(a)` is the write half. Three modules' scaladoc already promised it.
  *
  * The point of the `Either[JsonError.Decode, A]` shape is that "no body", "body isn't JSON" and
  * "body isn't an A" stay distinguishable — an `Option` would collapse the first two into the
  * third, which is the silent degradation this codebase rejects.
  */
class RecordedRequestBodyAsSpec extends FunSuite:

  private def req(body: Option[Json], bodyText: Option[String] = None): RecordedRequest =
    RecordedRequest(
      method = Method.POST,
      path = "/orders",
      query = Map.empty,
      headers = Headers.empty,
      body = body,
      bodyText = bodyText.orElse(body.map(_.render)),
      timestamp = Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )

  test("bodyAs decodes the recorded JSON body through its JsonBody codec"):
    val body = Json.obj("id" -> Json.Num(BigDecimal(1)))
    assertEquals(req(Some(body)).bodyAs[Json], Right(body))

  test("bodyAs decodes a primitive body through the built-in instances"):
    assertEquals(req(Some(Json.Str("hello"))).bodyAs[String], Right("hello"))
    assertEquals(req(Some(Json.Num(BigDecimal(42)))).bodyAs[Int], Right(42))
    assertEquals(req(Some(Json.Bool(true))).bodyAs[Boolean], Right(true))

  test("an absent body is a Left that says so — never decoded as null or a default"):
    val result = req(None).bodyAs[Int]
    assert(result.isLeft, result.toString)
    val err = result.left.getOrElse(fail("expected a Left"))
    assert(err.message.contains("no body"), err.toString)
    assertEquals(err.path, Vector("body"))

  // `bodyText` without `body` is a producer that recorded the body outside the `body` key — the
  // engine has no such field, so this arm is defensive and reports the observed state only.
  test("a bodyText-only request is a distinct Left from a request with no body at all"):
    val result = req(None, bodyText = Some("<order/>")).bodyAs[Int]
    val err = result.left.getOrElse(fail("expected a Left"))
    assert(err.message.contains("bodyText"), err.toString)

  // A non-JSON body is NOT the missing-body arm: `body` holds whatever the `body` key contained,
  // so text arrives as a Json.Str and fails as an ordinary codec mismatch.
  test("a text body arrives as a JSON string and fails as a shape mismatch, not a missing body"):
    val err = req(Some(Json.Str("plain text"))).bodyAs[Int].left.getOrElse(fail("expected a Left"))
    assert(!err.message.contains("no body"), err.toString)

  test("a shape mismatch is a Left carrying the codec's own message under the body path"):
    val result = req(Some(Json.Str("not-a-number"))).bodyAs[Int]
    val err = result.left.getOrElse(fail("expected a Left"))
    assertEquals(err.path, Vector("body"))
    assert(err.message.contains("integer") || err.message.contains("expected"), err.toString)

  test("the three failure modes are mutually distinguishable"):
    val absent = req(None).bodyAs[Int].left.map(_.message)
    val nonJson = req(None, bodyText = Some("x")).bodyAs[Int].left.map(_.message)
    val wrongShape = req(Some(Json.Str("s"))).bodyAs[Int].left.map(_.message)
    assertEquals(Set(absent, nonJson, wrongShape).size, 3)
