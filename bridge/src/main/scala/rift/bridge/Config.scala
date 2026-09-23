package rift.bridge

import java.net.URI
import java.nio.file.Path

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

import rift.model.Port

import io.github.achirdlabs.rift.{ConnectOptions, EmbeddedOptions, SpawnOptions}
import io.github.achirdlabs.rift.VersionCheck as JVersionCheck
import io.github.achirdlabs.rift.UpstreamTrust as JUpstreamTrust
import io.github.achirdlabs.rift.{RecordMode as JRecordMode, RecordSpec as JRecordSpec}
import io.github.achirdlabs.rift.dsl.RequestField as JRequestField
import io.github.achirdlabs.rift.EventStreamOptions as JEventStreamOptions
import io.github.achirdlabs.rift.EventStreamOptions.EventType as JEventType

/** Mirrors rift-java's `VersionCheck` enum — how a mismatched engine/rift-java version is handled
  * on connect.
  */
enum VersionCheck:
  case Fail, Warn, Off

  private[bridge] def toJava: JVersionCheck = this match
    case VersionCheck.Fail => JVersionCheck.FAIL
    case VersionCheck.Warn => JVersionCheck.WARN
    case VersionCheck.Off => JVersionCheck.OFF

/** How the engine trusts an HTTPS origin a proxy stub dials (engine 0.18.0; rift-java 0.3.0 #225):
  * a private CA from a PEM file on the engine's host, an inline PEM, or no verification at all. The
  * engine applies it when its admin plane starts, and an imposter keeps the client it was created
  * with, so it is a transport setting rather than a per-imposter one.
  *
  * rift-java sends it only to an engine that supports it: the embedded transport checks the
  * engine's advertised `serveOptions` (an engine without it fails with
  * `RiftError.EngineUnavailable`), and spawn checks the declared `version`. An inline PEM without a
  * certificate block, [[CaPem]] on spawn (the engine CLI has no inline form — write the PEM to a
  * file and use [[CaFile]]), and any trust on a spawn `version` older than 0.18.0 are refused
  * before an engine starts: `RiftConnector.embedded`/`spawn`, and every effect surface over them,
  * fail with the typed `RiftError.InvalidDefinition` (#193).
  */
enum UpstreamTrust:
  case CaFile(pem: Path)
  case CaPem(pem: String)
  case SkipVerify

  private[bridge] def toJava: JUpstreamTrust = this match
    case UpstreamTrust.CaFile(pem) => JUpstreamTrust.CaFile(pem)
    case UpstreamTrust.CaPem(pem) => JUpstreamTrust.CaPem(pem)
    case UpstreamTrust.SkipVerify => JUpstreamTrust.SkipVerify()

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
    versionCheck: VersionCheck = VersionCheck.Fail,
    /** Outbound TLS trust for proxy stubs; see [[UpstreamTrust]]. Setting it makes rift-java serve
      * the embedded admin plane eagerly, since that is when the engine applies it.
      */
    upstreamTrust: Option[UpstreamTrust] = None
):
  private[bridge] def toOptions: EmbeddedOptions =
    val builder = EmbeddedOptions.builder()
    libraryPath.foreach(builder.libraryPath)
    builder.adminHost(adminHost)
    builder.adminPort(adminPort)
    builder.serveAdminEagerly(serveAdminEagerly)
    apiKey.foreach(builder.apiKey)
    builder.versionCheck(versionCheck.toJava)
    upstreamTrust.foreach(t => builder.upstreamTrust(t.toJava))
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
    inheritLog: Boolean = false,
    /** Outbound TLS trust for proxy stubs; see [[UpstreamTrust]]. Needs a declared `version` of
      * 0.18.0 or later, and not [[UpstreamTrust.CaPem]].
      */
    upstreamTrust: Option[UpstreamTrust] = None
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
    upstreamTrust.foreach(t => builder.upstreamTrust(t.toJava))
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

/** Mirrors rift-java's `EventStreamOptions.EventType` — which envelope categories the admin SSE
  * stream subscribes to (`RiftConnector.events`, issue #87).
  */
enum EventType:
  case Requests, Lifecycle

  private[bridge] def toJava: JEventType = this match
    case EventType.Requests => JEventType.REQUESTS
    case EventType.Lifecycle => JEventType.LIFECYCLE

/** Scala-idiomatic mirror of `EventStreamOptions` — configuring `RiftConnector.events` (DESIGN.md
  * §9 item 2, D3, issue #87). Empty/`None` fields mean "facade default": `toOptions` calls the
  * builder setter only when a field is set, so the facade's own defaults (notably the private
  * `DEFAULT_IDLE_TIMEOUT`) travel untouched rather than being re-encoded here. `filters` reuses
  * `TailFilter` through the same `FacadeEncode.matchClauses` seam the request tail's server-side
  * filtering already goes through (Facade.scala).
  */
final case class EventStreamConfig(
    types: Set[EventType] = Set.empty,
    port: Option[Port] = None,
    filters: Vector[TailFilter] = Vector.empty,
    idleTimeout: Option[FiniteDuration] = None
):
  private[bridge] def toOptions: JEventStreamOptions =
    val builder = JEventStreamOptions.builder()
    if types.nonEmpty then builder.types(types.toArray.map(_.toJava)*)
    port.foreach(p => builder.port(Port.value(p)))
    // `match` is a Scala 3 reserved word — the facade builder's method of that name needs backticks.
    if filters.nonEmpty then builder.`match`(FacadeEncode.matchClauses(filters)*)
    idleTimeout.foreach(d => builder.idleTimeout(d.toJava))
    builder.build()
