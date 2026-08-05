package rift.zio.testkit

import java.lang.System as JSystem
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.CaMaterial

/** Gates `intercept.caFromBuildProps` against a PKCS12 produced by the very `keytool` invocation
  * the `sbt-rift` plugin uses — so the reader and the writer are pinned to each other rather than
  * to a hand-built keystore that only resembles one.
  *
  * Every test names its own system properties, so these never collide with the real `rift.ca.p12`
  * (or with each other) in the forked JVM the module's specs share.
  */
object CaFromBuildPropsSpec extends ZIOSpecDefault:

  private val Password = "changeit"
  private val Alias = "rift-ca"

  private val keytool: Option[Path] =
    val candidate = Path.of(sys.props.getOrElse("java.home", ""), "bin", "keytool")
    Option.when(Files.isExecutable(candidate))(candidate)

  /** A throwaway CA keystore in a scoped temp directory, generated exactly as
    * `RiftTlsPlugin.riftTlsGenerate` generates it.
    */
  private def caKeystore: ZIO[Scope, Throwable, Path] =
    for
      tool <- ZIO
        .fromOption(keytool)
        .orElseFail(new IllegalStateException("keytool not found under java.home"))
      dir <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("rift-ca-spec")))(
        deleteRecursively
      )
      p12 = dir.resolve("rift-ca.p12")
      _ <- ZIO.attemptBlocking {
        val command = List(
          tool.toString,
          "-genkeypair",
          "-alias",
          Alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "3650",
          "-dname",
          "CN=rift-test-ca,O=rift-scala",
          "-ext",
          "bc:c",
          "-keystore",
          p12.toString,
          "-storetype",
          "PKCS12",
          "-storepass",
          Password,
          "-keypass",
          Password
        )
        val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
        val output = new String(process.getInputStream.readAllBytes(), UTF_8)
        val exit = process.waitFor()
        if exit != 0 then throw new IllegalStateException(s"keytool failed ($exit): $output")
      }
    yield p12

  private def deleteRecursively(dir: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(Files.deleteIfExists(_))
    }.ignoreLogged

  /** Binds `key` to `value` for the duration of the effect — the properties the plugin would have
    * set, without leaking them past the test.
    */
  private def withProperty[R, E, A](key: String, value: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(ZIO.succeed(JSystem.setProperty(key, value)))(_ =>
      ZIO.succeed(JSystem.clearProperty(key))
    )(_ => zio)

  private def isInvalidDefinition(exit: Exit[RiftError, Any]): Boolean = exit match
    case Exit.Failure(cause) => cause.failures.exists(_.isInstanceOf[RiftError.InvalidDefinition])
    case Exit.Success(_) => false

  def spec = suite("CaFromBuildPropsSpec")(
    test("loads the build-generated keystore as CaMaterial.FromKeyStore") {
      ZIO.scoped {
        for
          p12 <- caKeystore
          material <- withProperty("rift.spec.ok.p12", p12.toString) {
            intercept.caFromBuildProps(
              pathProp = "rift.spec.ok.p12",
              passwordProp = "rift.spec.ok.password",
              alias = Alias
            )
          }
        yield assertTrue(material.isInstanceOf[CaMaterial.FromKeyStore])
      }
    },
    test("falls back to the plugin's default password when no password property is set") {
      // Named explicitly rather than left implicit in the happy-path test: this is the branch that
      // keeps `caFromBuildProps` working against a plugin-generated keystore with only one
      // property wired, and it silently breaks if either side's default moves.
      ZIO.scoped {
        for
          p12 <- caKeystore
          material <- withProperty("rift.spec.defaultpass.p12", p12.toString) {
            intercept.caFromBuildProps(
              pathProp = "rift.spec.defaultpass.p12",
              passwordProp = "rift.spec.defaultpass.unset",
              alias = Alias
            )
          }
        yield assertTrue(material.isInstanceOf[CaMaterial.FromKeyStore])
      }
    },
    test("says the default password was used when the property was unset") {
      // Distinguishes "you never set the password property" from "your password is wrong" — the
      // two are indistinguishable from the raw keystore error alone.
      ZIO.scoped {
        for
          p12 <- caKeystore
          exit <- withProperty("rift.spec.hint.p12", p12.toString) {
            intercept
              .caFromBuildProps(
                pathProp = "rift.spec.hint.p12",
                passwordProp = "rift.spec.hint.unset",
                alias = "no-such-alias"
              )
              .exit
          }
        yield assertTrue(exit match
          case Exit.Failure(cause) =>
            cause.failures.exists(_.getMessage.contains("rift.spec.hint.unset"))
          case Exit.Success(_) => false
        )
      }
    },
    test("fails with InvalidDefinition when the path property is unset") {
      for exit <- intercept
          .caFromBuildProps(
            pathProp = "rift.spec.unset.p12",
            passwordProp = "rift.spec.unset.password",
            alias = Alias
          )
          .exit
      yield assertTrue(isInvalidDefinition(exit))
    },
    test("fails with InvalidDefinition when the keystore file does not exist") {
      withProperty("rift.spec.missing.p12", "/nonexistent/rift-ca.p12") {
        for exit <- intercept
            .caFromBuildProps(
              pathProp = "rift.spec.missing.p12",
              passwordProp = "rift.spec.missing.password",
              alias = Alias
            )
            .exit
        yield assertTrue(isInvalidDefinition(exit))
      }
    },
    test("fails with InvalidDefinition when the password is wrong") {
      ZIO.scoped {
        for
          p12 <- caKeystore
          exit <- withProperty("rift.spec.badpass.p12", p12.toString) {
            withProperty("rift.spec.badpass.password", "not-the-password") {
              intercept
                .caFromBuildProps(
                  pathProp = "rift.spec.badpass.p12",
                  passwordProp = "rift.spec.badpass.password",
                  alias = Alias
                )
                .exit
            }
          }
        yield assertTrue(isInvalidDefinition(exit))
      }
    },
    test("fails with InvalidDefinition when the alias is absent from the keystore") {
      ZIO.scoped {
        for
          p12 <- caKeystore
          exit <- withProperty("rift.spec.alias.p12", p12.toString) {
            intercept
              .caFromBuildProps(
                pathProp = "rift.spec.alias.p12",
                passwordProp = "rift.spec.alias.password",
                alias = "no-such-alias"
              )
              .exit
          }
        yield assertTrue(isInvalidDefinition(exit))
      }
    }
    // keytool ships with every JDK, so this never actually skips in practice — it degrades to an
    // ignore rather than a red suite on an exotic JRE-only runtime.
  ) @@ (if keytool.isDefined then TestAspect.identity else TestAspect.ignore)
