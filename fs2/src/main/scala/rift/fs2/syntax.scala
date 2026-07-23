package rift.fs2

import scala.concurrent.duration.*

import _root_.cats.effect.{Sync, Temporal}
import _root_.fs2.Stream

import rift.cats.{ImposterHandle, Rift}
import rift.bridge.{EventStreamConfig, RecordedPage, RiftEvent, TailEvent, TailFilter}
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

  extension [F[_]: Sync](rift: Rift[F])
    /** The admin SSE event stream (DESIGN.md D4, issue #87) — each call opens its own connection
      * (D5), closed when the returned stream ends, fails, or is cancelled. See
      * [[EventStream.build]] for the full delivery/termination contract.
      *
      * '''Not available on the embedded transport.''' `RiftTransport.events()`'s default throws
      * `UnsupportedOperationException` and only the HTTP-backed transports (connect, spawn,
      * container) implement it. Poll `recorded`/`recordedSince` there instead.
      *
      * A quiet engine does not end this stream — it FAILS it: the facade turns an elapsed idle
      * timeout into `RiftError.EngineUnavailable`. Treat that as "reconnect", not "done".
      */
    def events(config: EventStreamConfig = EventStreamConfig()): Stream[F, RiftEvent] =
      EventStream.build(rift.eventSource(config))

  private def fetchPage[F[_]](
      handle: ImposterHandle[F],
      filters: Seq[TailFilter]
  ): Option[Long] => F[RecordedPage] =
    case None => handle.recordedPage(filters*)
    case Some(cursor) => handle.recordedSince(cursor, filters*)
