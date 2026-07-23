package rift.bridge

import io.github.achirdlabs.rift.EventStream as JEventStream

/** Blocking pull side of the admin SSE event stream (`RiftConnector.events`, DESIGN.md D4/D7, issue
  * #87).
  *
  * Two distinct endings, and they are NOT the same (verified against the facade's `SseEventStream`
  * bytecode):
  *   - `None` — the engine closed the connection; the facade iterator's `hasNext == false`.
  *   - **thrown `RiftError.EngineUnavailable` — the idle timeout elapsed.** The facade turns an
  *     idle stream into `Signal.Failed(EngineUnavailable)` and `hasNext()` rethrows it, so a quiet
  *     engine FAILS this stream rather than ending it. That is the default behaviour of any
  *     long-lived consumer, so treat it as "reconnect", not "done".
  *
  * Any other thrown `RiftException` is translated by `FacadeBoundary` into `RiftError`, same as
  * every other bridge downcall.
  *
  * Single consumer: the facade's iterator keeps plain non-volatile cursor fields, so concurrent
  * `poll()` from two threads is a data race. Pull from one.
  */
trait EventSource extends AutoCloseable:
  /** Blocks until the next event. `None` = the engine closed the stream; an elapsed idle timeout
    * throws `RiftError.EngineUnavailable` instead — see the trait doc.
    */
  def poll(): Option[RiftEvent]

/** Live `EventSource` over one SSE connection (`RiftConnector.events`). Each instance owns its
  * connection independently of the `RiftConnector` that opened it (D5) — `close()` ends the
  * underlying iterator and, per the facade's `SseEventStream` (verified via `javap`), unblocks a
  * pending `poll()` promptly rather than leaving it hung. Blocking/throwing, `AutoCloseable` —
  * mirrors `RecordingConnector`'s shape.
  */
final class EventStreamConnector private[bridge] (underlying: JEventStream) extends EventSource:
  private val it = underlying.iterator()

  def poll(): Option[RiftEvent] =
    FacadeBoundary.run(Option.when(it.hasNext)(FacadeDecode.riftEvent(it.next())))

  def close(): Unit = FacadeBoundary.run(underlying.close())
