package rift.ziobdd

import zio.*

import zio.bdd.mock as spi

import rift.RiftError
import rift.zio.{ImposterHandle, InterceptHandle, Rift}
import rift.bridge.ConnectConfig

/** zio-bdd `MockControl` layers backed by rift-scala (issue #18, DESIGN §5.12).
  *
  * Compared to zio-bdd's own engine-direct Rift adapters (`zio-bdd-rift*`), this one drives the
  * engine through `rift.zio.Rift` — the typed dsl/model instead of hand-rolled admin JSON, native
  * stub-id rule addressing, engine-assigned ephemeral ports (no client-side port allocator), and
  * the intercept/recording surfaces — so every wire detail is covered by rift-scala's conformance
  * corpus rather than a second protocol implementation.
  */
object RiftScalaBackend:

  private[ziobdd] enum Mode:
    case PerInstance
    case Correlated(header: String)

  /** The header a Correlated adapter partitions spaces by, unless overridden. */
  val defaultCorrelationHeader: String = "X-Mock-Space"

  /** Wrap an already-provided `rift.zio.Rift` service (PerInstance isolation). */
  val fromService: URLayer[Rift, spi.MockControl] = withIsolation()

  /** As [[fromService]], choosing the isolation mode (and, for Correlated, the flow header). */
  def withIsolation(
      isolation: spi.Isolation = spi.Isolation.PerInstance,
      correlationHeader: String = defaultCorrelationHeader
  ): URLayer[Rift, spi.MockControl] =
    ZLayer.scoped {
      for
        rift <- ZIO.service[Rift]
        scope <- ZIO.scope
        provisioning <- spi.Provisioning.make
        spaces <- Ref.make(Map.empty[spi.SpaceId, SpaceRec])
        shared <- Ref.Synchronized.make(Option.empty[ImposterHandle])
        interceptCell <- Ref.Synchronized.make(Option.empty[InterceptHandle])
        counter <- Ref.make(0)
        // Release-time sweep: spaces the suite never destroy()ed are reclaimed when the adapter
        // layer closes. Embedded engines die with their layer anyway; this is for a long-lived
        // `connect` engine, which would otherwise accumulate orphaned imposters run over run.
        _ <- ZIO.addFinalizer(
          spaces.get.flatMap(recs =>
            ZIO.foreachDiscard(recs.toVector) { (id, rec) =>
              val cleanup = rec match
                case r: SpaceRec.PerInstance => r.handle.delete
                case r: SpaceRec.Correlated => r.space.delete
              cleanup.catchAllCause(c =>
                ZIO.logWarningCause(s"release sweep: destroy space ${id.value} failed", c)
              )
            }
          )
        )
        mode = isolation match
          case spi.Isolation.PerInstance => Mode.PerInstance
          case spi.Isolation.Correlated => Mode.Correlated(correlationHeader)
      yield RiftScalaMockControl(
        rift,
        provisioning,
        mode,
        scope,
        spaces,
        shared,
        interceptCell,
        counter
      ): spi.MockControl
    }

  /** An embedded in-process engine plus the adapter — the one-liner test stack. */
  def embedded(
      isolation: spi.Isolation = spi.Isolation.PerInstance
  ): ZLayer[Any, spi.MockError, spi.MockControl] =
    Rift.embedded.mapError(startupError) >>> withIsolation(isolation)

  /** A remote engine over its admin URI plus the adapter. */
  def connect(
      config: ConnectConfig,
      isolation: spi.Isolation = spi.Isolation.PerInstance
  ): ZLayer[Any, spi.MockError, spi.MockControl] =
    Rift.connect(config).mapError(startupError) >>> withIsolation(isolation)

  private def startupError(e: RiftError): spi.MockError =
    RiftModelMapping.toMockError(None)(e)
