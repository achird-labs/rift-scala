package rift.bridge

import munit.FunSuite

import rift.RiftError

/** Issue #205, live against `rift-proxy` containers (Docker). Opt-in with `RIFT_IT=1`, the
  * convention rift-java's container ITs use; CI does not set it, and `ContainerInterceptSpec`
  * covers the same decisions without Docker.
  */
class ContainerInterceptIT extends FunSuite:

  override def munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def requireDocker(): Unit =
    assume(sys.env.get("RIFT_IT").contains("1"), "set RIFT_IT=1 to run the container ITs")

  private val port = 4700

  test("a runtime listener hands back a generated CA, and a launched one adopts it"):
    requireDocker()
    // A listener started at runtime, on an exposed port, bound where Docker maps.
    val runtime = RiftConnector.container(ContainerConfig(exposedInterceptPort = Some(port)))
    val pem =
      try
        val ic = runtime.intercept(
          InterceptConfig(host = "0.0.0.0", port = port, ca = Some(CaMaterial.Generated))
        )
        try ic.caMaterial.getOrElse(fail("a Generated CA must come back from a runtime start"))
        finally ic.close()
      finally runtime.close()

    // A listener launched with that CA committed: the default config attaches and carries it.
    val launched =
      RiftConnector.container(ContainerConfig(interceptPort = Some(port), interceptCa = Some(pem)))
    try
      val ic = launched.intercept()
      try
        assertEquals(ic.caPem.trim, pem.certPem.trim)
        assertEquals(ic.caMaterial, Some(pem))
      finally ic.close()
      // Anything the launched listener cannot honour is refused, not ignored.
      val refused =
        intercept[RiftError.InvalidDefinition](launched.intercept(InterceptConfig(port = 1)))
      assert(refused.getMessage.contains("interceptCa"), refused.getMessage)
    finally launched.close()

  test("a runtime start on a container refuses a loopback host and an unexposed port"):
    // Both are refused before anything starts, so the container's one listener is still free.
    requireDocker()
    val conn = RiftConnector.container(ContainerConfig(exposedInterceptPort = Some(port)))
    try
      val loopback =
        intercept[RiftError.InvalidDefinition](conn.intercept(InterceptConfig(port = port)))
      assert(loopback.getMessage.contains("0.0.0.0"), loopback.getMessage)
      val unexposed =
        intercept[RiftError.InvalidDefinition](
          conn.intercept(InterceptConfig(host = "0.0.0.0", port = port + 1))
        )
      assert(unexposed.getMessage.contains(s"exposes intercept port $port"), unexposed.getMessage)
    finally conn.close()
