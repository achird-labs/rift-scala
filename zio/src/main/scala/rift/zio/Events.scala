package rift.zio

import zio.*
import zio.stream.ZStream

import rift.RiftError
import rift.bridge.{EventSource, RiftEvent}

/** The admin SSE event stream (DESIGN.md D4/D5/D6/D7, issue #87). Factored out of `Rift.events` so
  * it is testable against a scripted `EventSource` with no live engine (`EventsSpec`) — SSE-shaped:
  * a blocking push (`poll()`), not `RequestTail`'s poll-on-interval.
  *
  * Acquire and release both run on the blocking pool: opening a subscription is a real network
  * call, and `close()` on release performs real blocking teardown — mirrors `intercept`'s scoped
  * lifecycle. `close()` on release is `orDie`: a teardown failure is a defect, not a value the
  * caller recovers. Each subscription is its own connection (D5) — never tied to the outer `Rift`
  * scope.
  *
  * `Lagged` needs no special-casing here: it is already an ordinary `RiftEvent` element (D6), so it
  * flows through `poll()` like any other. Only the two terminal shapes need handling —
  * `attemptBlockingCancelable`'s cancel callback closes the source, so a pending `poll()` unblocks
  * promptly on interruption (the facade's `SseEventStream` polls a bounded queue,
  * `POLL_GRANULARITY_MS`, verified via `javap`); engine-side end (`poll` returning `None`) surfaces
  * as clean stream termination; a thrown `RiftError` fails the stream typed.
  */
object Events:
  def stream(acquire: => EventSource): ZStream[Any, RiftError, RiftEvent] =
    ZStream
      .acquireReleaseWith(blockingIO(acquire))(source => ZIO.attemptBlocking(source.close()).orDie)
      .flatMap: source =>
        ZStream.repeatZIOOption:
          ZIO
            // `attemptBlocking`, matching the release above and `Rift.intercept`'s teardown:
            // `close()` shuts a socket stream and interrupts a reader thread, which is blocking
            // work and does not belong on a compute thread even inside the interrupt path.
            .attemptBlockingCancelable(source.poll())(ZIO.attemptBlocking(source.close()).ignore)
            .refineToOrDie[RiftError]
            .asSomeError
            .flatMap:
              case Some(ev) => ZIO.succeed(ev)
              case None => ZIO.fail(None) // clean end-of-stream
