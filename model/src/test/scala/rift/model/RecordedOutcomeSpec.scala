package rift.model

import rift.json.Json
import rift.model.matching.{MissedRequest, PredicateFailure, VerificationReport}

/** Issue #174 — engine 0.18.0 records the status an imposter answered with and how long it took on
  * each journal entry (optional `status` and `latencyMs`). Read from `raw` and never failing the
  * decode, as rift-java 0.3.0 does (#238).
  */
class RecordedOutcomeSpec extends munit.FunSuite:

  private def recorded(
      extra: String,
      method: String = "GET",
      path: String = "/orders"
  ): RecordedRequest =
    val raw =
      s"""{"method":"$method","path":"$path","timestamp":"2026-09-23T10:00:00Z"$extra}"""
    Json
      .parse(raw)
      .flatMap(RecordedRequest.fromJson)
      .fold(e => fail(s"$raw: $e"), identity)

  test("status and latencyMs are read when the engine recorded them"):
    val r = recorded(""","status":201,"latencyMs":12""")
    assertEquals(r.status, Some(201))
    assertEquals(r.latencyMs, Some(12L))

  test("both are absent on an entry that carries no outcome"):
    val r = recorded("")
    assertEquals(r.status, None)
    assertEquals(r.latencyMs, None)

  test("a present zero latency is an ordinary reading"):
    assertEquals(recorded(""","status":200,"latencyMs":0""").latencyMs, Some(0L))

  test("a mistyped or impossible value reads as absent and never fails the decode"):
    List(
      ""","status":"200","latencyMs":"5"""",
      ""","status":70000,"latencyMs":-1""",
      ""","status":-1,"latencyMs":1.5""",
      ""","status":200.5,"latencyMs":null"""
    ).foreach: extra =>
      val r = recorded(extra)
      assertEquals(r.status, None, extra)
      assertEquals(r.latencyMs, None, extra)

  test("summary renders the outcome the way rift-java does"):
    assertEquals(recorded(""","status":201,"latencyMs":12""").summary, "GET /orders → 201 in 12 ms")
    assertEquals(recorded(""","status":404""").summary, "GET /orders → 404")
    assertEquals(recorded("").summary, "GET /orders")

  test("summary prints a custom method by its wire name and an empty path as /"):
    assertEquals(recorded("", method = "PURGE", path = "").summary, "PURGE /")

  test("the near-miss report heads each miss with its summary"):
    val miss = recorded(""","status":404,"latencyMs":3""", method = "PURGE")
    val report = VerificationReport(
      matched = Vector.empty,
      missed = Vector(
        MissedRequest(
          miss,
          Vector(PredicateFailure(PredicateOp.Equals(Fields.empty), "path", "/a", Some("/orders")))
        )
      )
    )
    assert(report.render.contains("  PURGE /orders → 404 in 3 ms"), report.render)
