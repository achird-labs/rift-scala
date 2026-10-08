package rift.zio.testkit

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.{CaMaterial, ContainerConfig, InterceptConfig}

/** Gates the pure `CaSource` -> `CaPlan` decision that `tlsIntercept` is built on, so the
  * "build-generated CA when the build wired one, engine-generated otherwise" default is pinned
  * without booting an engine (the shape `RiftTestKit.transportFromEnv` uses).
  */
object InterceptTestConfigSpec extends ZIOSpecDefault:

  private val fixed = CaMaterial("cert-pem", "key-pem")
  private val other = CaMaterial("other-cert", "other-key")

  private def plan(c: ContainerConfig, ca: Option[CaMaterial], i: InterceptConfig) =
    InterceptEngine.containerPlan(c, ca, i).fold(e => throw e, identity)

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
      // #205: an absent CA is now the ephemeral, keyless one, so the testkit asks for the
      // key-returning mode explicitly — its behaviour (caMaterial present) is unchanged.
      test("Generated asks the engine to mint its own and hand back the key") {
        for material <- intercept.resolveCa(CaSource.Generated)
        yield assertTrue(material.contains(CaMaterial.Generated))
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
    // #205: where the resolved CA goes depends on how the container gets its listener.
    suite("InterceptEngine.container")(
      test("a launched listener takes the CA at launch and keeps the default InterceptConfig") {
        val (cc, ic) = plan(
          ContainerConfig(interceptPort = Some(4700)),
          Some(fixed),
          InterceptConfig()
        )
        assertTrue(
          cc.interceptCa.contains(fixed),
          cc.interceptPort.contains(4700),
          ic == InterceptConfig()
        )
      },
      test(
        "a launched listener with Generated launches an ephemeral CA: the key cannot be returned"
      ) {
        val (cc, ic) = plan(
          ContainerConfig(interceptPort = Some(4700)),
          Some(CaMaterial.Generated),
          InterceptConfig()
        )
        assertTrue(cc.interceptCa.isEmpty, ic == InterceptConfig())
      },
      test("a launched listener with no CA, and a runtime one with Generated, pass through") {
        val launched = ContainerConfig(interceptPort = Some(4700))
        val runtime = ContainerConfig(exposedInterceptPort = Some(4700))
        val (cc1, ic1) = plan(launched, None, InterceptConfig())
        val (cc2, ic2) =
          plan(runtime, Some(CaMaterial.Generated), InterceptConfig(port = 4700))
        assertTrue(
          cc1 == launched,
          ic1 == InterceptConfig(),
          cc2 == runtime,
          ic2 == InterceptConfig(port = 4700, ca = Some(CaMaterial.Generated))
        )
      },
      test(
        "a CA already on ContainerConfig.interceptCa survives a Generated or absent resolution"
      ) {
        val own = ContainerConfig(interceptPort = Some(4700), interceptCa = Some(fixed))
        val (cc1, _) = plan(own, Some(CaMaterial.Generated), InterceptConfig())
        val (cc2, _) = plan(own, None, InterceptConfig())
        val (cc3, _) = plan(own, Some(fixed), InterceptConfig())
        assertTrue(cc1 == own, cc2 == own, cc3 == own)
      },
      test("two different CAs for a launched listener are refused, not one silently dropped") {
        val own = ContainerConfig(interceptPort = Some(4700), interceptCa = Some(fixed))
        assertTrue(
          InterceptEngine.containerPlan(own, Some(other), InterceptConfig()) match
            case Left(RiftError.InvalidDefinition(msg, _)) => msg.contains("disagree")
            case _ => false
        )
      },
      test("a runtime-started listener takes the CA in its start, the container untouched") {
        val container = ContainerConfig(exposedInterceptPort = Some(4700))
        val (cc, ic) = plan(
          container,
          Some(fixed),
          InterceptConfig(host = "0.0.0.0", port = 4700)
        )
        assertTrue(
          cc == container,
          ic == InterceptConfig(host = "0.0.0.0", port = 4700, ca = Some(fixed))
        )
      }
    ),
    // #205: the engine seam — the CA the fixture resolves reaches the engine, with the intercept
    // config. A recording engine that then fails keeps this engine-free on every JDK.
    test("tlsIntercept hands the resolved CA and the intercept config to its engine") {
      final class Recording extends InterceptEngine:
        @volatile var seen: Option[(Option[CaMaterial], InterceptConfig)] = None
        def acquire(ca: Option[CaMaterial], intercept: InterceptConfig) =
          ZIO.succeed { seen = Some(ca -> intercept) } *>
            ZIO.fail(RiftError.EngineUnavailable("recording engine: no engine here", None))
      val explicit = new Recording
      val generated = new Recording
      for
        e1 <- ZIO.scoped(
          intercept
            .tlsIntercept(
              InterceptTestConfig(
                intercept = InterceptConfig(port = 4700),
                ca = CaSource.Explicit(fixed)
              ),
              explicit
            )
            .build
            .exit
        )
        e2 <- ZIO.scoped(
          intercept.tlsIntercept(InterceptTestConfig(ca = CaSource.Generated), generated).build.exit
        )
      yield assertTrue(
        explicit.seen.contains(Some(fixed) -> InterceptConfig(port = 4700)),
        generated.seen.contains(Some(CaMaterial.Generated) -> InterceptConfig()),
        e1.isFailure,
        e2.isFailure
      )
    },
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
