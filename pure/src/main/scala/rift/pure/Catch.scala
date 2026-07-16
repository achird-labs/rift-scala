package rift.pure

import rift.RiftError

/** The gate-testable seam every effectful method in this package runs through: only the modeled
  * `RiftError` becomes a value, anything else (a genuine defect) propagates unchanged (DESIGN.md
  * §5.11). Mirrors the ZIO backend's `refineToOrDie[RiftError]` and the Cats backend's plain
  * rethrow — this is `pure`'s equivalent boundary, just without an effect system underneath it.
  */
private[pure] def catchRiftError[A](op: => A): Either[RiftError, A] =
  try Right(op)
  catch case e: RiftError => Left(e)
