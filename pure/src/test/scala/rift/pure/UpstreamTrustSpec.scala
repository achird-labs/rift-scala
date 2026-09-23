package rift.pure

import munit.FunSuite

import rift.RiftError
import rift.bridge.{ContainerConfig, EmbeddedConfig, SpawnConfig, UpstreamTrust}

/** #193 — a trust setting rift-java refuses is a caller's configuration mistake, so it must come
  * back as a `Left`, not escape `catchRiftError` as a thrown `IllegalArgumentException`. Every
  * refusal fires while the options are built, before an engine is touched, so this needs no engine.
  */
class UpstreamTrustSpec extends FunSuite:

  private val pem = "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----"

  private def assertInvalid(result: Either[RiftError, Rift]): Unit =
    result match
      case Left(_: RiftError.InvalidDefinition) => ()
      case other => fail(s"expected Left(RiftError.InvalidDefinition), got $other")

  test("embedded with an inline PEM without a certificate block is Left(InvalidDefinition)"):
    assertInvalid(
      Rift.embedded(EmbeddedConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem"))))
    )

  test("spawn with an inline PEM is Left(InvalidDefinition)"):
    assertInvalid(Rift.spawn(SpawnConfig(upstreamTrust = Some(UpstreamTrust.CaPem(pem)))))

  // #194 — refused while the container is configured, before Docker is touched.
  test("container with an inline PEM without a certificate block is Left(InvalidDefinition)"):
    assertInvalid(
      Rift.container(ContainerConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem"))))
    )
