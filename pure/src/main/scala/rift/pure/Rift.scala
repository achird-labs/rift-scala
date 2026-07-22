package rift.pure

import java.net.URI

import rift.RiftError
import rift.json.Json
import rift.model.{ApplyResult, EngineInfo, Port}
import rift.dsl.ImposterBuilder
import rift.bridge.{
  ConnectConfig,
  ContainerConfig,
  EmbeddedConfig,
  ImposterDefinition,
  InterceptConfig,
  RiftConnector,
  SpawnConfig
}

/** The plain-Scala surface over `rift.bridge.RiftConnector` (DESIGN.md §5.11) — no effect system,
  * `Either[RiftError, _]`-shaped. The simplest reference implementation of the wire surface, and
  * the natural glue for scalatest/JUnit users via `scala.util.Using` (see the `*Unsafe`
  * constructors on the companion).
  */
final class Rift private (connector: RiftConnector) extends AutoCloseable:

  def create(definition: ImposterDefinition): Either[RiftError, Imposter] =
    catchRiftError(new Imposter(connector.create(definition)))

  def create(builder: ImposterBuilder): Either[RiftError, Imposter] =
    catchRiftError(new Imposter(connector.create(builder.build)))

  def createFromJson(json: String): Either[RiftError, Imposter] =
    Json.parse(json).flatMap(ImposterDefinition.fromJson) match
      case Right(definition) => create(definition)
      case Left(err) => Left(RiftError.DecodeFailed(err.toString, None))

  def imposter(port: Port): Either[RiftError, Imposter] =
    catchRiftError(new Imposter(connector.imposter(port)))

  def imposters(): Either[RiftError, Vector[Imposter]] =
    catchRiftError(connector.imposters().map(new Imposter(_)))

  def deleteAll(): Either[RiftError, Unit] = catchRiftError(connector.deleteAll())

  def replaceAll(definitions: Vector[ImposterDefinition]): Either[RiftError, Unit] =
    catchRiftError(connector.replaceAll(definitions))

  def applyConfig(config: Json): Either[RiftError, ApplyResult] =
    catchRiftError(connector.applyConfig(config))

  def info(): Either[RiftError, EngineInfo] = catchRiftError(connector.info())

  def adminUri: URI = connector.adminUri

  def intercept(config: InterceptConfig = InterceptConfig()): Either[RiftError, Intercept] =
    catchRiftError(new Intercept(connector.intercept(config)))

  /** RiftError is an Exception — throw for `Using.resource`. */
  def interceptUnsafe(config: InterceptConfig = InterceptConfig()): Intercept =
    new Intercept(connector.intercept(config))

  def close(): Unit = connector.close()

object Rift:

  /** Re-export of [[rift.bridge.RiftConnector.isEmbeddedAvailable]] so a consumer can gate an
    * embedded-only test without importing the Java facade directly.
    */
  def isEmbeddedAvailable: Boolean = RiftConnector.isEmbeddedAvailable

  def embedded(config: EmbeddedConfig = EmbeddedConfig()): Either[RiftError, Rift] =
    catchRiftError(new Rift(RiftConnector.embedded(config)))

  def connect(config: ConnectConfig): Either[RiftError, Rift] =
    catchRiftError(new Rift(RiftConnector.connect(config)))

  def spawn(config: SpawnConfig = SpawnConfig()): Either[RiftError, Rift] =
    catchRiftError(new Rift(RiftConnector.spawn(config)))

  def container(config: ContainerConfig = ContainerConfig()): Either[RiftError, Rift] =
    catchRiftError(new Rift(RiftConnector.container(config)))

  // ── throwing variants — RiftError is an Exception, so these are what `scala.util.Using.resource`
  //    needs (it requires a throwing acquire, not an `Either`) ─────────────────────────────────

  /** RiftError is an Exception — throw for `Using.resource`. */
  def embeddedUnsafe(config: EmbeddedConfig = EmbeddedConfig()): Rift =
    new Rift(RiftConnector.embedded(config))

  /** RiftError is an Exception — throw for `Using.resource`. */
  def connectUnsafe(config: ConnectConfig): Rift = new Rift(RiftConnector.connect(config))

  /** RiftError is an Exception — throw for `Using.resource`. */
  def spawnUnsafe(config: SpawnConfig = SpawnConfig()): Rift =
    new Rift(RiftConnector.spawn(config))

  /** RiftError is an Exception — throw for `Using.resource`. */
  def containerUnsafe(config: ContainerConfig = ContainerConfig()): Rift =
    new Rift(RiftConnector.container(config))
