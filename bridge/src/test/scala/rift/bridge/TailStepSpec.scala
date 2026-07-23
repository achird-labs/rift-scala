package rift.bridge

import java.time.Instant

import munit.FunSuite

import rift.RiftError
import rift.json.Json
import rift.model.{FlowId, Headers, Method, RecordedRequest}

import io.github.achirdlabs.rift.MatchClause as JMatchClause

/** The pure cursor + signal transition shared by the ZIO and fs2 tails (issue #37, DESIGN.md §5.3,
  * D6). These pin the emission rules the two unfolds must not diverge on: a control signal per
  * observable `RecordedPage` condition, dedup of the degraded/restored transition, and the
  * hold-the-cursor-on-`None` invariant. `FacadeEncode.matchClauses` proves the `TailFilter` →
  * facade `MatchClause` mapping is total.
  */
class TailStepSpec extends FunSuite:

  private def rr(path: String): RecordedRequest =
    RecordedRequest(
      method = Method.GET,
      path = path,
      query = Map.empty,
      headers = Headers.empty,
      body = None,
      bodyText = None,
      timestamp = Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )

  private def page(
      rs: Vector[RecordedRequest],
      next: Option[Long],
      truncated: Boolean = false
  ): RecordedPage = RecordedPage(rs, next, truncated)

  test("stable page: a Received per request in order, no control signal"):
    val (evs, st) = TailStep.step(TailStep.initial, page(Vector(rr("/a"), rr("/b")), Some(2L)))
    assertEquals(evs, Vector(TailEvent.Received(rr("/a")), TailEvent.Received(rr("/b"))))
    assertEquals(st, TailStep.State(Some(2L), degraded = false))

  test("truncated page emits Truncated once, before its Received elements"):
    val (evs, _) =
      TailStep.step(TailStep.initial, page(Vector(rr("/x")), Some(9L), truncated = true))
    assertEquals(evs, Vector(TailEvent.Truncated, TailEvent.Received(rr("/x"))))

  test(
    "transition into nextIndex == None emits Degraded once; staying degraded does not repeat it"
  ):
    val (e1, s1) = TailStep.step(TailStep.initial, page(Vector(rr("/a")), None))
    assertEquals(e1, Vector(TailEvent.Degraded, TailEvent.Received(rr("/a"))))
    assert(s1.degraded)
    val (e2, s2) = TailStep.step(s1, page(Vector(rr("/b")), None))
    assertEquals(e2, Vector(TailEvent.Received(rr("/b"))))
    assert(s2.degraded)

  test("transition back to a stable index emits Restored once"):
    val degraded = TailStep.State(Some(1L), degraded = true)
    val (evs, st) = TailStep.step(degraded, page(Vector(rr("/c")), Some(4L)))
    assertEquals(evs, Vector(TailEvent.Restored, TailEvent.Received(rr("/c"))))
    assertEquals(st, TailStep.State(Some(4L), degraded = false))

  test("cursor holds its previous value when the page reports no index"):
    val prior = TailStep.State(Some(7L), degraded = false)
    val (_, st) = TailStep.step(prior, page(Vector.empty, None))
    assertEquals(st.cursor, Some(7L))

  test("truncated AND a degraded transition: Truncated, then Degraded, then Received"):
    val (evs, _) = TailStep.step(TailStep.initial, page(Vector(rr("/z")), None, truncated = true))
    assertEquals(evs, Vector(TailEvent.Truncated, TailEvent.Degraded, TailEvent.Received(rr("/z"))))

  test("matchClauses maps each TailFilter to the exact facade MatchClause, in order"):
    val flow = FlowId.from("flow-1").fold(e => fail(s"bad flow id: $e"), identity)
    val out = FacadeEncode.matchClauses(Seq(TailFilter.Header("h", "v"), TailFilter.Flow(flow)))
    // MatchClause.header/flowId are structural-equality Java records, so a swapped-arg or
    // wrong-variant mapping (e.g. both cases → header) fails here, not just an arity check.
    assertEquals(out.toList, List(JMatchClause.header("h", "v"), JMatchClause.flowId("flow-1")))

  test("matchClauses on no filters is the empty (unfiltered) array"):
    assertEquals(FacadeEncode.matchClauses(Nil).toList, Nil)

  // Issue #96 — `MatchClause` has always had four factories; `TailFilter` modelled two, so a tail
  // could not be filtered server-side by method or path. These pin the mapping case for case.
  test("matchClauses maps the method and path filters to their facade counterparts"):
    val out = FacadeEncode.matchClauses(Seq(TailFilter.Method("POST"), TailFilter.Path("/orders")))
    assertEquals(out.toList, List(JMatchClause.method("POST"), JMatchClause.path("/orders")))

  test("all four filter kinds map in order, none collapsing onto another"):
    val flow = FlowId.from("flow-9").fold(e => fail(s"bad flow id: $e"), identity)
    val out = FacadeEncode.matchClauses(
      Seq(
        TailFilter.Header("h", "v"),
        TailFilter.Flow(flow),
        TailFilter.Method("GET"),
        TailFilter.Path("/a")
      )
    )
    assertEquals(
      out.toList,
      List(
        JMatchClause.header("h", "v"),
        JMatchClause.flowId("flow-9"),
        JMatchClause.method("GET"),
        JMatchClause.path("/a")
      )
    )

  // The facade records validate and throw IllegalArgumentException, which is not a RiftException
  // and would otherwise surface as a defect rather than a typed failure.
  test("an invalid method token is a typed InvalidDefinition, not a bare IllegalArgumentException"):
    intercept[RiftError.InvalidDefinition](FacadeEncode.matchClause(TailFilter.Method("GE T")))

  // #129: a space read already carries its own flow clause, and `SpaceImpl` rejects a second one
  // with IllegalArgumentException on EVERY transport — including the HTTP-backed ones the space
  // tail otherwise works on. Caught Scala-side so it lands as a typed error like the case above,
  // rather than as a defect. Engine-free: the rejection happens before any facade call.
  test("a space read rejects TailFilter.Flow as a typed InvalidDefinition"):
    val flow = FlowId.from("flow-1").fold(e => fail(s"bad flow id: $e"), identity)
    val thrown = intercept[RiftError.InvalidDefinition](
      FacadeEncode.spaceMatchClauses(Seq(TailFilter.Header("h", "v"), TailFilter.Flow(flow)))
    )
    assert(thrown.getMessage.contains("already scoped"), thrown.getMessage)

  // The other three kinds must still reach the facade, in order — a blanket refusal would be just
  // as wrong as letting Flow through.
  test("a space read encodes every filter kind that is not Flow, unchanged"):
    val out = FacadeEncode.spaceMatchClauses(
      Seq(TailFilter.Header("h", "v"), TailFilter.Method("GET"), TailFilter.Path("/a"))
    )
    assertEquals(out.length, 3)
    assertEquals(
      out.toList.map(_.toString),
      FacadeEncode
        .matchClauses(
          Seq(TailFilter.Header("h", "v"), TailFilter.Method("GET"), TailFilter.Path("/a"))
        )
        .toList
        .map(_.toString)
    )
