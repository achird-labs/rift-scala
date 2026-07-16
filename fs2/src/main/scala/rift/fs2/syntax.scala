package rift.fs2

import scala.concurrent.duration.*

import _root_.cats.effect.Temporal
import _root_.fs2.Stream

import rift.cats.ImposterHandle
import rift.model.RecordedRequest

/** The `fs2.Stream` surface on top of the cats module's `ImposterHandle[F]` (DESIGN.md §5.7, issue
  * #9).
  */
object syntax:
  extension [F[_]: Temporal](handle: ImposterHandle[F])
    /** Cursor-tracking poll (`recordedSince` over the stable journal index, DESIGN.md §5.3, D6);
      * emits each request exactly once while the engine reports a stable `nextIndex`. See
      * [[RequestStream.build]] for the full delivery contract.
      */
    def requestStream(pollEvery: FiniteDuration = 100.millis): Stream[F, RecordedRequest] =
      RequestStream.build(
        cursor => cursor.fold(handle.recordedPage)(handle.recordedSince),
        pollEvery
      )
