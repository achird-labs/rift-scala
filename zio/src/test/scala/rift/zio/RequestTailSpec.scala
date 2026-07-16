package rift.zio

import java.time.Instant

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.RecordedPage
import rift.json.Json
import rift.model.{Headers, Method, RecordedRequest}

/** The cursor request tail (D6, re-based per #22/#29). These assert the three properties the naive
  * `all.drop(offset)` scheme silently violated: exactly-once across pages, holding the cursor when
  * the engine exposes no stable index, and treating `truncated` as a signal rather than a drop.
  * `RequestTail.stream` is factored out of `ImposterHandle.requests` precisely so it is testable
  * against a scripted page source with no live engine.
  */
object RequestTailSpec extends ZIOSpecDefault:

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

  /** A page source that yields the scripted pages in order (then idles with an empty page that
    * holds the cursor) and records the cursor it was called with each time, so a test can assert
    * cursor advancement directly.
    */
  private def scripted(
      pages: List[RecordedPage]
  ): UIO[(Option[Long] => IO[RiftError, RecordedPage], Ref[Chunk[Option[Long]]])] =
    for
      remaining <- Ref.make(pages)
      calls <- Ref.make(Chunk.empty[Option[Long]])
    yield
      val fetch = (cursor: Option[Long]) =>
        calls.update(_ :+ cursor) *>
          remaining.modify {
            case head :: tail => (head, tail)
            case Nil => (RecordedPage(Vector.empty, cursor, truncated = false), Nil)
          }
      (fetch, calls)

  def spec = suite("RequestTail cursor semantics")(
    test("emits every request exactly once across pages — no skip, no replay") {
      for
        sc <- scripted(
          List(
            RecordedPage(Vector(rr("/a"), rr("/b")), Some(2L), truncated = false),
            RecordedPage(Vector(rr("/c")), Some(3L), truncated = false)
          )
        )
        (fetch, _) = sc
        out <- Live.live(RequestTail.stream(fetch, 1.milli).take(3).runCollect)
      yield assertTrue(out.map(_.path).toList == List("/a", "/b", "/c"))
    },
    test("nextIndex absent → holds the cursor, never falls back to an array offset") {
      for
        sc <- scripted(
          List(
            RecordedPage(Vector(rr("/a")), None, truncated = false), // no stable index → hold
            RecordedPage(Vector(rr("/b")), Some(5L), truncated = false), // now advance
            RecordedPage(Vector(rr("/c")), Some(6L), truncated = false)
          )
        )
        (fetch, calls) = sc
        _ <- Live.live(RequestTail.stream(fetch, 1.milli).take(3).runDrain)
        seen <- calls.get
      yield assertTrue(seen.take(3).toList == List(None, None, Some(5L)))
    },
    test("truncated page still emits its requests — a signal, not a silent drop") {
      for
        sc <- scripted(
          List(RecordedPage(Vector(rr("/x"), rr("/y")), Some(9L), truncated = true))
        )
        (fetch, _) = sc
        out <- Live.live(RequestTail.stream(fetch, 1.milli).take(2).runCollect)
      yield assertTrue(out.map(_.path).toList == List("/x", "/y"))
    }
  )
