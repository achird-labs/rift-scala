package rift.zio.testkit

import java.net.InetSocketAddress

import zio.*
import zio.test.*

/** Gates `intercept.scopedGlobal` — the save/restore core that `systemProxyProps`,
  * `systemProxySelector` and `systemSslContext` are each a thin wrapper over — and the pure
  * `proxyPropUpdates` name mapping. It does not call those three itself: they bind `scopedGlobal`
  * to real JVM globals, and `TlsInterceptE2ESpec` is what proves that binding end-to-end.
  *
  * `scopedGlobal` is exercised against a local cell rather than this JVM's real system properties:
  * the module forks one test JVM that every spec shares, so a spec that genuinely set
  * `https.proxyHost` could steer an unrelated spec's HTTP client while it raced.
  */
object SystemWiringSpec extends ZIOSpecDefault:

  /** One JVM-global slot, standing in for a system property or a `setDefault` pair. */
  private final class Cell[A](initial: A):
    private var value: A = initial
    def read: A = value
    def write(a: A): Unit = value = a

  def spec = suite("SystemWiringSpec")(
    suite("scopedGlobal")(
      test("installs the value for the scope and restores the prior one on close") {
        val cell = new Cell[Option[String]](Some("before"))
        for during <- ZIO.scoped(
            intercept.scopedGlobal(cell.read, cell.write, Some("during")).as(cell.read)
          )
        yield assertTrue(during.contains("during"), cell.read.contains("before"))
      },
      test("restores prior absence rather than leaving the installed value behind") {
        val cell = new Cell[Option[String]](None)
        for during <- ZIO.scoped(
            intercept.scopedGlobal(cell.read, cell.write, Some("during")).as(cell.read)
          )
        yield assertTrue(during.contains("during"), cell.read.isEmpty)
      },
      test("restores when the scope is closed by interruption") {
        val cell = new Cell[Option[String]](Some("before"))
        for
          opened <- Promise.make[Nothing, Unit]
          fiber <- ZIO
            .scoped(
              intercept.scopedGlobal(cell.read, cell.write, Some("during")) *>
                opened.succeed(()) *> ZIO.never
            )
            .fork
          _ <- opened.await
          // `interrupt` (not `interruptFork`) waits for the fiber to finish unwinding, so the
          // scope's finalizer has already run by the time the assertion reads the cell.
          _ <- fiber.interrupt
        yield assertTrue(cell.read.contains("before"))
      },
      test("restores the prior value even when the scoped body fails") {
        val cell = new Cell[Option[String]](Some("before"))
        for exit <- ZIO
            .scoped(
              intercept.scopedGlobal(cell.read, cell.write, Some("during")) *>
                ZIO.fail("boom")
            )
            .exit
        yield assertTrue(exit.isFailure, cell.read.contains("before"))
      },
      test("nests: an inner scope restores the outer fixture's value, not the original") {
        // The `intercept` object documents nesting as safe; this is what that has to mean.
        val cell = new Cell[Option[String]](Some("original"))
        for
          seen <- ZIO.scoped {
            for
              _ <- intercept.scopedGlobal(cell.read, cell.write, Some("outer"))
              afterInner <- ZIO.scoped(
                intercept.scopedGlobal(cell.read, cell.write, Some("inner")).as(cell.read)
              )
            yield (afterInner, cell.read)
          }
          (duringInner, afterInnerClosed) = seen
        yield assertTrue(
          duringInner.contains("inner"),
          afterInnerClosed.contains("outer"),
          cell.read.contains("original")
        )
      }
    ),
    suite("proxyPropUpdates")(
      test("maps the intercept address onto the https proxy properties only, by default") {
        val updates =
          intercept.proxyPropUpdates(InetSocketAddress.createUnresolved("127.0.0.1", 8123), false)
        assertTrue(
          updates == Map(
            "https.proxyHost" -> "127.0.0.1",
            "https.proxyPort" -> "8123",
            "http.nonProxyHosts" -> ""
          )
        )
      },
      test("clears http.nonProxyHosts so a localhost upstream cannot bypass the proxy") {
        // The JDK's default exclusion list is `localhost|127.*|[::1]|0.0.0.0|[::0]` and applies to
        // https too, so leaving it set would route a local upstream direct — the fixture would
        // report success having intercepted nothing.
        val updates =
          intercept.proxyPropUpdates(InetSocketAddress.createUnresolved("127.0.0.1", 8123), true)
        assertTrue(updates.get("http.nonProxyHosts").contains(""))
      },
      test("adds the http proxy properties when includeHttp is set") {
        val updates =
          intercept.proxyPropUpdates(InetSocketAddress.createUnresolved("127.0.0.1", 8123), true)
        assertTrue(
          updates == Map(
            "https.proxyHost" -> "127.0.0.1",
            "https.proxyPort" -> "8123",
            "http.nonProxyHosts" -> "",
            "http.proxyHost" -> "127.0.0.1",
            "http.proxyPort" -> "8123"
          )
        )
      }
    )
  )
