package rift.json.ziojson

// The point of this file. Under the old `rift.json.zio` package name these two imports could not
// coexist: `import rift.json.*` bound the name `zio` to the side-car package and shadowed the ZIO
// root, so the second line failed with "value json is not a member of rift.json.zio" — an error
// that never hints the fix is `_root_.zio`. Adding the artifact to a classpath broke user code that
// compiled fine without it (#24).
//
// This is a compile-time assertion: if the package ever regains a segment spelled `zio`, this file
// stops compiling and CI goes red. The test body is incidental.
import rift.json.*
import zio.json.JsonCodec

import rift.model.JsonBody

class PackageShadowingSpec extends munit.FunSuite:

  case class Widget(id: Int) derives JsonCodec

  test("rift.json.* and zio.json.* can be wildcard-imported together"):
    val body: Json = summon[JsonBody[Widget]].encode(Widget(7))
    assertEquals(body, Json.Obj(Vector("id" -> Json.Num(BigDecimal(7)))))

  test("the model's own Json AST is reachable through the same wildcard import"):
    assertEquals(
      Json.parse("""{"id":7}"""),
      Right(Json.Obj(Vector("id" -> Json.Num(BigDecimal(7)))))
    )
