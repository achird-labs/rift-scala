package rift.sbt

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import scala.sys.process.{Process, ProcessLogger}

/** Build-side half of the rift TLS-MITM test fixtures (rift-scala #145).
  *
  * A system under test that builds its own HTTP client — a vendor SDK on Apache HttpClient, say —
  * reads `javax.net.ssl.trustStore` once, at first TLS init, and never consults anything a test
  * could hand it afterwards. So the CA that the intercept proxy mints leaf certificates from has to
  * be in a truststore on disk *before* the test JVM forks. That is not something a library can do
  * from inside the JVM it needs to have already configured, which is why it lives in a plugin.
  *
  * Enabling this on a module generates a throwaway CA and a truststore (the JDK's own `cacerts`
  * plus that CA) under `riftTlsDir`, forks the test JVM, and points it at both — including the
  * `rift.ca.p12` properties that `rift.zio.testkit.intercept.caFromBuildProps` reads back, so the
  * proxy and the truststore agree on one CA.
  *
  * {{{
  * lazy val myTests = (project in file("my-tests")).enablePlugins(RiftTlsPlugin)
  * }}}
  *
  * Nothing generated here is a secret: the CA is minted per checkout under `target/` and is only
  * ever trusted by this build's own forked test JVM.
  */
object RiftTlsPlugin extends AutoPlugin {

  override def requires: Plugins = JvmPlugin

  /** Opt-in: this forks the test JVM and rewrites its trust settings, which no module should get
    * merely for being in the build.
    */
  override def trigger: PluginTrigger = noTrigger

  object autoImport {
    val riftTlsDir: SettingKey[File] =
      settingKey[File]("Directory holding the generated rift CA and truststore.")
    val riftTlsPassword: SettingKey[String] =
      settingKey[String]("Password for the generated CA keystore and truststore.")
    val riftTlsDname: SettingKey[String] =
      settingKey[String]("Distinguished name for the generated CA certificate.")
    val riftTlsEnablePreview: SettingKey[Boolean] =
      settingKey[Boolean](
        "Add --enable-preview to the forked test JVM, for the JDK-21 embedded rift artifact."
      )
    val riftTlsGenerate: TaskKey[Seq[File]] =
      taskKey[Seq[File]]("Generate the rift CA keystore and truststore if they are absent.")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    riftTlsDir := target.value / "rift",
    riftTlsPassword := DefaultPassword,
    riftTlsDname := s"CN=rift-test-ca,O=${name.value}",
    riftTlsEnablePreview := false,
    riftTlsGenerate := generateTask.value,
    // A resource generator so the material exists before anything on the test classpath runs.
    // It deliberately reports no files: these are JVM inputs, not resources, and returning them
    // would package a private key into the module's test artifact.
    Test / resourceGenerators += riftTlsGenerate.taskValue,
    // `--enable-native-access` and the trust settings only reach a forked JVM.
    Test / fork := true,
    Test / javaOptions ++= trustOptions(riftTlsDir.value, riftTlsPassword.value) ++
      runtimeOptions(riftTlsEnablePreview.value, buildJavaSpec)
  )

  private val DefaultPassword = "changeit"
  private val CaAlias = "rift-ca"
  private[sbt] val CaStoreName = "rift-ca.p12"
  private val CaCertName = "rift-ca-cert.pem"
  private[sbt] val TruststoreName = "rift-truststore.p12"

  /** The properties the forked JVM needs: the truststore for its own TLS validation, and the CA
    * keystore for `caFromBuildProps` to hand the same CA to the intercept proxy.
    */
  private[sbt] def trustOptions(dir: File, password: String): Seq[String] = Seq(
    s"-Djavax.net.ssl.trustStore=${(dir / TruststoreName).getAbsolutePath}",
    "-Djavax.net.ssl.trustStoreType=PKCS12",
    s"-Djavax.net.ssl.trustStorePassword=$password",
    s"-Drift.ca.p12=${(dir / CaStoreName).getAbsolutePath}",
    s"-Drift.ca.p12.password=$password"
  )

  /** Passing a JVM option the runtime does not recognise stops the forked JVM from starting at all,
    * which is far worse than going without the flag — hence the version gate. The build JVM's
    * version stands in for the fork's, since `Test / javaHome` defaults to it.
    *
    * The threshold here (21) is deliberately lower than `build.sbt`'s embedded-jar gate (22): that
    * one asks "can this JVM load the embedded engine", while this one asks only "will this JVM
    * accept the flag". `--enable-native-access` is accepted well before the engine is usable, and
    * gating both at 22 would withhold the flag from a JDK 21 fork that can legitimately take it.
    *
    * `javaSpec` is a parameter so both sides of the boundary are testable on whichever JDK happens
    * to run the suite; the settings pass [[buildJavaSpec]].
    */
  private[sbt] def runtimeOptions(enablePreview: Boolean, javaSpec: Int): Seq[String] = {
    val nativeAccess =
      if (javaSpec >= 21) Seq("--enable-native-access=ALL-UNNAMED") else Seq.empty
    val preview = if (enablePreview) Seq("--enable-preview") else Seq.empty
    nativeAccess ++ preview
  }

  private[sbt] lazy val buildJavaSpec: Int = {
    val parts = sys.props.getOrElse("java.specification.version", "0").split("\\.")
    if (parts.headOption.contains("1")) parts.lift(1).map(_.toInt).getOrElse(0)
    else parts.headOption.flatMap(p => scala.util.Try(p.toInt).toOption).getOrElse(0)
  }

  private def generateTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log = streams.value.log
    val dir = riftTlsDir.value
    val password = riftTlsPassword.value
    val dname = riftTlsDname.value

    val javaHome = file(sys.props.getOrElse("java.home", sys.error("java.home is not set")))
    val keytool = javaHome / "bin" / (if (isWindows) "keytool.exe" else "keytool")
    if (!keytool.exists())
      sys.error(s"keytool not found at $keytool -- RiftTlsPlugin needs a JDK, not a JRE")

    val caStore = dir / CaStoreName
    val caCert = dir / CaCertName
    val truststore = dir / TruststoreName
    IO.createDirectory(dir)

    // Idempotent by existence: regenerating would mint a new CA that the already-running fork's
    // truststore does not contain. `sbt clean`, or deleting riftTlsDir, forces a fresh one.
    //
    // The guards below key on EVERY file their block produces, not just the last one. Each block
    // is two keytool calls, and a failure between them used to leave the first output on disk —
    // enough for a naive `exists` check to skip the block forever, so the missing second half
    // never healed and surfaced later as the opaque handshake failure `runKeytool` exists to
    // prevent. Regenerating the CA also invalidates any truststore built from the old one, so the
    // two can never be left disagreeing.
    val caIncomplete = !caStore.exists() || !caCert.exists()
    if (caIncomplete) {
      IO.delete(caStore)
      IO.delete(caCert)
      IO.delete(truststore)
    }

    if (caIncomplete) {
      log.info(s"[rift-tls] generating throwaway CA at $caStore")
      runKeytool(
        keytool,
        Seq(
          "-genkeypair",
          "-alias",
          CaAlias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "3650",
          "-dname",
          dname,
          "-ext",
          "bc:c",
          "-ext",
          "ku:c=digitalSignature,keyCertSign,cRLSign",
          "-keystore",
          caStore.getAbsolutePath,
          "-storetype",
          "PKCS12",
          "-storepass",
          password,
          "-keypass",
          password
        )
      )
      runKeytool(
        keytool,
        Seq(
          "-exportcert",
          "-rfc",
          "-alias",
          CaAlias,
          "-keystore",
          caStore.getAbsolutePath,
          "-storepass",
          password,
          "-file",
          caCert.getAbsolutePath
        )
      )
    }

    if (!truststore.exists()) {
      log.info(s"[rift-tls] building truststore (JDK cacerts + rift CA) at $truststore")
      // Built under a temporary name and moved into place only once BOTH imports have succeeded,
      // so the real filename never exists in a half-built state. Without this, a truststore
      // carrying the JDK anchors but not the rift CA would look complete to the check above and
      // be reused on every later run.
      val staging = dir / (TruststoreName + ".building")
      IO.delete(staging)
      // Seeded from the JDK's own cacerts rather than started empty: this replaces the fork's
      // entire truststore, so omitting the platform anchors would leave every genuinely-trusted
      // host unreachable from the tests.
      runKeytool(
        keytool,
        Seq(
          "-importkeystore",
          "-noprompt",
          "-srckeystore",
          (javaHome / "lib" / "security" / "cacerts").getAbsolutePath,
          "-srcstorepass",
          DefaultPassword,
          "-destkeystore",
          staging.getAbsolutePath,
          "-deststoretype",
          "PKCS12",
          "-deststorepass",
          password
        )
      )
      runKeytool(
        keytool,
        Seq(
          "-importcert",
          "-noprompt",
          "-alias",
          CaAlias,
          "-file",
          caCert.getAbsolutePath,
          "-keystore",
          staging.getAbsolutePath,
          "-storetype",
          "PKCS12",
          "-storepass",
          password
        )
      )
      IO.move(staging, truststore)
    }

    Seq.empty[File]
  }

  private def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Fails the build on a non-zero exit, quoting keytool's own output. A half-generated truststore
    * would otherwise surface much later as an opaque TLS handshake failure in a forked JVM.
    */
  private def runKeytool(keytool: File, args: Seq[String]): Unit = {
    // `scala.sys.process` pumps stdout and stderr on two different threads through this one
    // function, so the buffer they share has to be safe for concurrent appends — an unsynchronised
    // StringBuilder can interleave into garbage or throw while building the error message.
    val output = new java.lang.StringBuffer
    val logger = ProcessLogger { line =>
      output.append(line).append('\n')
      ()
    }
    val exit = Process(keytool.getAbsolutePath +: args) ! logger
    keytoolFailure(exit, args, output.toString).foreach(sys.error)
  }

  /** The fail-loud decision, separated from running the process so it can be tested without
    * inducing a keytool failure. `None` means the invocation succeeded.
    */
  private[sbt] def keytoolFailure(
      exit: Int,
      args: Seq[String],
      output: String
  ): Option[String] =
    if (exit == 0) None
    else
      Some(s"keytool failed (exit $exit): ${args.mkString(" ")}${System.lineSeparator}$output")
}
