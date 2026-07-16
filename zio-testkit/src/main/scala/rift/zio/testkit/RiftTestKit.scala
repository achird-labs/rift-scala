package rift.zio.testkit

import java.net.URI

import zio.*

import rift.RiftError
import rift.dsl.ImposterBuilder
import rift.model.FlowId
import rift.zio.{ImposterHandle, Rift, SpaceHandle}

/** Test-tuned engine layers, per-test provisioning, and fixture scoping over `rift.zio.Rift`
  * (DESIGN.md §5.4). Kept thin: every method is a direct composition of `Rift`/`ImposterHandle`
  * primitives, with `Scope`-driven teardown so a test never has to remember to clean up.
  */
object RiftTestKit:

  /** An embedded engine with a dynamic admin port (`EmbeddedConfig`'s own default, `adminPort = 0`)
    * — safe to run many suites in parallel without a port clash. No test-specific tuning is needed
    * beyond that default, so this is a direct alias.
    */
  val embedded: ZLayer[Any, RiftError, Rift] = Rift.embedded

  /** `RIFT_ADMIN_URL` set (and non-empty) → connect to that engine; otherwise spin up an embedded
    * one. The one-line CI/local switch DESIGN.md §5.4 calls for: CI exports the URL of a long-lived
    * shared engine, local runs fall back to embedded with zero configuration.
    */
  def fromEnv: ZLayer[Any, RiftError, Rift] =
    transportFromEnv(sys.env) match
      case Left(uri) => Rift.connect(uri)
      case Right(()) => embedded

  /** The pure decision `fromEnv` is built on, factored out so it is testable without booting any
    * engine (`RiftTestKitSpec`). `Left` carries the admin URL to connect to; `Right` means
    * "embedded".
    */
  private[testkit] def transportFromEnv(env: Map[String, String]): Either[URI, Unit] =
    env.get("RIFT_ADMIN_URL").filter(_.nonEmpty) match
      case Some(url) => Left(URI.create(url))
      case None => Right(())

  /** An imposter scoped to the enclosing test: created now, deleted when the test's `Scope` closes
    * — no manual cleanup required even when a test fails partway through.
    */
  def imposter(builder: ImposterBuilder): ZIO[Rift & Scope, RiftError, ImposterHandle] =
    ZIO.acquireRelease(Rift.create(builder))(_.delete.orDie)

  /** A `_rift` flow-state space scoped to the test, on a fresh `FlowId` — for correlated isolation
    * on an imposter shared across many tests (typically one provisioned via
    * `aspects.withImposter`).
    */
  def space(handle: ImposterHandle): ZIO[Scope, RiftError, SpaceHandle] =
    for
      flowId <- freshFlowId
      space <- ZIO.acquireRelease(ZIO.succeed(handle.space(flowId)))(_.delete.orDie)
    yield space

  private def freshFlowId: UIO[FlowId] =
    Random.nextUUID.flatMap { uuid =>
      // A UUID's canonical string form is 36 characters and never empty, so `FlowId.from` cannot
      // actually observe the one input it rejects here. `.orDie` turns that unreachable branch into
      // a defect instead of laundering a can't-happen `Left` back through the return type.
      ZIO.fromEither(FlowId.from(uuid.toString)).orDieWith(new IllegalStateException(_))
    }
