package rift.bridge

import munit.FunSuite

import java.nio.file.{Files, Paths}

import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.json.Json
import rift.model.EngineInfo

import io.github.achirdlabs.rift.UpstreamTrust as JUpstreamTrust
import io.github.achirdlabs.rift.testcontainers.RiftContainerProbe

/** Issue #176 — outbound TLS trust for proxy stubs (engine 0.18.0; rift-java 0.3.0 #225). The
  * bridge configs carry it to the facade's options unchanged, and `EngineInfo` exposes the
  * `serveOptions` a caller feature-detects it by.
  */
class UpstreamTrustSpec extends FunSuite:

  private val pem = "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----"

  test("EmbeddedConfig carries every trust kind to EmbeddedOptions"):
    val file = Paths.get("/etc/rift/ca.pem")
    assertEquals(
      EmbeddedConfig(upstreamTrust =
        Some(UpstreamTrust.CaFile(file))
      ).toOptions.upstreamTrust.toScala,
      Some(JUpstreamTrust.CaFile(file))
    )
    assertEquals(
      EmbeddedConfig(upstreamTrust =
        Some(UpstreamTrust.CaPem(pem))
      ).toOptions.upstreamTrust.toScala,
      Some(JUpstreamTrust.CaPem(pem))
    )
    assertEquals(
      EmbeddedConfig(upstreamTrust =
        Some(UpstreamTrust.SkipVerify)
      ).toOptions.upstreamTrust.toScala,
      Some(JUpstreamTrust.SkipVerify())
    )

  test("SpawnConfig carries a CA file and skip-verify to SpawnOptions"):
    val file = Paths.get("/etc/rift/ca.pem")
    assertEquals(
      SpawnConfig(upstreamTrust = Some(UpstreamTrust.CaFile(file))).toOptions.upstreamTrust.toScala,
      Some(JUpstreamTrust.CaFile(file))
    )
    assertEquals(
      SpawnConfig(upstreamTrust = Some(UpstreamTrust.SkipVerify)).toOptions.upstreamTrust.toScala,
      Some(JUpstreamTrust.SkipVerify())
    )

  // The engine CLI has no inline-PEM flag, so the facade refuses one for spawn at build time.
  test("SpawnConfig refuses an inline PEM, which the engine CLI cannot take"):
    intercept[IllegalArgumentException](
      SpawnConfig(upstreamTrust = Some(UpstreamTrust.CaPem(pem))).toOptions
    )

  test("no trust set leaves the facade options unset"):
    assertEquals(EmbeddedConfig().toOptions.upstreamTrust.toScala, None)
    assertEquals(SpawnConfig().toOptions.upstreamTrust.toScala, None)

  test("an inline PEM without a certificate block is refused"):
    intercept[IllegalArgumentException](
      EmbeddedConfig(upstreamTrust = Some(UpstreamTrust.CaPem("junk"))).toOptions
    )

  // #193 — the facade refuses these with a bare IllegalArgumentException while the options are
  // built. The connector must surface that as the typed InvalidDefinition, not leak it as a defect.
  // Every refusal fires before an engine is touched, so none of these needs one.
  private def assertInvalid(open: => RiftConnector, mentions: String): Unit =
    val err = intercept[RiftError.InvalidDefinition](open)
    assert(err.getMessage.contains(mentions), err.getMessage)
    assert(err.getCause.isInstanceOf[IllegalArgumentException], String.valueOf(err.getCause))

  test("embedded with an inline PEM without a certificate block fails as InvalidDefinition"):
    assertInvalid(
      RiftConnector.embedded(
        EmbeddedConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem")))
      ),
      "BEGIN CERTIFICATE"
    )

  test("spawn with an inline PEM fails as InvalidDefinition"):
    assertInvalid(
      RiftConnector.spawn(SpawnConfig(upstreamTrust = Some(UpstreamTrust.CaPem(pem)))),
      "inline CA PEM"
    )

  test("spawn with trust on an engine version older than 0.18.0 fails as InvalidDefinition"):
    assertInvalid(
      RiftConnector.spawn(
        SpawnConfig(version = "0.17.0", upstreamTrust = Some(UpstreamTrust.SkipVerify))
      ),
      "0.18.0"
    )

  // #194 — the container transport (rift-java 0.3.1 #248). `configuredContainer` is exactly what
  // `container` starts, so reading its env and CA copy shows what the engine would get, with no
  // Docker involved.
  private def configured(trust: Option[UpstreamTrust]): RiftContainerProbe.Configured =
    RiftContainerProbe.configured(
      RiftConnector.configuredContainer(ContainerConfig(upstreamTrust = trust))
    )

  private val CaFileEnv = "RIFT_UPSTREAM_CA_FILE"
  private val SkipVerifyEnv = "RIFT_UPSTREAM_TLS_SKIP_VERIFY"

  test("ContainerConfig with a CA file copies it into the container and names it to the engine"):
    val file = Files.createTempFile("rift-upstream-ca", ".pem")
    try
      Files.writeString(file, pem)
      val c = configured(Some(UpstreamTrust.CaFile(file)))
      assertEquals(c.env.get(CaFileEnv), Some(RiftContainerProbe.UpstreamCaPath))
      assertEquals(c.env.get(SkipVerifyEnv), None)
      assertEquals(c.caCopy, Some(pem))
    finally Files.deleteIfExists(file)

  test("ContainerConfig with an inline PEM writes it into the container"):
    val c = configured(Some(UpstreamTrust.CaPem(pem)))
    assertEquals(c.env.get(CaFileEnv), Some(RiftContainerProbe.UpstreamCaPath))
    assertEquals(c.env.get(SkipVerifyEnv), None)
    assertEquals(c.caCopy, Some(pem))

  test("ContainerConfig with skip-verify sets the engine flag and copies nothing"):
    val c = configured(Some(UpstreamTrust.SkipVerify))
    assertEquals(c.env.get(SkipVerifyEnv), Some("true"))
    assertEquals(c.env.get(CaFileEnv), None)
    assertEquals(c.caCopy, None)

  test("ContainerConfig without trust leaves the container untouched"):
    val c = configured(None)
    assertEquals(c.env.get(CaFileEnv), None)
    assertEquals(c.env.get(SkipVerifyEnv), None)
    assertEquals(c.caCopy, None)

  test("container with an inline PEM without a certificate block fails as InvalidDefinition"):
    assertInvalid(
      RiftConnector.container(
        ContainerConfig(upstreamTrust = Some(UpstreamTrust.CaPem("not a pem")))
      ),
      "BEGIN CERTIFICATE"
    )

  test("container with trust on an image tag older than 0.18.0 fails as InvalidDefinition"):
    assertInvalid(
      RiftConnector.container(
        ContainerConfig(
          image = Some("zainalpour/rift-proxy:v0.17.0"),
          upstreamTrust = Some(UpstreamTrust.SkipVerify)
        )
      ),
      "0.18.0"
    )

  test("EngineInfo reads and writes serveOptions, absent meaning none"):
    val raw =
      """{"version":"0.18.0","commit":"abc","features":["x"],"serveOptions":["upstreamCaPem"]}"""
    val info = Json.parse(raw).flatMap(EngineInfo.fromJson).fold(e => fail(e.toString), identity)
    assertEquals(info.serveOptions, Set("upstreamCaPem"))
    assertEquals(info.toJson.render, raw)
    val old = Json
      .parse("""{"version":"0.16.0","commit":"abc","features":[]}""")
      .flatMap(EngineInfo.fromJson)
      .fold(e => fail(e.toString), identity)
    assertEquals(old.serveOptions, Set.empty[String])
    assertEquals(old.toJson.get("serveOptions"), None)
