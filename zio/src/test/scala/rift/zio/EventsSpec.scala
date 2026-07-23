package rift.zio

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.{EventSource, RiftEvent}

/** `Events.stream` (D4/D5/D6/D7, issue #87) — factored out of `Rift.events` precisely so it is
  * testable against a scripted `EventSource` with no live engine. Unlike `RequestTail`'s
  * poll-on-interval `fetch: Option[Long] => IO[RiftError, RecordedPage]`, `EventSource` is a
  * blocking, synchronous push (`poll()`/`close()`) — mirroring the facade's own shape — so the
  * scripted double here is plain synchronous state, not an effectful fetch.
  */
object EventsSpec extends ZIOSpecDefault:

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

  def spec = suite("Events.stream (admin SSE, D4/D5/D6/D7)")(
    test("emits scripted events in order, then ends cleanly on engine-side end") {
      val source = new ScriptedSource(List(Right(hello), Right(lagged)))
      for out <- Events.stream(source).runCollect
      yield assertTrue(out.toList == List(hello, lagged))
    },
    test("Lagged is an ordinary element, not a stream failure (D6)") {
      val source = new ScriptedSource(List(Right(lagged), Right(hello)))
      for out <- Events.stream(source).runCollect
      yield assertTrue(out.toList == List(lagged, hello))
    },
    test("a poll throwing RiftError fails the stream with that typed error") {
      val err = RiftError.CommunicationError("boom", None)
      val source = new ScriptedSource(List(Right(hello), Left(err)))
      for exit <- Events.stream(source).runCollect.exit
      yield assertTrue(exit match
        case Exit.Failure(cause) => cause.failureOption.contains(err)
        case Exit.Success(_) => false
      )
    },
    test("close() is observed exactly once after take(1) truncates the stream") {
      val source = new ScriptedSource(List(Right(hello), Right(lagged)))
      for _ <- Events.stream(source).take(1).runDrain
      yield assertTrue(source.closeCount == 1)
    },
    test("close() is observed exactly once after natural end-of-stream") {
      val source = new ScriptedSource(List(Right(hello)))
      for _ <- Events.stream(source).runDrain
      yield assertTrue(source.closeCount == 1)
    },
    // The two paths a leak actually hides on. The tests above all end the stream by returning
    // `None` or by truncating it, so neither the failure release nor the interruption canceler was
    // exercised — and a leaked SSE connection is invisible to every other assertion here.
    test("close() is observed exactly once when a poll throws") {
      val source =
        new ScriptedSource(List(Right(hello), Left(RiftError.CommunicationError("x", None))))
      for _ <- Events.stream(source).runDrain.exit
      yield assertTrue(source.closeCount == 1)
    },
    test("close() is observed when the consuming fiber is interrupted mid-poll") {
      // Parks inside poll() the way a real quiet SSE connection does, so interruption has to go
      // through `attemptBlockingCancelable`'s canceler rather than a convenient stream boundary.
      val source = new ParkingSource
      for
        fiber <- Events.stream(source).runDrain.fork
        _ <- source.parked.await
        _ <- fiber.interrupt
        closed <- ZIO.succeed(source.closeCount)
      yield assertTrue(closed >= 1)
    }
  )

  /** Blocks in `poll()` until closed — the shape of a real connection with no traffic. */
  private final class ParkingSource extends EventSource:
    val parked: Promise[Nothing, Unit] =
      Unsafe.unsafe(implicit u => Promise.unsafe.make(FiberId.None))
    private val gate = new java.util.concurrent.CountDownLatch(1)
    private val closes = new AtomicInteger(0)

    def poll(): Option[RiftEvent] =
      Unsafe.unsafe(implicit u =>
        Runtime.default.unsafe.run(parked.succeed(())).getOrThrowFiberFailure()
      )
      gate.await()
      None

    def close(): Unit =
      closes.incrementAndGet()
      gate.countDown()

    def closeCount: Int = closes.get()
