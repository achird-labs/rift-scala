package rift.zio

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.{EmbeddedConfig, SpawnConfig, UpstreamTrust}

/** #193 — a trust setting rift-java refuses is a caller's configuration mistake, so the layer must
  * fail with a typed `RiftError`, not die. Every refusal fires while the options are built, before
  * an engine is touched, so this needs no engine.
  */
object UpstreamTrustSpec extends ZIOSpecDefault:

  private val pem = "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----"

  private def failsTyped(layer: ZLayer[Any, RiftError, Rift]) =
    ZIO.scoped(layer.build).exit.map { exit =>
      assertTrue(exit.causeOption.flatMap(_.failureOption).exists {
        case _: RiftError.InvalidDefinition => true
        case _ => false
      })
    }

  def spec = suite("UpstreamTrust (zio)")(
    test("embedded with an inline PEM without a certificate block fails as InvalidDefinition"):
      failsTyped(
        Rift.embedded(EmbeddedConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem"))))
      )
    ,
    test("spawn with an inline PEM fails as InvalidDefinition"):
      failsTyped(Rift.spawn(SpawnConfig(upstreamTrust = Some(UpstreamTrust.CaPem(pem)))))
  )
