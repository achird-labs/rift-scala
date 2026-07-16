package rift.bridge

import rift.model.RecordedRequest

/** One page of the engine's stable recorded-request journal (DESIGN.md §5.3, D6). `nextIndex` is
  * the cursor to pass to `recordedSince` for the next page — `None` means the engine exposed no
  * stable index for this read (an older engine or a degraded one), in which case a caller must hold
  * its previous cursor rather than fall back to an array offset. `truncated` signals that retention
  * evicted entries the caller never saw — the one genuine loss case, surfaced rather than hidden.
  */
final case class RecordedPage(
    requests: Vector[RecordedRequest],
    nextIndex: Option[Long],
    truncated: Boolean
)
