package rift.bridge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import munit.FunSuite

import rift.RiftError

import io.github.achirdlabs.rift.InterceptOptions as JInterceptOptions
import io.github.achirdlabs.rift.testcontainers.RiftContainerProbe

/** Issue #205 — the container transport either honours an `InterceptConfig` or refuses it, and can
  * launch its listener with a committed CA. Docker-free: the intercept decision is a pure function
  * of the transport's mode, and `configuredContainer` is exactly what `container` starts.
  */
class ContainerInterceptSpec extends FunSuite:

  private val attach = JInterceptOptions.attach("localhost", 32768)
  private val pem = CaMaterial.Pem("cert-pem", "key-pem")
  private val pemText = CaMaterial(
    "-----BEGIN CERTIFICATE-----\nC\n-----END CERTIFICATE-----",
    "-----BEGIN PRIVATE KEY-----\nK\n-----END PRIVATE KEY-----"
  )

  private def refusal(r: Either[RiftError, JInterceptOptions]): String =
    r match
      case Left(RiftError.InvalidDefinition(msg, _)) => msg
      case other => fail(s"expected InvalidDefinition, got $other")

  // ── the intercept decision ────────────────────────────────────────────────────────────────────
  test("a container-booted listener attaches for the default InterceptConfig"):
    assertEquals(
      RiftConnector.resolveIntercept(InterceptMode.Attach(attach), InterceptConfig()),
      Right(attach)
    )

  test("a container-booted listener refuses any InterceptConfig it cannot honour, naming why"):
    for config <- Seq(
        InterceptConfig(ca = Some(pem)),
        InterceptConfig(ca = Some(CaMaterial.Generated)),
        InterceptConfig(host = "0.0.0.0"),
        InterceptConfig(port = 4700)
      )
    do
      val msg = refusal(RiftConnector.resolveIntercept(InterceptMode.Attach(attach), config))
      assert(msg.contains("interceptPort"), msg)
      assert(msg.contains("interceptCa"), msg)

  test("a runtime start on a container refuses any host but a wildcard bind, which Docker maps"):
    for host <- Seq("127.0.0.1", "localhost", "::1", "[::1]", "::ffff:127.0.0.1", "10.1.2.3", "") do
      val msg = refusal(
        RiftConnector.resolveIntercept(
          InterceptMode.ContainerStart(Some(4700)),
          InterceptConfig(host = host, port = 4700)
        )
      )
      assert(msg.contains("0.0.0.0"), s"'$host': $msg")

  test("a runtime start on a container must use the port the container exposed"):
    for port <- Seq(0, 4701) do
      val msg = refusal(
        RiftConnector.resolveIntercept(
          InterceptMode.ContainerStart(Some(4700)),
          InterceptConfig(host = "0.0.0.0", port = port)
        )
      )
      assert(msg.contains("4700") && msg.contains(s"not $port"), msg)

  test("a runtime start on a container that exposed no intercept port is refused"):
    val msg = refusal(
      RiftConnector.resolveIntercept(
        InterceptMode.ContainerStart(None),
        InterceptConfig(host = "0.0.0.0", port = 4700)
      )
    )
    assert(msg.contains("exposedInterceptPort"), msg)

  test("a runtime start on a container accepts every wildcard spelling"):
    for host <- Seq("0.0.0.0", "::", "[::]") do
      assert(
        RiftConnector
          .resolveIntercept(
            InterceptMode.ContainerStart(Some(4700)),
            InterceptConfig(host = host, port = 4700)
          )
          .isRight,
        host
      )

  test("a runtime start on a container on a reachable host starts with the caller's config"):
    val r = RiftConnector.resolveIntercept(
      InterceptMode.ContainerStart(Some(4700)),
      InterceptConfig(host = "0.0.0.0", port = 4700, ca = Some(pem))
    )
    val json = r.fold(e => fail(e.toString), _.toJson.toJson)
    assert(json.contains("\"host\":\"0.0.0.0\""), json)
    assert(json.contains("\"port\":4700"), json)
    assert(json.contains("cert-pem"), json)

  test("the other transports start with the caller's config unchanged, loopback included"):
    val r = RiftConnector.resolveIntercept(InterceptMode.Start, InterceptConfig(ca = Some(pem)))
    assertEquals(
      r.map(_.toJson.toJson),
      Right("""{"host":"127.0.0.1","port":0,"caCertPem":"cert-pem","caKeyPem":"key-pem"}""")
    )

  // ── ContainerConfig.interceptCa / exposedInterceptPort ────────────────────────────────────────
  private def launched(config: ContainerConfig): Option[(String, String)] =
    RiftContainerProbe.launchedInterceptCa(RiftConnector.configuredContainer(config))

  test("interceptCa as PEM text launches the listener with that pair"):
    assertEquals(
      launched(ContainerConfig(interceptPort = Some(4700), interceptCa = Some(pemText))),
      Some((pemText.certPem, pemText.keyPem))
    )

  test("interceptCa as files is read here and copied into the container"):
    val cert = Files.createTempFile("rift-ca", ".pem")
    val key = Files.createTempFile("rift-ca", ".key")
    try
      Files.writeString(cert, "file-cert")
      Files.writeString(key, "file-key")
      assertEquals(
        launched(
          ContainerConfig(
            interceptPort = Some(4700),
            interceptCa = Some(CaMaterial.PemFiles(cert, key))
          )
        ),
        Some(("file-cert", "file-key"))
      )
    finally
      Files.deleteIfExists(cert)
      Files.deleteIfExists(key)

  test("interceptCa from a keystore launches the listener with the keystore's PEM pair"):
    val (ks, certDer) = selfSignedKeyStore()
    val Some((certPem, keyPem)) =
      launched(
        ContainerConfig(
          interceptPort = Some(4700),
          interceptCa = Some(CaMaterial.fromKeyStore(ks, "changeit".toCharArray))
        )
      ): @unchecked
    val decoded = java.util.Base64.getMimeDecoder.decode(
      certPem.replaceAll("-----(BEGIN|END) CERTIFICATE-----", "").replaceAll("\\s", "")
    )
    assert(
      java.util.Arrays.equals(decoded, certDer),
      "the launched certificate is not the keystore's"
    )
    assert(keyPem.contains("PRIVATE KEY"), keyPem.take(40))

  test("no interceptCa launches with the engine's own ephemeral CA"):
    assertEquals(launched(ContainerConfig(interceptPort = Some(4700))), None)

  private def invalid(config: ContainerConfig): String =
    intercept[RiftError.InvalidDefinition](RiftConnector.container(config)).getMessage

  test("interceptCa PEM text without a BEGIN block is refused before Docker is touched"):
    val msg = invalid(ContainerConfig(interceptPort = Some(4700), interceptCa = Some(pem)))
    assert(msg.contains("-----BEGIN"), msg)

  test("interceptCa without interceptPort is refused before Docker is touched"):
    val msg = invalid(ContainerConfig(interceptCa = Some(pemText)))
    assert(msg.contains("needs interceptPort"), msg)
    assert(msg.contains("InterceptConfig.ca"), msg)

  test("interceptCa = Generated is refused: a launched listener cannot hand back a key"):
    val msg =
      invalid(ContainerConfig(interceptPort = Some(4700), interceptCa = Some(CaMaterial.Generated)))
    assert(msg.contains("cannot hand its key back"), msg)

  test("interceptCa files that are not readable regular files are refused before Docker"):
    val readable = Files.createTempFile("rift-ca", ".pem")
    val dir = Files.createTempDirectory("rift-ca-dir")
    val missing = readable.resolveSibling("rift-ca-missing-" + java.lang.System.nanoTime + ".pem")
    try
      for (cert, key, bad) <- Seq(
          (missing, readable, missing),
          (readable, missing, missing),
          (dir, readable, dir)
        )
      do
        val msg = invalid(
          ContainerConfig(
            interceptPort = Some(4700),
            interceptCa = Some(CaMaterial.PemFiles(cert, key))
          )
        )
        assert(msg.contains("not readable") && msg.contains(bad.toString), msg)
    finally
      Files.deleteIfExists(readable)
      Files.deleteIfExists(dir)

  test("exposedInterceptPort exposes the port for a runtime-started listener"):
    val c = RiftConnector.configuredContainer(ContainerConfig(exposedInterceptPort = Some(4700)))
    assert(
      RiftContainerProbe.exposedPorts(c).contains(4700),
      RiftContainerProbe.exposedPorts(c).toString
    )

  test("interceptPort and exposedInterceptPort together are refused before Docker is touched"):
    val msg =
      invalid(ContainerConfig(interceptPort = Some(4700), exposedInterceptPort = Some(4701)))
    assert(msg.contains("cannot be combined"), msg)
    assert(msg.contains("interceptPort") && msg.contains("exposedInterceptPort"), msg)

  /** A throwaway self-signed key pair in a PKCS12 keystore, made by the JDK's `keytool` so no key
    * material is committed; returns the keystore and its certificate's DER.
    */
  private def selfSignedKeyStore(): (java.security.KeyStore, Array[Byte]) =
    val dir = Files.createTempDirectory("rift-ca-ks")
    val file: Path = dir.resolve("ca.p12")
    try
      val keytool = Path.of(java.lang.System.getProperty("java.home"), "bin", "keytool").toString
      val proc = new ProcessBuilder(
        keytool,
        "-genkeypair",
        "-alias",
        "ca",
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        "CN=rift-scala-test-ca",
        "-validity",
        "1",
        "-storetype",
        "PKCS12",
        "-keystore",
        file.toString,
        "-storepass",
        "changeit",
        "-keypass",
        "changeit",
        "-noprompt"
      ).redirectErrorStream(true).start()
      val output = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if proc.waitFor() != 0 then fail(s"keytool failed: $output")
      val ks = java.security.KeyStore.getInstance("PKCS12")
      scala.util.Using.resource(Files.newInputStream(file))(ks.load(_, "changeit".toCharArray))
      (ks, ks.getCertificate("ca").getEncoded)
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
