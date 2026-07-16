package rift.zio

import java.net.URI

import zio.*

import rift.RiftError
import rift.json.Json
import rift.model.{ApplyResult, EngineInfo, Port}
import rift.dsl.ImposterBuilder
import rift.bridge.{
  ConnectConfig,
  ContainerConfig,
  EmbeddedConfig,
  ImposterDefinition,
  RiftConnector,
  SpawnConfig
}

/** The ZIO surface over `rift.bridge.RiftConnector` (DESIGN.md §5.3, issue #4).
  *
  * `intercept` is omitted: the bridge doesn't expose it yet — `RiftConnector`'s own scaladoc tracks
  * the gap as issue #34. Not faked here.
  */
trait Rift:
  def create(definition: ImposterDefinition): IO[RiftError, ImposterHandle]
  def create(builder: ImposterBuilder): IO[RiftError, ImposterHandle]
  def createFromJson(json: String): IO[RiftError, ImposterHandle]
  def imposter(port: Port): IO[RiftError, ImposterHandle]
  def imposters: IO[RiftError, Chunk[ImposterHandle]]
  def deleteAll: IO[RiftError, Unit]
  def replaceAll(definitions: Chunk[ImposterDefinition]): IO[RiftError, Unit]
  def applyConfig(config: Json): IO[RiftError, ApplyResult]
  def info: IO[RiftError, EngineInfo]
  def adminUri: UIO[URI]

/** Runs a blocking bridge downcall on `ZIO.attemptBlocking`, recovering the `RiftError` the bridge
  * already throws for every modeled failure (DESIGN.md §5.2, D3) and letting anything else die as
  * the defect it is. Shared by every `*Live`/`Ref` wrapper in this package.
  */
private[zio] def blockingIO[A](op: => A): IO[RiftError, A] =
  ZIO.attemptBlocking(op).refineToOrDie[RiftError]

private[zio] final case class RiftLive(connector: RiftConnector) extends Rift:
  def create(definition: ImposterDefinition): IO[RiftError, ImposterHandle] =
    blockingIO(connector.create(definition)).map(ImposterHandleLive(_))

  def create(builder: ImposterBuilder): IO[RiftError, ImposterHandle] =
    blockingIO(connector.create(builder.build)).map(ImposterHandleLive(_))

  def createFromJson(json: String): IO[RiftError, ImposterHandle] =
    ZIO
      .fromEither(Json.parse(json).flatMap(ImposterDefinition.fromJson))
      .mapError(err => RiftError.DecodeFailed(err.toString, None))
      .flatMap(create)

  def imposter(port: Port): IO[RiftError, ImposterHandle] =
    blockingIO(connector.imposter(port)).map(ImposterHandleLive(_))

  def imposters: IO[RiftError, Chunk[ImposterHandle]] =
    blockingIO(Chunk.fromIterable(connector.imposters())).map(_.map(ImposterHandleLive(_)))

  def deleteAll: IO[RiftError, Unit] = blockingIO(connector.deleteAll())

  def replaceAll(definitions: Chunk[ImposterDefinition]): IO[RiftError, Unit] =
    blockingIO(connector.replaceAll(definitions.toVector))

  def applyConfig(config: Json): IO[RiftError, ApplyResult] =
    blockingIO(connector.applyConfig(config))

  def info: IO[RiftError, EngineInfo] = blockingIO(connector.info())

  def adminUri: UIO[URI] = ZIO.succeed(connector.adminUri)

object Rift:

  // ── layers (all scoped; acquire + release both run on the blocking pool, since
  //    `RiftConnector.close()` performs real blocking teardown — engine shutdown, or
  //    subprocess/testcontainer stop for spawn/container) ─────────────────────────────────────

  private def scopedConnector(acquire: => RiftConnector): ZLayer[Any, RiftError, Rift] =
    ZLayer.scoped:
      ZIO
        .acquireRelease(ZIO.attemptBlocking(acquire).refineToOrDie[RiftError])(c =>
          ZIO.attemptBlocking(c.close()).orDie
        )
        .map(RiftLive(_))

  val embedded: ZLayer[Any, RiftError, Rift] = scopedConnector(RiftConnector.embedded())

  def embedded(config: EmbeddedConfig): ZLayer[Any, RiftError, Rift] =
    scopedConnector(RiftConnector.embedded(config))

  def connect(adminUri: URI): ZLayer[Any, RiftError, Rift] = connect(ConnectConfig(adminUri))

  def connect(config: ConnectConfig): ZLayer[Any, RiftError, Rift] =
    scopedConnector(RiftConnector.connect(config))

  def spawn(config: SpawnConfig = SpawnConfig()): ZLayer[Any, RiftError, Rift] =
    scopedConnector(RiftConnector.spawn(config))

  def container(config: ContainerConfig = ContainerConfig()): ZLayer[Any, RiftError, Rift] =
    scopedConnector(RiftConnector.container(config))

  // ── environment accessors (thin ZIO.serviceWithZIO delegates, for test DX) ──────────────────

  def create(definition: ImposterDefinition): ZIO[Rift, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](_.create(definition))
  def create(builder: ImposterBuilder): ZIO[Rift, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](_.create(builder))
  def createFromJson(json: String): ZIO[Rift, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](_.createFromJson(json))
  def imposter(port: Port): ZIO[Rift, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](_.imposter(port))
  def imposters: ZIO[Rift, RiftError, Chunk[ImposterHandle]] =
    ZIO.serviceWithZIO[Rift](_.imposters)
  def deleteAll: ZIO[Rift, RiftError, Unit] = ZIO.serviceWithZIO[Rift](_.deleteAll)
  def replaceAll(definitions: Chunk[ImposterDefinition]): ZIO[Rift, RiftError, Unit] =
    ZIO.serviceWithZIO[Rift](_.replaceAll(definitions))
  def applyConfig(config: Json): ZIO[Rift, RiftError, ApplyResult] =
    ZIO.serviceWithZIO[Rift](_.applyConfig(config))
  def info: ZIO[Rift, RiftError, EngineInfo] = ZIO.serviceWithZIO[Rift](_.info)
  def adminUri: URIO[Rift, URI] = ZIO.serviceWithZIO[Rift](_.adminUri)
