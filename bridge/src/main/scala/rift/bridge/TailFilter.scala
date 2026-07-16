package rift.bridge

import rift.model.FlowId

/** A server-side filter for the cursor request tail (DESIGN.md §5.3, D6), mirroring exactly what
  * the facade's `MatchClause` can express — `header(name, value)` and `flowId` — and nothing more.
  * The engine advances the cursor past `match`-rejected entries, so a filtered tail never re-scans
  * the journal; that is the whole point of filtering server-side rather than client-side over the
  * unfiltered page.
  *
  * Deliberately narrow: it does **not** accept the model's full `RequestMatch` predicate
  * vocabulary, because translating the richer predicates onto `MatchClause` would silently drop the
  * ones it cannot express — a lossy filter would violate the "signalled, not hidden" contract the
  * tail is built on. Consumers needing richer predicates filter client-side over the unfiltered
  * tail (`rift.fs2.pipes.matching`, or a `collect`/`filter` over the ZIO stream). A richer facade
  * `MatchClause` (and a `Space`-scoped cursor) is a tracked upstream ask.
  */
enum TailFilter:
  case Header(name: String, value: String)
  case Flow(flowId: FlowId)
