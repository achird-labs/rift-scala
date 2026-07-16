package rift.zio

import zio.*
import zio.stream.ZStream

import rift.RiftError
import rift.bridge.{RecordedPage, TailEvent, TailStep}
import rift.model.RecordedRequest

/** The cursor request tail (DESIGN.md §5.3, D6). Factored out of `ImposterHandle.requests` so it is
  * testable against a scripted page source with no live engine (`RequestTailSpec`).
  *
  * A client-side `all.drop(offset)` scheme cannot be exactly-once: journal positions shift under
  * the engine's retention eviction, `clearRecorded`, and scoped clears, so an offset tail silently
  * skips or replays entries with no way to notice. The server-assigned cursor survives clears and
  * makes the one genuine loss case (`truncated`) explicit instead of hiding it.
  *
  * Delivery contract: **exactly-once while the engine exposes stable indices** (`nextIndex`
  * present) — the normal path. When it does not (an older engine, or a degraded/partial read that
  * returns `nextIndex == None`), the tail holds its cursor and keeps polling rather than falling
  * back to an offset; on the baseline (no cursor yet) that means re-reading the current journal, so
  * delivery degrades to **at-least-once**, never silent skip. Losing-loud over losing-silent is the
  * deliberate degradation (cf. #29). [[events]] surfaces these two conditions as typed `TailEvent`s
  * (`Truncated`/`Degraded`/`Restored`); [[stream]] is `events` with only the `Received` elements
  * kept, so the plain-`RecordedRequest` and signal-carrying views share one implementation and
  * can't drift.
  */
object RequestTail:

  /** The signal-carrying tail — never ends except by interruption. The cursor/dedup semantics live
    * in the shared, pure `rift.bridge.TailStep` (identical to the fs2 tail); this only threads the
    * effect and the poll interval. `TailStep` holds the previous cursor when a page reports no
    * `nextIndex`, so an older/degraded read never falls back to the array-offset scheme this design
    * replaces.
    */
  def events(
      fetch: Option[Long] => IO[RiftError, RecordedPage],
      pollEvery: Duration
  ): ZStream[Any, RiftError, TailEvent] =
    ZStream.unfoldChunkZIO(TailStep.initial): state =>
      fetch(state.cursor)
        .map { page =>
          val (evs, next) = TailStep.step(state, page)
          Some((Chunk.fromIterable(evs), next))
        }
        .tap(_ => ZIO.sleep(pollEvery))

  /** The plain request tail — `events` with the control signals dropped. */
  def stream(
      fetch: Option[Long] => IO[RiftError, RecordedPage],
      pollEvery: Duration
  ): ZStream[Any, RiftError, RecordedRequest] =
    events(fetch, pollEvery).collect { case TailEvent.Received(r) => r }
