package rift.zio.testkit

import java.net.URI

import zio.test.*

/** The pure transport decision `RiftTestKit.fromEnv` is built on (DESIGN.md §5.4) — asserted
  * directly, with no engine involved, since it is the one seam in this module that doesn't need
  * one. Everything else `RiftTestKit` exposes (`imposter`, `space`, the layers themselves) talks to
  * a real `Rift`/`ImposterHandle` and is covered by the conformance corpus (#6) against a live
  * engine instead.
  */
object RiftTestKitSpec extends ZIOSpecDefault:
  def spec = suite("RiftTestKit.transportFromEnv")(
    test("RIFT_ADMIN_URL set -> connect(uri)") {
      val result = RiftTestKit.transportFromEnv(Map("RIFT_ADMIN_URL" -> "http://h:2525"))
      assertTrue(result == Left(URI.create("http://h:2525")))
    },
    test("RIFT_ADMIN_URL absent -> embedded") {
      val result = RiftTestKit.transportFromEnv(Map.empty)
      assertTrue(result == Right(()))
    },
    test("RIFT_ADMIN_URL set but empty -> embedded (treated as unset)") {
      val result = RiftTestKit.transportFromEnv(Map("RIFT_ADMIN_URL" -> ""))
      assertTrue(result == Right(()))
    }
  )
