package rift.cats.testkit.weaver

import _root_.cats.effect.{IO, Resource}
import _root_.scala.util.Try

import rift.cats.Rift

/** `weaver.ResourceTag` is keyed off a `ClassTag`, and its companion deliberately makes the derived
  * instance ambiguous (`implicitAmbiguous`) whenever the stored type has the shape `HKF[F[_]]` — a
  * type-erasure footgun guard, since `Rift[IO]` and (say) a hypothetical `Rift[Task]` would erase
  * to the same runtime class. `Rift[IO]` matches that guarded shape (`Rift` is `trait Rift[F[_]]`),
  * so `global.getOrFailR[Rift[IO]]()` below needs a hand-written `ResourceTag` rather than the
  * derived one weaver's docs recommend for erasure-safe types; this is the one escape hatch weaver
  * itself documents in the ambiguity error. Only `Rift[IO]` is ever stored under this tag in this
  * module (no other `F` appears), so the unchecked cast below is safe in practice despite being
  * unchecked in principle.
  */
private given riftIOResourceTag: _root_.weaver.ResourceTag[Rift[IO]] with
  def description: String = "rift.cats.Rift[cats.effect.IO]"
  def cast(obj: Any): Option[Rift[IO]] = Try(obj.asInstanceOf[Rift[IO]]).toOption

/** Shares one embedded engine across every suite in a run via weaver's global-resource channel
  * (DESIGN.md §5.8) — register with `--shared-resources rift.cats.testkit.weaver.RiftGlobal` (or
  * weaver's SBT auto-discovery of top-level `GlobalResource` objects).
  *
  * `weaver.GlobalResource`/`GlobalWrite` are `IO`-specialised aliases the `weaver` package object
  * exposes (`GlobalResource = IOGlobalResource`, `GlobalWrite = GlobalResourceF.Write[IO]`) — not
  * separate top-level classes, despite DESIGN.md §5.8's sketch naming them as if they were.
  *
  * Library references in this file are `_root_`-qualified (`_root_.weaver...`): this package is
  * itself named `rift.cats.testkit.weaver` (mirroring the real `weaver` library's own package name
  * per DESIGN.md §5.8), and Scala resolves an unqualified `weaver.X` from sibling code in the
  * enclosing `rift.cats.testkit` package against *that* nested package first — silently shadowing
  * the real one — unless qualified from `_root_`.
  */
object RiftGlobal extends _root_.weaver.GlobalResource:
  def sharedResources(global: _root_.weaver.GlobalWrite): Resource[IO, Unit] =
    Rift.embedded[IO].flatMap(global.putR(_))

/** Per-suite convenience over `RiftGlobal`'s shared engine: `Res = Rift[IO]`, read back via
  * `GlobalRead#getOrFailR`. A concrete suite supplies the `global` constructor argument itself —
  * weaver's runner detects and passes it reflectively, mirroring every other `IOSuite` subclass.
  */
abstract class RiftIOSuite(global: _root_.weaver.GlobalRead) extends _root_.weaver.IOSuite:
  type Res = Rift[IO]
  def sharedResource: Resource[IO, Rift[IO]] = global.getOrFailR[Rift[IO]]()
