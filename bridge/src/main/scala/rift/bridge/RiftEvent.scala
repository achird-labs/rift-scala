package rift.bridge

import rift.model.{FlowId, Port, RecordedRequest}

/** The engine's admin SSE `/events` stream, mirroring the facade's `RiftEvent` ADT 1:1 (DESIGN.md
  * §9 item 2, D1, issue #87). Lives in `bridge` next to `TailEvent`/`RecordedPage` — these values
  * are born from facade records, not JSON documents (the envelopes expose no `toJson()`), so
  * field-by-field translation is the only faithful option (`FacadeDecode.riftEvent`). The one
  * embedded field that IS JSON — `RequestRecorded.request` — still goes through the D2 raw-JSON
  * seam (`FacadeDecode.recordedRequest`), unchanged from every other recorded request.
  *
  * `Unknown` is the total-mapping default for a facade subtype the pinned jar's sealed `RiftEvent`
  * cannot produce today (its `PermittedSubclasses` covers exactly `Hello`/`ImposterChanged`/
  * `RequestRecorded`/`Lagged`) but a newer `rift-java-core` minor might — novelty is an element,
  * not a stream-killer, the same losing-loud philosophy `TailEvent` already uses for `Truncated`/
  * `Degraded`/`Restored`.
  *
  * `Lagged` is an ordinary element here too (D6): the facade already models dropped-event loss this
  * way, so `FacadeDecode.riftEvent` needs no special-casing to preserve it — a lagged read is never
  * translated into a stream failure on any surface.
  */
enum RiftEvent:
  /** The connection preamble — the first element after a subscription opens. */
  case Hello(engineVersion: String, seqAtConnect: Long, types: Vector[String], port: Option[Port])
  case ImposterChanged(seq: Option[Long], action: ImposterAction, port: Option[Port])
  case RequestRecorded(
      seq: Option[Long],
      port: Port,
      index: Option[Long],
      flowId: Option[FlowId],
      request: RecordedRequest
  )

  /** The engine dropped events the subscriber didn't keep up with — a signal, never a failure. */
  case Lagged(missed: Long)

  /** A facade `RiftEvent` subtype this build doesn't recognise (D2) — forward-compat only; the
    * pinned jar's `RiftEvent` is JVM-sealed to the four cases above, so this is unreachable today.
    */
  case Unknown(description: String, seq: Option[Long])

/** Mirrors the facade's `RiftEvent.ImposterChanged.Action` enum. */
enum ImposterAction:
  case Created, Replaced, StubsChanged, Deleted, AllDeleted
