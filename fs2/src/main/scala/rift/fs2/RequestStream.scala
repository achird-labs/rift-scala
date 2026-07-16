package rift.fs2

import scala.concurrent.duration.FiniteDuration

import _root_.cats.effect.Temporal
import _root_.cats.syntax.all.*
import _root_.fs2.{Chunk, Stream}

import rift.bridge.RecordedPage
import rift.model.RecordedRequest

/** The cursor request tail (DESIGN.md §5.7, D6). Factored out of the `requestStream` extension so
  * it is testable against a scripted page source with no live engine (`RequestStreamSpec`) —
  * mirrors `rift.zio.RequestTail` 1:1, fs2-shaped.
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
  * deliberate degradation (cf. #22/#29); a typed `truncated`/degraded signal is a tracked
  * follow-up.
  */
object RequestStream:

  /** Never ends except by interruption (or the consumer stopping, e.g. `.take(n)`).
    * `nextIndex.orElse(cursor)` holds the previously-seen cursor when the page reports none — an
    * older/degraded engine read must never fall back to an array offset, since that is exactly the
    * silent-loss scheme this cursor design replaces.
    */
  def build[F[_]: Temporal](
      fetch: Option[Long] => F[RecordedPage],
      pollEvery: FiniteDuration
  ): Stream[F, RecordedRequest] =
    Stream
      .unfoldChunkEval(Option.empty[Long]): cursor =>
        fetch(cursor)
          .map(p => Some((Chunk.from(p.requests), p.nextIndex.orElse(cursor))))
          .flatTap(_ => Temporal[F].sleep(pollEvery))
