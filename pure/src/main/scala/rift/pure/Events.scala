package rift.pure

import rift.RiftError
import rift.bridge.{EventSource, RiftEvent}

/** The plain-Scala surface over `rift.bridge.EventSource` (DESIGN.md D4, issue #87), obtained from
  * `Rift.events`/`Rift.eventsUnsafe`. `next()` mirrors `EventSource.poll()` but never throws — a
  * `RiftError` becomes a `Left` instead, this module's boundary (`catchRiftError`). `close()` tears
  * the connection down; `AutoCloseable` so it is `Using`-friendly, same as `Intercept`.
  */
final class Events private[pure] (source: EventSource) extends AutoCloseable:
  def next(): Either[RiftError, Option[RiftEvent]] = catchRiftError(source.poll())
  def close(): Unit = source.close()
