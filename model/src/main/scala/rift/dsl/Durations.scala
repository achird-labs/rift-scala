package rift.dsl

import scala.concurrent.duration.FiniteDuration

/** `500.millis to 2.seconds` — the range sugar used by `.withLatencyFault(probability, range)`
  * (DESIGN.md §5.1.3). Plain stdlib durations; no dependency.
  */
final case class DurationRange private[dsl] (min: FiniteDuration, max: FiniteDuration)

extension (min: FiniteDuration) def to(max: FiniteDuration): DurationRange = DurationRange(min, max)
