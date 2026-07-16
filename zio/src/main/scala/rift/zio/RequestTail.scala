package rift.zio

import zio.*
import zio.stream.ZStream

import rift.RiftError
import rift.bridge.RecordedPage
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
  * deliberate degradation (cf. #29); a typed `truncated`/degraded signal is a tracked follow-up.
  */
object RequestTail:

  /** Never ends except by interruption. `nextIndex.orElse(cursor)` holds the previously-seen cursor
    * when the page reports none — an older/degraded engine read must never fall back to an array
    * offset, since that is exactly the silent-loss scheme this cursor design replaces.
    */
  def stream(
      fetch: Option[Long] => IO[RiftError, RecordedPage],
      pollEvery: Duration
  ): ZStream[Any, RiftError, RecordedRequest] =
    ZStream.unfoldChunkZIO(Option.empty[Long]): cursor =>
      fetch(cursor)
        .map(p => Some((Chunk.fromIterable(p.requests), p.nextIndex.orElse(cursor))))
        .tap(_ => ZIO.sleep(pollEvery))
