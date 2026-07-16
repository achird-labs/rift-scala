package rift.fs2

import scala.concurrent.duration.FiniteDuration

import _root_.cats.effect.Temporal
import _root_.cats.syntax.all.*
import _root_.fs2.{Chunk, Stream}

import rift.bridge.{RecordedPage, TailEvent, TailStep}
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
  * deliberate degradation (cf. #22/#29). [[events]] surfaces these two conditions as typed
  * `TailEvent`s (`Truncated`/`Degraded`/`Restored`); [[build]] is `events` with only the `Received`
  * elements kept, so the two views share one implementation (the pure `rift.bridge.TailStep`, also
  * driving the ZIO tail) and can't drift.
  */
object RequestStream:

  /** The signal-carrying tail — never ends except by interruption (or the consumer stopping, e.g.
    * `.take(n)`). Cursor/dedup semantics live in the shared, pure `rift.bridge.TailStep`;
    * `TailStep` holds the previous cursor when a page reports no `nextIndex`, so an older/degraded
    * read never falls back to the array-offset scheme this design replaces.
    */
  def events[F[_]: Temporal](
      fetch: Option[Long] => F[RecordedPage],
      pollEvery: FiniteDuration
  ): Stream[F, TailEvent] =
    Stream
      .unfoldChunkEval(TailStep.initial): state =>
        fetch(state.cursor)
          .map { page =>
            val (evs, next) = TailStep.step(state, page)
            Some((Chunk.from(evs), next))
          }
          .flatTap(_ => Temporal[F].sleep(pollEvery))

  /** The plain request tail — `events` with the control signals dropped. */
  def build[F[_]: Temporal](
      fetch: Option[Long] => F[RecordedPage],
      pollEvery: FiniteDuration
  ): Stream[F, RecordedRequest] =
    events(fetch, pollEvery).collect { case TailEvent.Received(r) => r }
