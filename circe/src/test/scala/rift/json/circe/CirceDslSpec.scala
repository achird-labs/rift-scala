package rift.json.circe

import io.circe.Codec
import rift.dsl.*
import rift.json.Json
import rift.model.Method.*
import rift.model.Response

/** The doc-tested example for issue #17: the snippet in `circe/README.md` is this test. */
class CirceDslSpec extends munit.FunSuite:

  case class User(id: Int, name: String) derives Codec.AsObject

  private def bodyOf(r: Response): Option[Json] = r match
    case is: Response.Is => is.response.body
    case other => fail(s"expected an is-response, got $other")

  test("ok.json(a) encodes through the derived circe codec"):
    assertEquals(
      bodyOf(ok.json(User(1, "Alice")).build),
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
