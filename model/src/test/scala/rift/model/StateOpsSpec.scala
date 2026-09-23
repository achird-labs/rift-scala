package rift.model

import rift.dsl.*
import rift.json.Json

/** Issue #172 — `_rift.stateOps`, engine 0.18.0's declarative post-response flow-state writes: a
  * list of `set` / `increment` / `delete` / `clearFlow` ops run in order against the request's
  * resolved flow id. Mirrors rift-java 0.3.0's `IsSpec.setState/incrementState/deleteState/
  * clearFlowState` (#237).
  */
class StateOpsSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  private def stateOpsOf(r: IsResponseBuilder): Option[String] =
    r.build.toJson.get("_rift", "stateOps").map(_.render)

  test("each chainer writes its op"):
    assertEquals(
      stateOpsOf(ok.setState("k", "v")),
      Some("""[{"op":"set","key":"k","value":"v"}]""")
    )
    assertEquals(
      stateOpsOf(ok.incrementState("hits")),
      Some("""[{"op":"increment","key":"hits","by":1}]""")
    )
    assertEquals(
      stateOpsOf(ok.incrementState("stock", -2)),
      Some("""[{"op":"increment","key":"stock","by":-2}]""")
    )
    assertEquals(stateOpsOf(ok.deleteState("k")), Some("""[{"op":"delete","key":"k"}]"""))
    assertEquals(stateOpsOf(ok.clearFlowState), Some("""[{"op":"clearFlow"}]"""))

  test("ops run in call order and compose with the other _rift settings"):
    val built = ok.templated
      .setState("last", "{{ request.path }}")
      .incrementState("hits")
      .clearFlowState
      .build
      .toJson
    assertEquals(
      built.get("_rift").map(_.render),
      Some(
        """{"templated":true,"stateOps":[{"op":"set","key":"last","value":"{{ request.path }}"},{"op":"increment","key":"hits","by":1},{"op":"clearFlow"}]}"""
      )
    )

  test("a response with no state ops writes no stateOps key"):
    assertEquals(ok.templated.build.toJson.get("_rift", "stateOps"), None)

  private def decoded(raw: String): Vector[StateOp] =
    RiftResponseExt
      .fromJson(parse(s"""{"stateOps":$raw}"""))
      .fold(e => fail(s"$raw: $e"), _.stateOps)

  test("every op decodes to its typed case"):
    assertEquals(
      decoded(
        """[{"op":"set","key":"a","value":"1"},{"op":"increment","key":"b","by":5},{"op":"increment","key":"c"},{"op":"delete","key":"d"},{"op":"clearFlow"}]"""
      ),
      Vector(
        StateOp.Set("a", "1"),
        StateOp.Increment("b", Some(5L)),
        StateOp.Increment("c", None),
        StateOp.Delete("d"),
        StateOp.ClearFlow
      )
    )

  test("an increment without `by` round-trips without inventing one"):
    val raw = """{"stateOps":[{"op":"increment","key":"c"}]}"""
    val ext = RiftResponseExt.fromJson(parse(raw)).fold(e => fail(e.toString), identity)
    assertEquals(ext.toJson.render, raw)

  test("an op from a newer engine is carried verbatim"):
    val raw = """{"stateOps":[{"op":"append","key":"log","value":"x"}]}"""
    val ext = RiftResponseExt.fromJson(parse(raw)).fold(e => fail(e.toString), identity)
    assertEquals(
      ext.stateOps,
      Vector(StateOp.Unknown("append", parse("""{"op":"append","key":"log","value":"x"}""")))
    )
    assertEquals(ext.toJson.render, raw)
    assertEquals(ext.extra, Vector.empty)

  test("a malformed op is a decode error naming its index and field"):
    List(
      """[{"op":"set","key":"k"}]""" -> Vector("stateOps", "0", "value"),
      """[{"op":"delete"}]""" -> Vector("stateOps", "0", "key"),
      """[{"op":"increment","key":"k","by":1.5}]""" -> Vector("stateOps", "0", "by"),
      """[{"key":"k"}]""" -> Vector("stateOps", "0", "op"),
      """{"op":"clearFlow"}""" -> Vector("stateOps")
    ).foreach: (raw, path) =>
      RiftResponseExt.fromJson(parse(s"""{"stateOps":$raw}""")) match
        case Left(e) => assertEquals(e.path, path, s"$raw: $e")
        case Right(ext) => fail(s"$raw decoded to $ext")
