package rift.zio.testkit

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.CaMaterial

/** Gates the pure `CaSource` -> `CaPlan` decision that `tlsIntercept` is built on, so the
  * "build-generated CA when the build wired one, engine-generated otherwise" default is pinned
  * without booting an engine (the shape `RiftTestKit.transportFromEnv` uses).
  */
object InterceptTestConfigSpec extends ZIOSpecDefault:

  private val fixed = CaMaterial("cert-pem", "key-pem")

  def spec = suite("InterceptTestConfigSpec")(
    suite("caPlan")(
      test("BuildProps demands the build properties even when they are absent") {
        assertTrue(
          intercept.caPlan(CaSource.BuildProps, buildPropsPresent = true) == CaPlan.FromBuildProps,
          intercept.caPlan(CaSource.BuildProps, buildPropsPresent = false) == CaPlan.FromBuildProps
        )
      },
      test("Generated always lets the engine mint its own CA") {
        assertTrue(
          intercept.caPlan(CaSource.Generated, buildPropsPresent = true) == CaPlan.EngineGenerated,
          intercept.caPlan(CaSource.Generated, buildPropsPresent = false) == CaPlan.EngineGenerated
        )
      },
      test("BuildPropsOrGenerated prefers the build CA and falls back to an engine-generated one") {
        assertTrue(
          intercept.caPlan(
            CaSource.BuildPropsOrGenerated,
            buildPropsPresent = true
          ) == CaPlan.FromBuildProps,
          intercept.caPlan(
            CaSource.BuildPropsOrGenerated,
            buildPropsPresent = false
          ) == CaPlan.EngineGenerated
        )
      },
      test("Explicit carries the caller's material through unchanged") {
        assertTrue(
          intercept.caPlan(CaSource.Explicit(fixed), buildPropsPresent = false) == CaPlan.Fixed(
            fixed
          )
        )
      }
    ),
    suite("resolveCa")(
      // caPlan proves only which branch is CHOSEN. These prove the branch does what it says —
      // a `Fixed` case that dropped its material, or a swap of the two non-default branches,
      // would satisfy every caPlan assertion above and still be wrong.
      test("Explicit hands the caller's own material to the engine") {
        for material <- intercept.resolveCa(CaSource.Explicit(fixed))
        yield assertTrue(material.contains(fixed))
      },
      test("Generated asks the engine to mint its own") {
        for material <- intercept.resolveCa(CaSource.Generated)
        yield assertTrue(material.isEmpty)
      },
      test("BuildProps fails when the build wired no CA, rather than silently generating one") {
        // This JVM has no `rift.ca.p12` (only a module using RiftTlsPlugin would), so this is the
        // unwired case — and the contract is a typed failure, not a quiet fallback.
        for exit <- intercept.resolveCa(CaSource.BuildProps).exit
        yield assertTrue(exit match
          case Exit.Failure(cause) =>
            cause.failures.exists(_.isInstanceOf[RiftError.InvalidDefinition])
          case Exit.Success(_) => false
        )
      }
    ),
    test("the default config generates no CA wiring beyond the proxy properties") {
      val config = InterceptTestConfig()
      assertTrue(
        config.ca == CaSource.BuildPropsOrGenerated,
        config.proxyProps,
        !config.includeHttpProxy,
        !config.proxySelector,
        !config.sslContext
      )
    }
  )
