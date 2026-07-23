package rift.fs2

import scala.concurrent.duration.*

import _root_.cats.effect.{Sync, Temporal}
import _root_.fs2.Stream

import rift.cats.{ImposterHandle, Rift, SpaceHandle}
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

  // These deliberately spell out their arities instead of taking defaults like the imposter
  // extensions above: Scala 3 rejects two overloaded variants of the same extension name in one
  // object when both carry default arguments ("two or more overloaded variants ... have default
  // arguments"). Explicit overloads keep `space.requestStream()` reading exactly like the imposter
  // form for callers, and keep both on the single `rift.fs2.syntax` import.
  extension [F[_]: Temporal](space: SpaceHandle[F])
    /** The same cursor-tracking poll as `ImposterHandle.requestStream`, scoped to one flow space.
      * `filters` narrow *within* the space, so this can never widen past its own flow.
      * `TailFilter.Flow` is rejected with `RiftError.InvalidDefinition` — the space already scopes
      * by flow and the facade refuses a second `flowId` clause.
      *
      * '''Not available on the embedded transport.''' That prepended clause is a server-side match
      * filter, which the FFM transport refuses outright, so the first poll fails with
      * `UnsupportedOperationException`. Use an HTTP-backed transport, or the space's one-shot
      * `recorded`. (The imposter-level stream has the opposite failure mode there — it returns, but
      * without a working cursor: upstream `rift-java#175`.)
      */
    def requestStream(
        pollEvery: FiniteDuration,
        filters: Seq[TailFilter]
    ): Stream[F, RecordedRequest] =
      RequestStream.build(fetchSpacePage(space, filters), pollEvery)

    def requestStream(pollEvery: FiniteDuration): Stream[F, RecordedRequest] =
      requestStream(pollEvery, Nil)

    /** Filters at the default poll interval — the arity that keeps `requestStream(filters = …)`
      * available here, since the imposter form gets it from a default argument this one cannot
      * have.
      */
    def requestStream(filters: Seq[TailFilter]): Stream[F, RecordedRequest] =
      requestStream(100.millis, filters)

    def requestStream(): Stream[F, RecordedRequest] = requestStream(100.millis, Nil)

    /** The signal-carrying view of this space's tail, mirroring `ImposterHandle.requestEvents`. */
    def requestEvents(pollEvery: FiniteDuration, filters: Seq[TailFilter]): Stream[F, TailEvent] =
      RequestStream.events(fetchSpacePage(space, filters), pollEvery)

    def requestEvents(pollEvery: FiniteDuration): Stream[F, TailEvent] =
      requestEvents(pollEvery, Nil)

    def requestEvents(filters: Seq[TailFilter]): Stream[F, TailEvent] =
      requestEvents(100.millis, filters)

    def requestEvents(): Stream[F, TailEvent] = requestEvents(100.millis, Nil)

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

  private def fetchSpacePage[F[_]](
      space: SpaceHandle[F],
      filters: Seq[TailFilter]
  ): Option[Long] => F[RecordedPage] =
    case None => space.recordedPage(filters*)
    case Some(cursor) => space.recordedSince(cursor, filters*)
