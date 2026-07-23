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
  EventSource,
  EventStreamConfig,
  ImposterDefinition,
  InterceptConfig,
  RiftConnector,
  SpawnConfig
}

/** The Cats Effect surface over `rift.bridge.RiftConnector` (DESIGN.md §5.6, issue #8). */
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
  def intercept(config: InterceptConfig = InterceptConfig()): Resource[F, InterceptHandle[F]]

  /** Attach to an intercept listener started out of process — a remote or CI-managed engine. No
    * `InterceptConfig`: the running listener already owns its CA and address.
    */
  def interceptAttach(host: String, port: Int): Resource[F, InterceptHandle[F]]

  /** The admin SSE event stream as a `Resource` (DESIGN.md D4/D5, issue #87). The cats module has
    * no fs2 dependency, so this exposes the bridge `EventSource` directly rather than a `Stream` —
    * it already exposes bridge types (`RecordedPage`) publicly. `rift.fs2.syntax`'s `events`
    * extension builds the `Stream[F, RiftEvent]` on top of it. Each call opens its own connection,
    * never tied to this `Rift[F]`'s own `Resource`.
    *
    * A quiet engine does not end this stream — it FAILS it: the facade turns an elapsed idle
    * timeout into `RiftError.EngineUnavailable`. Treat that as "reconnect", not "done".
    *
    * This is the one surface handing back the raw `EventSource`, so its `poll()` is yours to wrap:
    * it BLOCKS until the next event, so run it under `Sync[F].interruptible`, never `delay` — a
    * plain `delay` pins a compute thread for up to the idle timeout. `rift.fs2`'s `events`
    * extension does exactly that and is the easier option if fs2 is available. Single consumer
    * only: concurrent `poll()` races on the facade's iterator cursor.
    *
    * Available on every transport as of rift-java 0.2.2. It used to throw on the embedded one —
    * `RiftTransport.events()`'s default did, and only `RemoteTransport` overrode it — but
    * `EmbeddedTransport` now delegates to the in-process admin server it already starts for
    * `replaceAllImposters` (rift-java#177). The first call there pays that server's lazy start.
    */
  def eventSource(config: EventStreamConfig = EventStreamConfig()): Resource[F, EventSource]

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

  /** Both acquire and release run on the blocking pool (like `RiftLive`'s `close()`), since
    * `InterceptConnector.close()` makes a real blocking call. Release clears the handle's rules
    * rather than stopping the proxy, and only one *successful* `intercept` is allowed per engine —
    * see `InterceptHandle`.
    */
  def intercept(config: InterceptConfig): Resource[F, InterceptHandle[F]] =
    Resource
      .fromAutoCloseable(blockingF(connector.intercept(config)))
      .map(
        new InterceptHandleLive[F](_)
      )

  def interceptAttach(host: String, port: Int): Resource[F, InterceptHandle[F]] =
    Resource
      .fromAutoCloseable(blockingF(connector.interceptAttach(host, port)))
      .map(
        new InterceptHandleLive[F](_)
      )

  def eventSource(config: EventStreamConfig): Resource[F, EventSource] =
    Resource.fromAutoCloseable(blockingF(connector.events(config)))

object Rift:

  /** Re-export of [[rift.bridge.RiftConnector.isEmbeddedAvailable]] so a consumer can gate an
    * embedded-only test without importing the Java facade directly.
    */
  def isEmbeddedAvailable: Boolean = RiftConnector.isEmbeddedAvailable

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
