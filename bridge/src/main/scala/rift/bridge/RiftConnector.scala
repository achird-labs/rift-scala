package rift.bridge

import java.net.URI

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.json.Json
import rift.model.{ApplyResult, EngineInfo, Port}

import io.github.achirdlabs.rift.Rift as JRift
import io.github.achirdlabs.rift.InterceptOptions as JInterceptOptions
import io.github.achirdlabs.rift.model.ImposterDefinition as JImposterDefinition

/** Blocking, throwing (`RiftError`), thread-safe. One instance per engine (DESIGN.md §5.2). All
  * effect-module connectors (`rift.zio`, `rift.cats`, ...) are thin wrappers over this and
  * `ImposterConnector`, so behavior can never diverge between backends.
  */
final class RiftConnector private (
    underlying: JRift,
    onClose: () => Unit,
    // Pre-resolved attach options for a container that already booted an intercept listener at
    // start (`withInterceptPort`). The engine allows one listener per process, so calling
    // `intercept`'s start path against such an engine 409s; when present, `intercept` attaches to
    // the running listener (at the host-mapped address) instead of starting a second one.
    attachOptions: Option[JInterceptOptions] = None
) extends AutoCloseable:

  def create(definition: ImposterDefinition): ImposterConnector =
    FacadeBoundary.run(ImposterConnector(underlying.create(FacadeEncode.json(definition.toJson))))

  def imposter(port: Port): ImposterConnector =
    FacadeBoundary.run {
      underlying.imposter(Port.value(port)).toScala match
        case Some(imp) => ImposterConnector(imp)
        case None => throw RiftError.ImposterNotFound(port)
    }

  def imposters(): Vector[ImposterConnector] =
    FacadeBoundary.run(underlying.imposters().asScala.toVector.map(ImposterConnector(_)))

  def deleteAll(): Unit = FacadeBoundary.run(underlying.deleteAll())

  /** Rebuilds each java-model definition via its own `fromJson` (the D2 raw-JSON seam) rather than
    * a hand-written Scala→Java field mapping: `Rift.replaceAll` only accepts the facade's own
    * `model.ImposterDefinition`, and its `fromJson` is the parser guaranteed to stay in sync with
    * the wire format it also writes.
    */
  def replaceAll(definitions: Vector[ImposterDefinition]): Unit =
    FacadeBoundary.run {
      val javaDefs = definitions.map(d => JImposterDefinition.fromJson(d.toJson.render))
      underlying.replaceAll(javaDefs.asJava)
    }

  def applyConfig(config: Json): ApplyResult =
    FacadeBoundary.run {
      val r = underlying.applyConfig(FacadeEncode.json(config))
      ApplyResult(
        created = r.created(),
        replaced = r.replaced(),
        stubPatched = r.stubPatched(),
        deleted = r.deleted(),
        failed = r.failed().asScala.toVector.map(FacadeDecode.json)
      )
    }

  def info(): EngineInfo =
    FacadeBoundary.run {
      val i = underlying.info()
      EngineInfo(i.version(), i.commit(), i.features().asScala.toSet)
    }

  def adminUri: URI = FacadeBoundary.run(underlying.adminUri())

  /** Start the engine's TLS-MITM intercept proxy — at most one per engine (a second call is an
    * engine-side error, surfaced as a `RiftError`, not hidden). `config.ca = None` generates an
    * ephemeral CA; `Some(CaMaterial)` uses a committed PEM pair (the fixed-CA case #7 needs).
    */
  def intercept(config: InterceptConfig = InterceptConfig()): InterceptConnector =
    // A container-booted listener is already running with the CA it started under, so its attach
    // options (host + mapped port) are authoritative — `config` cannot retune a live listener, and
    // the start path would 409. Only when no listener was pre-started do we honour `config`.
    FacadeBoundary.run(
      InterceptConnector(underlying.intercept(attachOptions.getOrElse(config.toOptions)))
    )

  /** Attach to an intercept listener started out of process — a remote or CI-managed engine.
    *
    * Takes no `InterceptConfig` on purpose: the running listener already owns its CA and address,
    * so `attach = true` alongside CA material would be a representable state the facade silently
    * ignores. The container transport's pre-resolved attach options are deliberately not consulted
    * here either — an explicit call names its own target.
    */
  def interceptAttach(host: String, port: Int): InterceptConnector =
    FacadeBoundary.run(
      InterceptConnector(underlying.intercept(JInterceptOptions.attach(host, port)))
    )

  /** Open the engine's admin SSE `/events` stream — its own connection, independent of this
    * `RiftConnector`'s lifetime (DESIGN.md D5, issue #87). The caller owns the returned
    * `EventStreamConnector`: `close()` it when done, the same scoped-resource contract as
    * `intercept`/`startRecording`.
    *
    * A quiet engine does not end this stream — it FAILS it: the facade turns an elapsed idle
    * timeout into `RiftError.EngineUnavailable`. Treat that as "reconnect", not "done".
    *
    * '''Not available on the embedded transport.''' `RiftTransport.events()`'s default throws
    * `UnsupportedOperationException` and only the HTTP-backed transports (connect, spawn,
    * container) implement it, so an embedded engine rejects this call — as a defect, not a typed
    * `RiftError`, since choosing a transport that cannot do it is a wiring decision rather than an
    * engine failure. Poll `recorded`/`recordedSince` there instead (rift-java-core 0.2.1).
    */
  def events(config: EventStreamConfig = EventStreamConfig()): EventStreamConnector =
    FacadeBoundary.run(EventStreamConnector(underlying.events(config.toOptions)))

  def close(): Unit =
    try FacadeBoundary.run(underlying.close())
    finally onClose()

object RiftConnector:

  /** True when the embedded engine runtime (`rift-java-embedded` plus its natives) is on the
    * classpath — the standard gate for `assume`-guarded embedded tests. A plain `Boolean` rather
    * than an effect: it is a deterministic classpath probe, and every call site needs it in an
    * un-effectful position (munit `assume`, `TestAspect.when`, suite-construction guards).
    */
  def isEmbeddedAvailable: Boolean = FacadeBoundary.run(JRift.isEmbeddedAvailable())

  def embedded(config: EmbeddedConfig = EmbeddedConfig()): RiftConnector =
    FacadeBoundary.run(new RiftConnector(JRift.embedded(config.toOptions), () => ()))

  def connect(config: ConnectConfig): RiftConnector =
    FacadeBoundary.run(new RiftConnector(JRift.connect(config.toOptions), () => ()))

  def spawn(config: SpawnConfig = SpawnConfig()): RiftConnector =
    FacadeBoundary.run(new RiftConnector(JRift.spawn(config.toOptions), () => ()))

  /** Requires `rift-java-testcontainers` (+ Docker) on the runtime classpath — `% Optional` in this
    * build (project/Dependencies.scala) so consumers who never call `container` don't inherit
    * org.testcontainers. Its absence surfaces as `EngineUnavailable` naming the missing artifact
    * rather than a `NoClassDefFoundError` leaking out of this API.
    */
  def container(config: ContainerConfig = ContainerConfig()): RiftConnector =
    // Probe only the entry class up front, so ONLY its genuine absence maps to the friendly
    // "add rift-java-testcontainers" message. A NoClassDefFoundError raised later (a missing
    // *transitive* testcontainers dep, or one thrown during start()/client()) must stay the defect
    // it really is — misreporting it as "add rift-java-testcontainers" would send a developer whose
    // artifact is already present chasing the wrong fix with the real class name discarded.
    try
      Class.forName(
        "io.github.achirdlabs.rift.testcontainers.RiftContainer",
        false,
        getClass.getClassLoader
      )
    catch
      case _: ClassNotFoundException | _: NoClassDefFoundError =>
        throw RiftError.EngineUnavailable(
          "rift-java-testcontainers (+ Docker) required for container(); add it to your build",
          None
        )

    val container = config.image match
      case Some(image) =>
        new io.github.achirdlabs.rift.testcontainers.RiftContainer(
          org.testcontainers.utility.DockerImageName.parse(image)
        )
      case None => new io.github.achirdlabs.rift.testcontainers.RiftContainer()
    config.apiKey.foreach(container.withApiKey)
    if config.imposterPorts.nonEmpty then container.withImposterPorts(config.imposterPorts.toArray*)
    if config.gateway then container.withGateway()
    config.interceptPort.foreach(p => container.withInterceptPort(p))
    // The engine reads `MB_ALLOW_INJECTION` as the env form of `--allowInjection` (rift
    // rift-http-proxy/server.rs). `RiftContainer` has no dedicated setter, but it extends
    // testcontainers' GenericContainer, so set the env directly rather than needing a rift-java bump.
    if config.allowInjection then container.withEnv("MB_ALLOW_INJECTION", "true")

    // stop() the container if anything after start() fails (notably client(), which opens a real
    // connection and can throw under versionCheck=FAIL) — otherwise the started Docker container is
    // orphaned until Ryuk reaps it. The onClose hook only covers the *successful* path.
    try
      container.start()
      // If an intercept listener was booted at start, capture its attach options now (they read the
      // started container's host + mapped port) so `intercept` attaches instead of starting a second.
      val attach = Option.when(config.interceptPort.isDefined)(container.interceptOptions())
      new RiftConnector(container.client(), () => container.stop(), attach)
    catch
      case t: Throwable =>
        try container.stop()
        catch case _: Throwable => () // best-effort cleanup; never mask the original failure
        throw RiftError.fromThrowable(t).getOrElse(t)
