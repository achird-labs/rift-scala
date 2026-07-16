package rift.bridge

import java.net.URI

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import rift.RiftError
import rift.json.Json
import rift.model.{ApplyResult, EngineInfo, Port}

import io.github.etacassiopeia.rift.Rift as JRift
import io.github.etacassiopeia.rift.model.ImposterDefinition as JImposterDefinition

/** Blocking, throwing (`RiftError`), thread-safe. One instance per engine (DESIGN.md §5.2). All
  * effect-module connectors (`rift.zio`, `rift.cats`, ...) are thin wrappers over this and
  * `ImposterConnector`, so behavior can never diverge between backends.
  *
  * `intercept(options)` is not yet implemented: `InterceptConfig`/`InterceptConnector` would need
  * to mirror rift-java's `dsl.IsSpec`/`InterceptRuleBuilder`/`InterceptTrust`/`CaMaterial` surface,
  * none of which the pure `rift-scala-model` currently models. Deferred rather than faked — see the
  * bridge implementation report (issue #3) for follow-up.
  */
final class RiftConnector private (underlying: JRift, onClose: () => Unit) extends AutoCloseable:

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

  def close(): Unit =
    try FacadeBoundary.run(underlying.close())
    finally onClose()

object RiftConnector:

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
        "io.github.etacassiopeia.rift.testcontainers.RiftContainer",
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
        new io.github.etacassiopeia.rift.testcontainers.RiftContainer(
          org.testcontainers.utility.DockerImageName.parse(image)
        )
      case None => new io.github.etacassiopeia.rift.testcontainers.RiftContainer()
    config.apiKey.foreach(container.withApiKey)
    if config.imposterPorts.nonEmpty then container.withImposterPorts(config.imposterPorts.toArray*)
    if config.gateway then container.withGateway()
    config.interceptPort.foreach(p => container.withInterceptPort(p))

    // stop() the container if anything after start() fails (notably client(), which opens a real
    // connection and can throw under versionCheck=FAIL) — otherwise the started Docker container is
    // orphaned until Ryuk reaps it. The onClose hook only covers the *successful* path.
    try
      container.start()
      new RiftConnector(container.client(), () => container.stop())
    catch
      case t: Throwable =>
        try container.stop()
        catch case _: Throwable => () // best-effort cleanup; never mask the original failure
        throw RiftError.fromThrowable(t).getOrElse(t)
