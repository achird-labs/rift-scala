package rift.pure

import munit.FunSuite

import rift.RiftError

/** The swallow-taxonomy gate (DESIGN.md §5.11): only the modeled `RiftError` may become an `Either`
  * value. Anything else is a defect and must keep propagating — a non-`RiftError` caught here would
  * be exactly the "gate fails open" shape this module exists to prevent.
  */
class CatchSpec extends FunSuite:

  test("a successful op becomes Right"):
    assertEquals(catchRiftError("ok"), Right("ok"))

  test("a thrown RiftError is caught into Left with the same case"):
    val err = RiftError.EngineError(1, "x")
    assertEquals(catchRiftError(throw err), Left(err))

  test("a non-RiftError throwable is NOT caught — it propagates as a defect"):
    intercept[IllegalStateException] {
      catchRiftError(throw new IllegalStateException("boom"))
    }
