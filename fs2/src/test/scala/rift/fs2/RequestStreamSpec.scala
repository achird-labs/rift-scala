package rift.fs2

import java.time.Instant

import scala.concurrent.duration.*

import _root_.cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import rift.RiftError
import rift.bridge.RecordedPage
import rift.json.Json
import rift.model.{Headers, Method, RecordedRequest}

/** The cursor request tail (D6, re-based per #22/#29) — mirrors `rift.zio.RequestTailSpec` 1:1.
  * These assert the three properties the naive `all.drop(offset)` scheme silently violated:
  * exactly-once across pages, holding the cursor when the engine exposes no stable index, and
  * treating `truncated` as a signal rather than a drop. `RequestStream.build` is factored out of
  * the `requestStream` extension precisely so it is testable against a scripted page source with no
  * live engine.
  */
class RequestStreamSpec extends CatsEffectSuite:

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
  ): IO[(Option[Long] => IO[RecordedPage], Ref[IO, Vector[Option[Long]]])] =
    for
      remaining <- Ref.of[IO, List[RecordedPage]](pages)
      calls <- Ref.of[IO, Vector[Option[Long]]](Vector.empty)
    yield
      val fetch = (cursor: Option[Long]) =>
        calls.update(_ :+ cursor) *>
          remaining.modify {
            case head :: tail => (tail, head)
            case Nil => (Nil, RecordedPage(Vector.empty, cursor, truncated = false))
          }
      (fetch, calls)

  test("emits every request exactly once across pages — no skip, no replay") {
    for
      sc <- scripted(
        List(
          RecordedPage(Vector(rr("/a"), rr("/b")), Some(2L), truncated = false),
          RecordedPage(Vector(rr("/c")), Some(3L), truncated = false)
        )
      )
      (fetch, _) = sc
      out <- RequestStream.build(fetch, 1.milli).take(3).compile.toList
    yield assertEquals(out.map(_.path), List("/a", "/b", "/c"))
  }

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
      _ <- RequestStream.build(fetch, 1.milli).take(3).compile.drain
      seen <- calls.get
    yield assertEquals(seen.take(3), Vector(None, None, Some(5L)))
  }

  test("truncated page still emits its requests — a signal, not a silent drop") {
    for
      sc <- scripted(
        List(RecordedPage(Vector(rr("/x"), rr("/y")), Some(9L), truncated = true))
      )
      (fetch, _) = sc
      out <- RequestStream.build(fetch, 1.milli).take(2).compile.toList
    yield assertEquals(out.map(_.path), List("/x", "/y"))
  }

  test("a fetch failure propagates through the Stream channel — not swallowed") {
    val err = RiftError.CommunicationError("boom", None)
    for
      calls <- Ref.of[IO, Int](0)
      fetch = (_: Option[Long]) =>
        calls.getAndUpdate(_ + 1).flatMap {
          case 0 => IO.pure(RecordedPage(Vector(rr("/a")), Some(1L), truncated = false))
          case _ => IO.raiseError[RecordedPage](err)
        }
      out <- RequestStream.build(fetch, 1.milli).take(3).compile.toList.attempt
    yield assertEquals(out, Left(err))
  }

  test("an empty page with no nextIndex holds the cursor and keeps polling") {
    for
      sc <- scripted(
        List(
          RecordedPage(
            Vector.empty,
            None,
            truncated = false
          ), // empty + no index → hold, keep polling
          RecordedPage(Vector(rr("/a")), Some(3L), truncated = false)
        )
      )
      (fetch, calls) = sc
      out <- RequestStream.build(fetch, 1.milli).take(1).compile.toList
      seen <- calls.get
    yield
      assertEquals(out.map(_.path), List("/a"))
      assertEquals(seen.take(2), Vector(None, None))
  }
