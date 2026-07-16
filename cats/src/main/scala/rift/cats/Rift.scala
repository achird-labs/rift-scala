package rift.cats

import java.net.URI

import _root_.cats.effect.{Async, Resource, Sync}

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

/** The Cats Effect surface over `rift.bridge.RiftConnector` (DESIGN.md §5.6, issue #8).
  *
  * `intercept` is omitted here: the bridge exposes `RiftConnector.intercept` and the ZIO surface
  * wraps it (`rift.zio.Rift.intercept`) as of #34, but the Cats `Resource[F, InterceptHandle[F]]`
  * wiring is a tracked follow-up rather than faked here.
  */
trait Rift[F[_]]:
  def create(definition: ImposterDefinition): F[ImposterHandle[F]]
  def create(builder: ImposterBuilder): F[ImposterHandle[F]]
  def createFromJson(json: String): F[ImposterHandle[F]]
  def imposter(port: Port): F[ImposterHandle[F]]
  def imposters: F[Vector[ImposterHandle[F]]]
  def deleteAll: F[Unit]
  def replaceAll(definitions: Vector[ImposterDefinition]): F[Unit]
  def applyConfig(config: Json): F[ApplyResult]
  def info: F[EngineInfo]
  def adminUri: F[URI]

/** Runs a blocking bridge downcall on `Sync[F].blocking`. Unlike ZIO's typed-error/defect split
  * (`refineToOrDie`), a cats-effect `F` has a single `Throwable` error channel, so the `RiftError`
  * the bridge already throws for every modeled failure (DESIGN.md §5.2, D3) rethrows into it as-is
  * — this is intentionally a one-line delegate, not a translation layer. Shared by every `*Live`
  * wrapper in this package.
  */
private[cats] def blockingF[F[_]: Sync, A](op: => A): F[A] = Sync[F].blocking(op)

private[cats] final class RiftLive[F[_]: Async](connector: RiftConnector) extends Rift[F]:
  def create(definition: ImposterDefinition): F[ImposterHandle[F]] =
    blockingF(new ImposterHandleLive[F](connector.create(definition)))

  def create(builder: ImposterBuilder): F[ImposterHandle[F]] =
    blockingF(new ImposterHandleLive[F](connector.create(builder.build)))

  def createFromJson(json: String): F[ImposterHandle[F]] =
    Json.parse(json).flatMap(ImposterDefinition.fromJson) match
      case Right(definition) => create(definition)
      case Left(err) => Async[F].raiseError(RiftError.DecodeFailed(err.toString, None))

  def imposter(port: Port): F[ImposterHandle[F]] =
    blockingF(new ImposterHandleLive[F](connector.imposter(port)))

  def imposters: F[Vector[ImposterHandle[F]]] =
    blockingF(connector.imposters().map(new ImposterHandleLive[F](_)))

  def deleteAll: F[Unit] = blockingF(connector.deleteAll())

  def replaceAll(definitions: Vector[ImposterDefinition]): F[Unit] =
    blockingF(connector.replaceAll(definitions))

  def applyConfig(config: Json): F[ApplyResult] = blockingF(connector.applyConfig(config))

  def info: F[EngineInfo] = blockingF(connector.info())

  def adminUri: F[URI] = blockingF(connector.adminUri)

object Rift:

  // ── Resource factories (both acquire and release run on the blocking pool, since
  //    `RiftConnector.close()` performs real blocking teardown — engine shutdown, or
  //    subprocess/testcontainer stop for spawn/container; `Resource.fromAutoCloseable` wraps the
  //    release in `Sync[F].blocking` internally) ────────────────────────────────────────────────

  private def resourceConnector[F[_]: Async](acquire: => RiftConnector): Resource[F, Rift[F]] =
    Resource.fromAutoCloseable(Sync[F].blocking(acquire)).map(new RiftLive[F](_))

  def embedded[F[_]: Async]: Resource[F, Rift[F]] = embedded(EmbeddedConfig())

  def embedded[F[_]: Async](config: EmbeddedConfig): Resource[F, Rift[F]] =
    resourceConnector(RiftConnector.embedded(config))

  def connect[F[_]: Async](adminUri: URI): Resource[F, Rift[F]] = connect(ConnectConfig(adminUri))

  def connect[F[_]: Async](config: ConnectConfig): Resource[F, Rift[F]] =
    resourceConnector(RiftConnector.connect(config))

  def spawn[F[_]: Async](config: SpawnConfig = SpawnConfig()): Resource[F, Rift[F]] =
    resourceConnector(RiftConnector.spawn(config))

  def container[F[_]: Async](config: ContainerConfig = ContainerConfig()): Resource[F, Rift[F]] =
    resourceConnector(RiftConnector.container(config))
