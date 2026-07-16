package rift.fs2

import scala.concurrent.duration.*

import _root_.cats.effect.Temporal
import _root_.fs2.Stream

import rift.cats.ImposterHandle
import rift.bridge.{RecordedPage, TailEvent, TailFilter}
import rift.model.RecordedRequest

/** The `fs2.Stream` surface on top of the cats module's `ImposterHandle[F]` (DESIGN.md §5.7, issue
  * #9).
  */
object syntax:
  extension [F[_]: Temporal](handle: ImposterHandle[F])
    /** Cursor-tracking poll (`recordedSince` over the stable journal index, DESIGN.md §5.3, D6);
      * emits each request exactly once while the engine reports a stable `nextIndex`. `filters` are
      * applied server-side (`TailFilter` → facade `MatchClause`). See [[RequestStream.build]] for
      * the full delivery contract.
      */
    def requestStream(
        pollEvery: FiniteDuration = 100.millis,
        filters: Seq[TailFilter] = Nil
    ): Stream[F, RecordedRequest] =
      RequestStream.build(fetchPage(handle, filters), pollEvery)

    /** The signal-carrying tail: the same cursor poll as `requestStream`, but each element is a
      * `TailEvent` so a caller can observe `Truncated`/`Degraded`/`Restored` explicitly.
      * `requestStream` is this stream through [[pipes.received]]. See [[RequestStream.events]].
      */
    def requestEvents(
        pollEvery: FiniteDuration = 100.millis,
        filters: Seq[TailFilter] = Nil
    ): Stream[F, TailEvent] =
      RequestStream.events(fetchPage(handle, filters), pollEvery)

  private def fetchPage[F[_]](
      handle: ImposterHandle[F],
      filters: Seq[TailFilter]
  ): Option[Long] => F[RecordedPage] =
    case None => handle.recordedPage(filters*)
    case Some(cursor) => handle.recordedSince(cursor, filters*)
