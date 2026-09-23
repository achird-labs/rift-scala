package rift.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite

import rift.RiftError
import rift.bridge.{ContainerConfig, EmbeddedConfig, SpawnConfig, UpstreamTrust}

/** #193 — a trust setting rift-java refuses is a caller's configuration mistake, so the `Resource`
  * must raise a `RiftError`, not a bare `IllegalArgumentException`. Every refusal fires while the
  * options are built, before an engine is touched, so this needs no engine.
  */
class UpstreamTrustSpec extends CatsEffectSuite:

  private val pem = "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----"

  private def assertInvalid(result: IO[Either[Throwable, Unit]]): IO[Unit] =
    result.map {
      case Left(_: RiftError.InvalidDefinition) => ()
      case other => fail(s"expected RiftError.InvalidDefinition, got $other")
    }

  test("embedded with an inline PEM without a certificate block fails as InvalidDefinition"):
    assertInvalid(
      Rift
        .embedded[IO](EmbeddedConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem"))))
        .use_
        .attempt
    )

  test("spawn with an inline PEM fails as InvalidDefinition"):
    assertInvalid(
      Rift.spawn[IO](SpawnConfig(upstreamTrust = Some(UpstreamTrust.CaPem(pem)))).use_.attempt
    )

  // #194 — refused while the container is configured, before Docker is touched.
  test("container with an inline PEM without a certificate block fails as InvalidDefinition"):
    assertInvalid(
      Rift
        .container[IO](ContainerConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem"))))
        .use_
        .attempt
    )
