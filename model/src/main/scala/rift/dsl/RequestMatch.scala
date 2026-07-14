package rift.dsl

import rift.model.Predicate

/** The verification vocabulary (DESIGN.md §5.1.3): any [[StubBuilder]] is a `RequestMatch`, so the
  * same expression that stubs a request can also verify it via `RequestMatcher`.
  */
trait RequestMatch:
  def predicates: Vector[Predicate]
