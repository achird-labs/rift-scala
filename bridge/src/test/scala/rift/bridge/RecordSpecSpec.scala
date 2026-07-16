package rift.bridge

import scala.jdk.CollectionConverters.*

import munit.FunSuite

import io.github.etacassiopeia.rift.{RecordMode as JRecordMode, RecordSpec as JRecordSpec}
import io.github.etacassiopeia.rift.dsl.RequestField as JRequestField

/** The `RecordSpec`/`RecordMode`/`RequestField` config maps onto the facade `RecordSpec.builder()`
  * (issue #35). The default-mirroring test is the load-bearing one: the facade builder defaults are
  * `mode=ONCE, generators=[METHOD,PATH], addWaitBehavior=true, ignoreHeaders=[]`, so `RecordSpec()`
  * must reproduce them exactly or `startRecording(origin, RecordSpec())` would silently differ from
  * the facade's no-spec `startRecording(origin)`.
  */
class RecordSpecSpec extends FunSuite:

  test("RecordSpec() reproduces the facade builder defaults exactly"):
    val js = RecordSpec().toJava
    assertEquals(js.mode(), JRecordMode.ONCE)
    assertEquals(js.generators().asScala.toList, List(JRequestField.METHOD, JRequestField.PATH))
    assertEquals(js.addWaitBehavior(), true)
    assertEquals(js.ignoreHeaders().asScala.toList, List.empty[String])

  test("a fully-custom RecordSpec round-trips through the facade builder"):
    val js = RecordSpec(
      mode = RecordMode.Transparent,
      generateBy = Vector(RequestField.Body, RequestField.Query),
      addWaitBehavior = false,
      ignoreHeaders = Vector("Authorization", "Cookie")
    ).toJava
    assertEquals(js.mode(), JRecordMode.TRANSPARENT)
    assertEquals(js.generators().asScala.toList, List(JRequestField.BODY, JRequestField.QUERY))
    assertEquals(js.addWaitBehavior(), false)
    assertEquals(js.ignoreHeaders().asScala.toList, List("Authorization", "Cookie"))

  test("an explicitly-empty generateBy is honored (not replaced by the builder default)"):
    assertEquals(RecordSpec(generateBy = Vector.empty).toJava.generators().asScala.toList, Nil)

  test("RecordMode maps 1:1 to the facade enum"):
    assertEquals(RecordMode.Once.toJava, JRecordMode.ONCE)
    assertEquals(RecordMode.Always.toJava, JRecordMode.ALWAYS)
    assertEquals(RecordMode.Transparent.toJava, JRecordMode.TRANSPARENT)

  test("RequestField maps 1:1 to the facade enum"):
    assertEquals(RequestField.Method.toJava, JRequestField.METHOD)
    assertEquals(RequestField.Path.toJava, JRequestField.PATH)
    assertEquals(RequestField.Query.toJava, JRequestField.QUERY)
    assertEquals(RequestField.Headers.toJava, JRequestField.HEADERS)
    assertEquals(RequestField.Body.toJava, JRequestField.BODY)
