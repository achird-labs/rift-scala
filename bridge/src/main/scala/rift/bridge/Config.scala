package rift.bridge

import java.net.URI
import java.nio.file.Path

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

import io.github.achirdlabs.rift.{ConnectOptions, EmbeddedOptions, SpawnOptions}
import io.github.achirdlabs.rift.VersionCheck as JVersionCheck
import io.github.achirdlabs.rift.{RecordMode as JRecordMode, RecordSpec as JRecordSpec}
import io.github.achirdlabs.rift.dsl.RequestField as JRequestField

/** Mirrors rift-java's `VersionCheck` enum — how a mismatched engine/rift-java version is handled
  * on connect.
  */
enum VersionCheck:
  case Fail, Warn, Off

  private[bridge] def toJava: JVersionCheck = this match
    case VersionCheck.Fail => JVersionCheck.FAIL
    case VersionCheck.Warn => JVersionCheck.WARN
    case VersionCheck.Off => JVersionCheck.OFF

/** Scala-idiomatic mirror of `ConnectOptions` — connecting to an already-running engine. */
final case class ConnectConfig(
    adminUri: URI,
    apiKey: Option[String] = None,
    requestTimeout: FiniteDuration = 30.seconds,
    versionCheck: VersionCheck = VersionCheck.Fail,
    hostResolver: Option[Int => URI] = None
):
  private[bridge] def toOptions: ConnectOptions =
    val builder = ConnectOptions.builder(adminUri)
    apiKey.foreach(builder.apiKey)
    builder.requestTimeout(requestTimeout.toJava)
    builder.versionCheck(versionCheck.toJava)
    hostResolver.foreach(f => builder.hostResolver((i: Int) => f(i)))
    builder.build()

/** Scala-idiomatic mirror of `EmbeddedOptions` — an in-process engine (requires the natives
  * classifier jar and `--enable-native-access` — see `RiftNatives`).
  */
final case class EmbeddedConfig(
    libraryPath: Option[Path] = None,
    adminHost: String = "127.0.0.1",
    adminPort: Int = 0,
    serveAdminEagerly: Boolean = false,
    apiKey: Option[String] = None,
    versionCheck: VersionCheck = VersionCheck.Fail
):
  private[bridge] def toOptions: EmbeddedOptions =
    val builder = EmbeddedOptions.builder()
    libraryPath.foreach(builder.libraryPath)
    builder.adminHost(adminHost)
    builder.adminPort(adminPort)
    builder.serveAdminEagerly(serveAdminEagerly)
    apiKey.foreach(builder.apiKey)
    builder.versionCheck(versionCheck.toJava)
    builder.build()

/** Scala-idiomatic mirror of `SpawnOptions` — launching the engine binary as a child process. */
final case class SpawnConfig(
    binaryPath: Option[Path] = None,
    version: String = RiftVersions.engine,
    host: String = "127.0.0.1",
    adminPort: Int = 0,
    allowInjection: Boolean = true,
    localOnly: Boolean = true,
    logLevel: String = "info",
    env: Map[String, String] = Map.empty,
    workingDir: Option[Path] = None,
    mirrorUrl: Option[URI] = None,
    startupTimeout: FiniteDuration = 15.seconds,
    shutdownTimeout: FiniteDuration = 5.seconds,
    inheritLog: Boolean = false
):
  private[bridge] def toOptions: SpawnOptions =
    val builder = SpawnOptions.builder()
    binaryPath.foreach(builder.binaryPath)
    builder.version(version)
    builder.host(host)
    builder.adminPort(adminPort)
    builder.allowInjection(allowInjection)
    builder.localOnly(localOnly)
    builder.logLevel(logLevel)
    if env.nonEmpty then builder.env(env.asJava)
    workingDir.foreach(builder.workingDir)
    mirrorUrl.foreach(builder.mirrorUrl)
    builder.startupTimeout(startupTimeout.toJava)
    builder.shutdownTimeout(shutdownTimeout.toJava)
    builder.inheritLog(inheritLog)
    builder.build()

/** `RiftConnector.container` config. No `toOptions`: the testcontainers transport is configured
  * directly on `RiftContainer`, not through one of rift-java's `*Options` builders.
  */
final case class ContainerConfig(
    image: Option[String] = None,
    imposterPorts: Vector[Int] = Vector.empty,
    apiKey: Option[String] = None,
    gateway: Boolean = false,
    interceptPort: Option[Int] = None,
    // Enable the engine's script-injection surface (`_rift.script`, `_behaviors.decorate`). The
    // engine gates it behind `--allowInjection` (env `MB_ALLOW_INJECTION`) and defaults it OFF for
    // safety, so this stays opt-in; a consumer that drives the scripting capability sets it true.
    allowInjection: Boolean = false
)

/** Mirrors rift-java's `RecordMode` — how a proxy-capture session records matched requests. */
enum RecordMode:
  case Once, Always, Transparent

  private[bridge] def toJava: JRecordMode = this match
    case RecordMode.Once => JRecordMode.ONCE
    case RecordMode.Always => JRecordMode.ALWAYS
    case RecordMode.Transparent => JRecordMode.TRANSPARENT

/** Mirrors rift-java's `dsl.RequestField` — a request attribute a recording turns into a stub
  * predicate (`RecordSpec.generateBy`).
  */
enum RequestField:
  case Method, Path, Query, Headers, Body

  private[bridge] def toJava: JRequestField = this match
    case RequestField.Method => JRequestField.METHOD
    case RequestField.Path => JRequestField.PATH
    case RequestField.Query => JRequestField.QUERY
    case RequestField.Headers => JRequestField.HEADERS
    case RequestField.Body => JRequestField.BODY

/** Scala-idiomatic mirror of `RecordSpec` — configures a `startRecording` proxy-capture session.
  * The defaults mirror the facade builder's own defaults exactly (`RecordSpec.builder()`: `mode =
  * ONCE`, `generateBy = [METHOD, PATH]`, `addWaitBehavior = true`, `ignoreHeaders = []`), so
  * `RecordSpec()` behaves identically to the facade's no-spec `startRecording(origin)`.
  */
final case class RecordSpec(
    mode: RecordMode = RecordMode.Once,
    generateBy: Vector[RequestField] = Vector(RequestField.Method, RequestField.Path),
    addWaitBehavior: Boolean = true,
    ignoreHeaders: Vector[String] = Vector.empty
):
  private[bridge] def toJava: JRecordSpec =
    JRecordSpec
      .builder()
      .mode(mode.toJava)
      .generateBy(generateBy.map(_.toJava)*)
      .addWaitBehavior(addWaitBehavior)
      .ignoreHeaders(ignoreHeaders*)
      .build()
