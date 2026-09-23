package rift.bridge

import munit.FunSuite

import java.nio.file.Paths

import scala.jdk.OptionConverters.*

import rift.json.Json
import rift.model.EngineInfo

import io.github.achirdlabs.rift.UpstreamTrust as JUpstreamTrust

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
