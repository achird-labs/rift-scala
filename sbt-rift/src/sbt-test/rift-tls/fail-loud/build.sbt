lazy val root = (project in file("."))
  .enablePlugins(RiftTlsPlugin)
  .settings(
    scalaVersion := "2.12.20",
    // keytool rejects a malformed distinguished name, so `-genkeypair` exits non-zero. The whole
    // point of the plugin's `sys.error` is that this stops the build instead of leaving a
    // half-built truststore to be discovered later as an opaque handshake failure.
    riftTlsDname := "this is not a valid dname",
    // Nothing may survive a failed generation: a leftover CA keystore would satisfy a later
    // existence check and permanently skip the step that never finished.
    TaskKey[Unit]("checkNothingLeftBehind") := {
      val dir = riftTlsDir.value
      val leftovers =
        Seq("rift-ca.p12", "rift-ca-cert.pem", "rift-truststore.p12", "rift-truststore.p12.building")
          .map(dir / _)
          .filter(_.exists())
      assert(leftovers.isEmpty, s"failed generation left artifacts behind: $leftovers")
    }
  )
