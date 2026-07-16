package rift.fs2

import java.time.Instant

import _root_.cats.effect.IO
import _root_.fs2.Stream
import munit.CatsEffectSuite

import rift.dsl.*
import rift.json.Json
import rift.model.{Headers, Method, RecordedRequest}
import rift.model.Method.GET

/** `pipes.matching` filters a `Stream[F, RecordedRequest]` via the client-side matcher
  * (`rift.model.matching.RequestMatcher`) — same predicate vocabulary `on(...)` builds for stubs.
  */
class PipesSpec extends CatsEffectSuite:

  private def rr(path: String): RecordedRequest =
    RecordedRequest(
      method = GET,
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

  test("keeps only requests matching the RequestMatch") {
    val m = on(GET, "/x")
    val requests = Stream[IO, RecordedRequest](rr("/x"), rr("/y"), rr("/x"), rr("/z"))

    requests.through(pipes.matching(m)).compile.toList.map { kept =>
      assertEquals(kept.map(_.path), List("/x", "/x"))
    }
  }

  test("no matches → empty stream, not an error") {
    val m = on(GET, "/nope")
    val requests = Stream[IO, RecordedRequest](rr("/x"), rr("/y"))

    requests.through(pipes.matching(m)).compile.toList.map { kept =>
      assertEquals(kept, List.empty)
    }
  }
