package rift.dsl

/** Phantom type tracking whether a [[StubBuilder]] has at least one response yet. A `Matching` stub
  * is only usable as a [[RequestMatch]]; only a `Complete` stub can be `.build` into a `Stub` or
  * handed to `imposter(...).stub(...)`.
  */
sealed trait StubPhase

object StubPhase:
  sealed trait Matching extends StubPhase
  sealed trait Complete extends StubPhase
