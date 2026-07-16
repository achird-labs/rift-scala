package rift.bridge

/** The pure, shared transition of the cursor request tail — one implementation of the cursor +
  * signal semantics (DESIGN.md §5.3, D6), driven identically by the ZIO (`rift.zio.RequestTail`)
  * and fs2 (`rift.fs2.RequestStream`) unfolds so the two tails can never drift. Given the prior
  * `State` and a freshly-fetched `RecordedPage`, `step` computes the `TailEvent`s to emit and the
  * next state.
  *
  * `nextCursor = page.nextIndex.orElse(state.cursor)` holds the previous cursor when the page
  * reports none — an older/degraded read must never fall back to an array offset, the silent-loss
  * scheme this cursor design replaces. `Degraded`/`Restored` are emitted only on state
  * *transitions* (tracked by `State.degraded`), so a long degraded stretch is one element, not one
  * per poll. Per page the order is `Truncated?` (loss that preceded this data) then
  * `Degraded`/`Restored?` (the index state these elements were read under) then the `Received`
  * elements.
  */
object TailStep:

  final case class State(cursor: Option[Long], degraded: Boolean)

  val initial: State = State(cursor = None, degraded = false)

  def step(state: State, page: RecordedPage): (Vector[TailEvent], State) =
    val nowDegraded = page.nextIndex.isEmpty
    val truncated = Option.when(page.truncated)(TailEvent.Truncated)
    val transition =
      if nowDegraded && !state.degraded then Some(TailEvent.Degraded)
      else if !nowDegraded && state.degraded then Some(TailEvent.Restored)
      else None
    val received = page.requests.map(TailEvent.Received(_))
    val events = truncated.toVector ++ transition.toVector ++ received
    (events, State(page.nextIndex.orElse(state.cursor), nowDegraded))
