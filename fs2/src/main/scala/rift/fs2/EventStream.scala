package rift.fs2

import _root_.cats.effect.{Resource, Sync}
import _root_.fs2.Stream

import rift.bridge.{EventSource, RiftEvent}

/** The admin SSE event stream as an `fs2.Stream` (DESIGN.md D4/D5/D7, issue #87). Factored out of
  * the `events` extension so it is testable against a scripted `Resource[F, EventSource]` with no
  * live engine (`EventStreamSpec`) — mirrors `rift.zio.Events` 1:1, fs2-shaped: SSE is a blocking
  * push (`poll()` via `Sync[F].interruptible`), not `RequestStream`'s poll-on-interval.
  *
  * `source`'s `Resource` owns the connection (D5): its release closes it whether the stream ends
  * naturally (the engine closed the connection → `poll()` returns `None`, `unNoneTerminate` stops
  * the stream cleanly — `Lagged` needs no special-casing, it is already an ordinary element per
  * D6), a `poll()` throws, or the stream is cancelled.
  *
  * An elapsed **idle timeout is a failure, not a clean end**: the facade raises `EngineUnavailable`
  * from `hasNext`, so it reaches `F`'s error channel like any other `RiftError`. A long-lived
  * consumer against a quiet engine sees that, not termination.
  *
  * On cancellation the `Resource` finalizer is not a rescue for a stuck read — under CE3 an
  * `interruptible` region must finish before finalizers run. It unblocks because the facade's
  * `take()` parks on a `BlockingQueue` at 200ms granularity and maps `InterruptedException` into a
  * thrown `EngineUnavailable`, so the interrupt lands within that window.
  */
object EventStream:
  def build[F[_]: Sync](source: Resource[F, EventSource]): Stream[F, RiftEvent] =
    Stream.resource(source).flatMap { s =>
      Stream.repeatEval(Sync[F].interruptible(s.poll())).unNoneTerminate
    }
