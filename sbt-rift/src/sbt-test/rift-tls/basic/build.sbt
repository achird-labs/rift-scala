import java.io.FileInputStream
import java.security.KeyStore

lazy val root = (project in file("."))
  .enablePlugins(RiftTlsPlugin)
  .settings(
    scalaVersion := "2.12.20",
    // Proves the generator ran and produced both stores, with the CA under the expected alias.
    TaskKey[Unit]("checkGenerated") := {
      val dir = riftTlsDir.value
      val caStore = dir / "rift-ca.p12"
      val truststore = dir / "rift-truststore.p12"
      assert(caStore.exists(), s"CA keystore was not generated at $caStore")
      assert(truststore.exists(), s"truststore was not generated at $truststore")

      def load(f: File): KeyStore = {
        val ks = KeyStore.getInstance("PKCS12")
        val in = new FileInputStream(f)
        try ks.load(in, riftTlsPassword.value.toCharArray)
        finally in.close()
        ks
      }

      assert(load(caStore).containsAlias("rift-ca"), "CA keystore has no 'rift-ca' alias")
      val trust = load(truststore)
      assert(trust.containsAlias("rift-ca"), "truststore does not contain the rift CA")
      // Seeded from the JDK's cacerts, so it holds far more than the one CA we added.
      assert(trust.size() > 1, s"truststore has only ${trust.size()} entries; cacerts was not merged")
    },
    // Proves the forked test JVM is actually pointed at what was generated.
    TaskKey[Unit]("checkJavaOptions") := {
      val options = (Test / javaOptions).value
      val dir = riftTlsDir.value
      val expected = Seq(
        s"-Djavax.net.ssl.trustStore=${(dir / "rift-truststore.p12").getAbsolutePath}",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        s"-Drift.ca.p12=${(dir / "rift-ca.p12").getAbsolutePath}"
      )
      expected.foreach(o => assert(options.contains(o), s"missing $o in $options"))
      assert((Test / fork).value, "the test JVM must fork for these options to take effect")
    },
    // Regenerating would mint a CA the already-built truststore does not contain.
    TaskKey[Unit]("recordFingerprint") := {
      val caStore = riftTlsDir.value / "rift-ca.p12"
      IO.write(target.value / "ca-fingerprint.txt", caStore.lastModified().toString)
    },
    TaskKey[Unit]("checkUnchanged") := {
      val caStore = riftTlsDir.value / "rift-ca.p12"
      val before = IO.read(target.value / "ca-fingerprint.txt").trim
      assert(
        before == caStore.lastModified().toString,
        s"the CA was regenerated (was $before, now ${caStore.lastModified()})"
      )
    }
  )
