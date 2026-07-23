package rift.bridge

import munit.FunSuite

import rift.RiftError
import rift.json.Json
import rift.model.Port

import io.github.achirdlabs.rift.RiftEvent as JRiftEvent
import io.github.achirdlabs.rift.RecordedRequest as JRecordedRequest
import io.github.achirdlabs.rift.json.JsonValue

/** AC1/AC5/AC6 — the D1 translation `FacadeDecode.riftEvent` mirrors the facade's `RiftEvent` ADT
  * 1:1: `Hello`/`ImposterChanged`/`RequestRecorded`/`Lagged` translate field-for-field, the
  * embedded `RequestRecorded.request` goes through the same D2 raw-JSON seam as every other
  * recorded request (`FacadeDecode.recordedRequest`), and a decode failure (a malformed embedded
  * request, or an out-of-range port) propagates as `RiftError.DecodeFailed` rather than being
  * dropped. The `Unknown` branch (D2) is unreachable under the pinned jar — `RiftEvent` is
  * JVM-sealed to exactly `Hello`/`ImposterChanged`/`RequestRecorded`/`Lagged` (verified via `javap
  * -v`'s `PermittedSubclasses`) — so it is defensive forward-compat, not exercised here.
  */
class EventTranslationSpec extends FunSuite:

  private val validRequestJson =
    """{"method":"GET","path":"/ping","timestamp":"1970-01-01T00:00:00Z"}"""

  /** Builds a facade `RecordedRequest` whose `.raw()` is the given document — `FacadeDecode`
    * decodes exclusively off `.raw()`, so the canonical constructor's other (unused) fields are
    * filled with harmless placeholders rather than mirrored from `rawJson` (mirrors
    * `VerifyResultDecodeSpec`'s `jreqRaw`).
    */
  private def jreqRaw(rawJson: String): JRecordedRequest =
    new JRecordedRequest(
      "GET",
      "/placeholder",
      java.util.Map.of(),
      java.util.Map.of(),
      "",
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      java.util.Map.of(),
      JsonValue.parse(rawJson)
    )

  test("Hello translates every field, port present") {
    val h = JRiftEvent.Hello(
      "0.16.0",
      42L,
      java.util.List.of("LIFECYCLE", "REQUESTS"),
      java.util.OptionalInt.of(8080)
    )
    FacadeDecode.riftEvent(h) match
      case RiftEvent.Hello(engineVersion, seqAtConnect, types, port) =>
        assertEquals(engineVersion, "0.16.0")
        assertEquals(seqAtConnect, 42L)
        assertEquals(types, Vector("LIFECYCLE", "REQUESTS"))
        assertEquals(port.map(Port.value), Some(8080))
      case other => fail(s"expected Hello, got $other")
  }

  test("Hello translates, port absent") {
    val h = JRiftEvent.Hello("0.16.0", 0L, java.util.List.of(), java.util.OptionalInt.empty())
    FacadeDecode.riftEvent(h) match
      case RiftEvent.Hello(_, _, types, port) =>
        assertEquals(types, Vector.empty)
        assertEquals(port, None)
      case other => fail(s"expected Hello, got $other")
  }

  test("every ImposterChanged.Action value translates 1:1") {
    val expected = Map(
      JRiftEvent.ImposterChanged.Action.CREATED -> ImposterAction.Created,
      JRiftEvent.ImposterChanged.Action.REPLACED -> ImposterAction.Replaced,
      JRiftEvent.ImposterChanged.Action.STUBS_CHANGED -> ImposterAction.StubsChanged,
      JRiftEvent.ImposterChanged.Action.DELETED -> ImposterAction.Deleted,
      JRiftEvent.ImposterChanged.Action.ALL_DELETED -> ImposterAction.AllDeleted
    )
    // Looping over the live enum's own `values()` (not just the hardcoded map above) is what would
    // catch a future Action the facade adds and this translation doesn't mirror — a map-only
    // assertion would stay green even if `imposterAction`'s match silently dropped a case.
    JRiftEvent.ImposterChanged.Action.values().toVector.foreach { action =>
      val jic = new JRiftEvent.ImposterChanged(
        java.util.OptionalLong.of(1L),
        action,
        java.util.OptionalInt.of(9090)
      )
      FacadeDecode.riftEvent(jic) match
        case RiftEvent.ImposterChanged(seq, translated, port) =>
          assertEquals(seq, Some(1L))
          assertEquals(translated, expected(action))
          assertEquals(port.map(Port.value), Some(9090))
        case other => fail(s"expected ImposterChanged, got $other")
    }
  }

  test("Lagged(7) translates to Lagged(7) — an element, never a stream error (D6)") {
    assertEquals(FacadeDecode.riftEvent(new JRiftEvent.Lagged(7L)), RiftEvent.Lagged(7L))
  }

  test("RequestRecorded decodes its embedded request through the D2 seam") {
    val jr = new JRiftEvent.RequestRecorded(
      java.util.OptionalLong.of(5L),
      8080,
      java.util.OptionalLong.of(12L),
      java.util.Optional.of("flow-1"),
      jreqRaw(validRequestJson)
    )
    val expectedRequest =
      Json.parse(validRequestJson).flatMap(rift.model.RecordedRequest.fromJson) match
        case Right(r) => r
        case Left(err) => fail(s"test setup: bad request fixture: $err")
    FacadeDecode.riftEvent(jr) match
      case RiftEvent.RequestRecorded(seq, port, index, flowId, request) =>
        assertEquals(seq, Some(5L))
        assertEquals(Port.value(port), 8080)
        assertEquals(index, Some(12L))
        assertEquals(flowId.map(rift.model.FlowId.value), Some("flow-1"))
        assertEquals(request, expectedRequest)
      case other => fail(s"expected RequestRecorded, got $other")
  }

  test(
    "a decode failure in the embedded request propagates as RiftError.DecodeFailed, never dropped"
  ) {
    val jr = new JRiftEvent.RequestRecorded(
      java.util.OptionalLong.empty(),
      8080,
      java.util.OptionalLong.empty(),
      java.util.Optional.empty(),
      jreqRaw("""{"path":"/x"}""") // missing the required "method" field
    )
    intercept[RiftError.DecodeFailed](FacadeDecode.riftEvent(jr))
  }

  test("an out-of-range RequestRecorded.port throws DecodeFailed") {
    val jr = new JRiftEvent.RequestRecorded(
      java.util.OptionalLong.empty(),
      0,
      java.util.OptionalLong.empty(),
      java.util.Optional.empty(),
      jreqRaw(validRequestJson)
    )
    intercept[RiftError.DecodeFailed](FacadeDecode.riftEvent(jr))
  }

  test("an out-of-range Hello.port throws DecodeFailed") {
    val h = JRiftEvent.Hello("0.16.0", 0L, java.util.List.of(), java.util.OptionalInt.of(0))
    intercept[RiftError.DecodeFailed](FacadeDecode.riftEvent(h))
  }
