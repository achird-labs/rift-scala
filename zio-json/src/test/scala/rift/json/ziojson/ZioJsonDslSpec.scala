package rift.json.ziojson

import zio.json.{DeriveJsonCodec, JsonCodec}
import rift.dsl.*
import rift.json.Json
import rift.model.Method.*
import rift.model.{JsonBody, Response}

/** The doc-tested example for issue #16: the snippet in `zio-json/README.md` is this test.
  *
  * Like [[ZioJsonBodySpec]], no ZIO effect type appears — a user on the `pure` or `cats` surface
  * writes exactly this.
  */
class ZioJsonDslSpec extends munit.FunSuite:

  case class User(id: Int, name: String)
  object User:
    given JsonCodec[User] = DeriveJsonCodec.gen[User]

  private def bodyOf(r: Response): Option[Json] = r match
    case is: Response.Is => is.response.body
    case other => fail(s"expected an is-response, got $other")

  test("ok.json(a) encodes through the derived zio-json codec"):
    val body = bodyOf(ok.json(User(1, "Alice")).build)
    assertEquals(
      body,
      Some(Json.Obj(Vector("id" -> Json.Num(BigDecimal(1)), "name" -> Json.Str("Alice"))))
    )

  test("a full stub builds with a typed body"):
    val stub = on(GET, "/users/1").reply(ok.json(User(1, "Alice"))).build
    assertEquals(
      bodyOf(stub.responses.head),
      Some(Json.Obj(Vector("id" -> Json.Num(BigDecimal(1)), "name" -> Json.Str("Alice"))))
    )

  test("body.deepEquals(a) matches a request body through the same codec"):
    val stub = on(POST, "/users").where(body.deepEquals(User(2, "Bob"))).reply(ok).build
    val rendered = stub.predicates.map(_.toJson.render).mkString
    assert(
      rendered.contains("\"name\":\"Bob\"") && rendered.contains("\"id\":2"),
      s"expected the encoded user in the deepEquals predicate, got: $rendered"
    )
