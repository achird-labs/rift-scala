package rift.bridge

import rift.model.RecordedRequest

/** An element of the signal-carrying request tail (`requestEvents`/`events`, DESIGN.md §5.3, D6).
  * The plain `requests` tail stays `RecordedRequest`-valued (the 90% API); this additive variant
  * surfaces the two observable conditions on `RecordedPage` as elements rather than hiding them in
  * docs or killing the stream:
  *
  *   - `Truncated` — the page reported `truncated = true`: retention evicted journal entries the
  *     tail never saw. Real loss, emitted once per truncated page (the engine reports no count, so
  *     the signal deliberately carries no fake payload).
  *   - `Degraded` — a read returned no stable index (`nextIndex == None`): the tail holds its
  *     cursor and delivery is at-least-once until an index returns. Emitted once, on the transition
  *     *into* the degraded state — not once per poll.
  *   - `Restored` — a stable index returned after a degraded stretch. Emitted once, on the
  *     transition back.
  *
  * Loss is an element, never a stream error: a truncation must not kill an otherwise-healthy tail,
  * and a consumer that wants to fail can lift a `Truncated`/`Degraded` into an error downstream.
  * `requests` is derived as `events.collect { case Received(r) => r }`, so the two views can never
  * drift.
  */
enum TailEvent:
  case Received(request: RecordedRequest)
  case Truncated
  case Degraded
  case Restored
