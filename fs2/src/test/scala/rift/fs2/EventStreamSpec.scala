package rift.fs2

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import _root_.cats.effect.{IO, Resource}
import munit.CatsEffectSuite

import rift.RiftError
import rift.bridge.{EventSource, RiftEvent}

/** `EventStream.build` (D4/D5/D6/D7, issue #87) — mirrors `rift.zio.EventsSpec` 1:1, fs2-shaped.
  * Factored out of the `events` extension precisely so it is testable against a scripted
  * `Resource[F, EventSource]` with no live engine. `EventSource` is a blocking, synchronous push
  * (`poll()`/`close()`) — mirroring the facade's own shape — so the scripted double here is plain
  * synchronous state, not an effectful fetch (unlike `RequestStream`'s poll-on-interval `fetch`).
  */
class EventStreamSpec extends CatsEffectSuite:

  private val hello = RiftEvent.Hello("0.16.0", 0L, Vector("LIFECYCLE"), None)
  private val lagged = RiftEvent.Lagged(3L)

  /** Yields the scripted `events` in order, then `None` forever (engine-side end). A `Left` at a
    * given position models a thrown `RiftError` from that `poll()` call. `closeCount` lets a test
    * assert `close()` fired exactly once.
    */
  private final class ScriptedSource(events: List[Either[RiftError, RiftEvent]])
      extends EventSource:
    private val remaining = new AtomicReference(events)
    private val closes = new AtomicInteger(0)

    def poll(): Option[RiftEvent] =
      remaining.getAndUpdate {
        case Nil => Nil
        case _ :: tail => tail
      } match
        case Nil => None
        case Right(ev) :: _ => Some(ev)
        case Left(err) :: _ => throw err

    def close(): Unit = { closes.incrementAndGet(); () }
    def closeCount: Int = closes.get()

  private def resourceOf(
      events: List[Either[RiftError, RiftEvent]]
  ): (Resource[IO, EventSource], ScriptedSource) =
    val source = new ScriptedSource(events)
    (Resource.fromAutoCloseable(IO(source)), source)

  test("emits scripted events in order, then ends cleanly on engine-side end") {
    val (resource, _) = resourceOf(List(Right(hello), Right(lagged)))
    EventStream.build(resource).compile.toList.map(out => assertEquals(out, List(hello, lagged)))
  }

  test("Lagged is an ordinary element, not a stream failure (D6)") {
    val (resource, _) = resourceOf(List(Right(lagged), Right(hello)))
    EventStream.build(resource).compile.toList.map(out => assertEquals(out, List(lagged, hello)))
  }

  test("a poll throwing RiftError fails the stream with that typed error") {
    val err = RiftError.CommunicationError("boom", None)
    val (resource, _) = resourceOf(List(Right(hello), Left(err)))
    EventStream.build(resource).compile.toList.attempt.map(out => assertEquals(out, Left(err)))
  }

  test("close() is observed exactly once after take(1) truncates the stream") {
    val (resource, source) = resourceOf(List(Right(hello), Right(lagged)))
    EventStream.build(resource).take(1).compile.drain.map(_ => assertEquals(source.closeCount, 1))
  }

  test("close() is observed exactly once after natural end-of-stream") {
    val (resource, source) = resourceOf(List(Right(hello)))
    EventStream.build(resource).compile.drain.map(_ => assertEquals(source.closeCount, 1))
  }

  // The two paths a leak actually hides on. Every test above ends the stream by returning `None` or
  // by truncating it, so neither the failure release nor cancellation was exercised — and a leaked
  // SSE connection is invisible to all of them. Note the earlier failure test discards the handle
  // entirely, so it could not have caught a leak even in principle.
  test("close() is observed exactly once when a poll throws") {
    val (resource, source) =
      resourceOf(List(Right(hello), Left(RiftError.CommunicationError("x", None))))
    EventStream
      .build(resource)
      .compile
      .drain
      .attempt
      .map(_ => assertEquals(source.closeCount, 1))
  }

  test("close() is observed when the stream is cancelled mid-poll") {
    // Parks inside poll() the way a real quiet SSE connection does, so cancellation has to unwind
    // the `interruptible` region rather than stop at a convenient stream boundary.
    val source = new ParkingSource
    val resource = Resource.fromAutoCloseable(IO(source: EventSource))
    EventStream
      .build(resource)
      .interruptAfter(scala.concurrent.duration.Duration(300, "ms"))
      .compile
      .drain
      .timeout(scala.concurrent.duration.Duration(10, "s"))
      .attempt
      .map(_ => assert(source.closeCount >= 1, s"stream leaked: closeCount=${source.closeCount}"))
  }

  /** Blocks in `poll()` until closed — the shape of a real connection with no traffic. */
  private final class ParkingSource extends EventSource:
    private val gate = new java.util.concurrent.CountDownLatch(1)
    private val closes = new AtomicInteger(0)

    def poll(): Option[RiftEvent] =
      gate.await()
      None

    def close(): Unit =
      closes.incrementAndGet()
      gate.countDown()

    def closeCount: Int = closes.get()
