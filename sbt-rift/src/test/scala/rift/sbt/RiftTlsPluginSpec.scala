package rift.sbt

import java.io.File

/** Pins the contract `sbt-rift` shares with `rift-scala-zio-testkit`.
  *
  * The two artifacts cannot reference each other — this is a Scala 2.12 sbt plugin and the testkit
  * is Scala 3 — so the only thing holding them together is the spelling of a handful of system
  * properties. `rift.zio.testkit.intercept` declares them as `DefaultCaPathProperty` /
  * `DefaultCaPasswordProperty`; the literals below are the other half of that contract, and this
  * test is what fails if either side is renamed alone.
  */
class RiftTlsPluginSpec extends munit.FunSuite {

  private val dir = new File("/tmp/rift-tls-spec")

  test("trustOptions names the CA properties the testkit reads back") {
    val options = RiftTlsPlugin.trustOptions(dir, "changeit")
    val caPath = new File(dir, RiftTlsPlugin.CaStoreName).getAbsolutePath
    assert(
      options.contains(s"-Drift.ca.p12=$caPath"),
      s"missing the rift.ca.p12 property in $options"
    )
    assert(
      options.contains("-Drift.ca.p12.password=changeit"),
      s"missing the rift.ca.p12.password property in $options"
    )
  }

  test("trustOptions points the fork at the generated truststore") {
    val options = RiftTlsPlugin.trustOptions(dir, "s3cret")
    val truststore = new File(dir, RiftTlsPlugin.TruststoreName).getAbsolutePath
    assertEquals(
      options,
      Seq(
        s"-Djavax.net.ssl.trustStore=$truststore",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        "-Djavax.net.ssl.trustStorePassword=s3cret",
        s"-Drift.ca.p12=${new File(dir, RiftTlsPlugin.CaStoreName).getAbsolutePath}",
        "-Drift.ca.p12.password=s3cret"
      )
    )
  }

  test("runtimeOptions adds --enable-preview only when asked") {
    assert(
      !RiftTlsPlugin
        .runtimeOptions(enablePreview = false, javaSpec = 21)
        .contains("--enable-preview")
    )
    assert(
      RiftTlsPlugin.runtimeOptions(enablePreview = true, javaSpec = 21).contains("--enable-preview")
    )
  }

  test("runtimeOptions gates --enable-native-access on both sides of the JDK boundary") {
    // Both sides are asserted regardless of which JDK runs this suite — passing an option the
    // runtime rejects stops the forked JVM from starting at all, so the gate matters.
    def nativeAccess(javaSpec: Int): Boolean =
      RiftTlsPlugin
        .runtimeOptions(enablePreview = false, javaSpec = javaSpec)
        .contains("--enable-native-access=ALL-UNNAMED")

    assert(!nativeAccess(17), "JDK 17 does not accept --enable-native-access")
    assert(!nativeAccess(20))
    assert(nativeAccess(21), "JDK 21 accepts --enable-native-access")
    assert(nativeAccess(25))
  }

  test("keytoolFailure is silent on success and quotes keytool's output on failure") {
    assertEquals(RiftTlsPlugin.keytoolFailure(0, Seq("-genkeypair"), "ignored"), None)

    val message = RiftTlsPlugin.keytoolFailure(1, Seq("-genkeypair", "-alias", "rift-ca"), "boom")
    assert(message.isDefined, "a non-zero exit must fail the build, not be swallowed")
    assert(message.get.contains("exit 1"), message.get)
    assert(message.get.contains("-genkeypair -alias rift-ca"), message.get)
    assert(message.get.contains("boom"), s"keytool's own output must survive: ${message.get}")
  }
}
